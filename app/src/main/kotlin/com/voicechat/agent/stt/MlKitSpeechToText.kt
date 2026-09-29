package com.voicechat.agent.stt

import android.os.ParcelFileDescriptor
import com.google.mlkit.genai.common.FeatureStatus
import com.google.mlkit.genai.common.audio.AudioSource
import com.google.mlkit.genai.speechrecognition.SpeechRecognition
import com.google.mlkit.genai.speechrecognition.SpeechRecognizer
import com.google.mlkit.genai.speechrecognition.SpeechRecognizerResponse
import com.google.mlkit.genai.speechrecognition.speechRecognizerRequest
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.SpeechToText
import com.voicechat.agent.contracts.SttEvent
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device [SpeechToText] backed by ML Kit GenAI Speech Recognition — the
 * single STT engine selected in `docs/decisions.md` §2.1.
 *
 * **Runtime gating.** Before any recognition work, the adapter calls
 * `checkStatus()` and refuses to start unless the status is `AVAILABLE`. A
 * downloadable, downloading, or unavailable feature is surfaced as a typed
 * [SttEvent.Failed] (`STT_MODEL_NOT_READY` or `STT_UNAVAILABLE`); STT is never
 * presented as ready and there is no cloud fallback.
 *
 * **Audio path.** The adapter consumes the M02 [AudioFrame] stream (16 kHz mono
 * 16-bit PCM), re-encodes each frame to little-endian PCM
 * ([PcmFraming.toLittleEndianPcm]), and writes it to a pipe passed to
 * `AudioSource.fromPfd`. No preprocessing (gain, AEC, noise suppression,
 * resampling) is applied here; that belongs to capture (M07). `fromPfd` needs a
 * real-time stream, which live capture provides. Replay tests therefore drive
 * the contract through the M03 replay adapter instead of this class, and no
 * test claims this engine is available.
 *
 * **Lifecycle.** One recognizer is created per [transcribe] session and closed
 * when the session ends; cancelling collection stops the audio pump, calls
 * `stopRecognition()`, and releases the client. [close] is idempotent.
 *
 * Vendor types stay in this file and in `MlKitSttMapping` / `MlKitSttStatus`;
 * the domain and contracts layers stay platform-free.
 */
class MlKitSpeechToText(
    private val engine: SttEngine,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
) : SpeechToText {
    override val engineId: EngineId = engine.engineId

    private val activeRecognizer = AtomicReference<SpeechRecognizer?>(null)
    private val closed = AtomicBoolean(false)

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            check(!closed.get()) { "MlKitSpeechToText has been closed" }

            val startedAtNanos = clock.nanoTime()
            val languageTag = engine.locale.toLanguageTag()
            var frameCount = 0
            var finalCharacterCount = 0
            var outcome = DiagnosticOutcome.CANCELLED
            var errorCode: String? = null
            AppLog.d {
                "stt: session start engine=${engine.engineId.value} model=${engine.modelId.value} locale=$languageTag"
            }
            record(DiagnosticOutcome.STARTED, startedAtNanos, frameCount, finalCharacterCount, errorCode)

            val recognizer = SpeechRecognition.getClient(engine.toRecognizerOptions())
            activeRecognizer.set(recognizer)
            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]

            try {
                val status = statusOrNull(recognizer)
                if (status != FeatureStatus.AVAILABLE) {
                    val kind =
                        if (status == FeatureStatus.DOWNLOADABLE || status == FeatureStatus.DOWNLOADING) {
                            SttFailureKind.NOT_READY
                        } else {
                            SttFailureKind.UNAVAILABLE
                        }
                    AppLog.w { "stt: refused to start; feature status=$status" }
                    val event =
                        SttResultAssembler(languageTag)
                            .onResponse(SttEngineResponse.Failure(kind, detail = "feature status $status"))
                            .first() as SttEvent.Failed
                    outcome = DiagnosticOutcome.FAILED
                    errorCode = event.error.code.name
                    emit(event)
                    return@flow
                }

                val assembler = SttResultAssembler(languageTag)
                coroutineScope {
                    // ML Kit delivers responses on its own coroutine. A bounded
                    // channel lets the stopper end this session even if the
                    // engine's flow does not complete after a stop.
                    val responses = Channel<SttEngineResponse>(Channel.UNLIMITED)
                    val pump = launch { pumpAudio(audio, writeEnd) { frameCount++ } }
                    val collector =
                        launch {
                            try {
                                recognizer
                                    .startRecognition(speechRecognizerRequest { audioSource = AudioSource.fromPfd(readEnd) })
                                    .collect { response -> responses.trySend(response.toEngineResponse()) }
                                responses.close()
                            } catch (cancellation: CancellationException) {
                                // A sibling cancelled this collection; leave the
                                // channel open so the reader can still finalize.
                                throw cancellation
                            } catch (failure: Throwable) {
                                responses.close(failure)
                            }
                        }
                    // The response flow does not complete on capture EOF; only
                    // stopRecognition() completes it. Stop once capture ends, then
                    // bound the wait so a stuck flow cannot hang the turn.
                    val stopper =
                        launch {
                            pump.join()
                            AppLog.d { "stt: input ended frames=$frameCount; stopping recognition" }
                            if (assembler.onInputEnded()) {
                                withContext(NonCancellable) { runCatching { recognizer.stopRecognition() } }
                            }
                            if (withTimeoutOrNull(STOP_COMPLETION_TIMEOUT_MILLIS) { collector.join() } == null) {
                                AppLog.w { "stt: recognition did not complete after stop; finalizing anyway" }
                                collector.cancel()
                                responses.close()
                            }
                        }

                    try {
                        for (response in responses) {
                            assembler.onResponse(response).forEach { event ->
                                when (event) {
                                    is SttEvent.Result -> {
                                        if (event.transcript.isFinal) {
                                            outcome = DiagnosticOutcome.COMPLETED
                                            finalCharacterCount = event.transcript.text.length
                                        }
                                    }

                                    is SttEvent.Failed -> {
                                        outcome = DiagnosticOutcome.FAILED
                                        errorCode = event.error.code.name
                                    }
                                }
                                emit(event)
                            }
                        }
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Throwable) {
                        AppLog.e { "stt: recognition stream failed" }
                        assembler
                            .onResponse(
                                SttEngineResponse.Failure(
                                    SttFailureKind.RECOGNITION_FAILED,
                                    detail = "recognition stream failed",
                                ),
                            ).forEach { event ->
                                outcome = DiagnosticOutcome.FAILED
                                errorCode = (event as SttEvent.Failed).error.code.name
                                emit(event)
                            }
                    }

                    // The engine can end without a `Completed` response (for
                    // example when capture stops first); finalize anyway.
                    assembler.finish().forEach { event ->
                        if (outcome != DiagnosticOutcome.FAILED && event is SttEvent.Result) {
                            outcome = DiagnosticOutcome.COMPLETED
                            finalCharacterCount = event.transcript.text.length
                        }
                        emit(event)
                    }

                    withContext(NonCancellable) { runCatching { recognizer.stopRecognition() } }
                    stopper.cancel()
                    collector.cancel()
                    pump.cancel()
                }
            } catch (cancellation: CancellationException) {
                AppLog.d { "stt: session cancelled frames=$frameCount" }
                outcome = DiagnosticOutcome.CANCELLED
                throw cancellation
            } finally {
                record(outcome, startedAtNanos, frameCount, finalCharacterCount, errorCode)
                AppLog.d { "stt: session end outcome=$outcome frames=$frameCount chars=$finalCharacterCount" }
                activeRecognizer.compareAndSet(recognizer, null)
                runCatching { recognizer.close() }
                runCatching { readEnd.close() }
                runCatching { writeEnd.close() }
            }
        }.flowOn(Dispatchers.IO)

    override suspend fun close() {
        closed.set(true)
        activeRecognizer.getAndSet(null)?.let { recognizer -> runCatching { recognizer.close() } }
    }

    private fun SpeechRecognizerResponse.toEngineResponse(): SttEngineResponse =
        when (this) {
            is SpeechRecognizerResponse.PartialTextResponse -> SttEngineResponse.Partial(text)
            is SpeechRecognizerResponse.FinalTextResponse -> SttEngineResponse.Final(text)
            is SpeechRecognizerResponse.CompletedResponse -> SttEngineResponse.Completed
            is SpeechRecognizerResponse.ErrorResponse -> e.toEngineResponse()
        }

    private suspend fun statusOrNull(recognizer: SpeechRecognizer): Int? =
        try {
            recognizer.checkStatus()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            null
        }

    private suspend fun pumpAudio(
        audio: Flow<AudioFrame>,
        writeEnd: ParcelFileDescriptor,
        onFrameWritten: () -> Unit,
    ) {
        try {
            FileOutputStream(writeEnd.fileDescriptor).use { output ->
                audio.collect { frame ->
                    output.write(PcmFraming.toLittleEndianPcm(frame))
                    onFrameWritten()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The recognizer closes the read side when the session ends; a failed
            // write only ends this session's audio pump.
        }
    }

    private fun record(
        outcome: DiagnosticOutcome,
        startedAtNanos: Long,
        frameCount: Int,
        characterCount: Int,
        errorCode: String?,
    ) {
        val now = clock.nanoTime()
        diagnostics.record(
            DiagnosticEvent(
                stage = DiagnosticStage.SPEECH_TO_TEXT,
                outcome = outcome,
                monotonicTimeNanos = now,
                durationNanos = if (outcome == DiagnosticOutcome.STARTED) null else now - startedAtNanos,
                attributes =
                    buildMap {
                        put(DiagnosticAttribute.ENGINE_ID, engine.engineId.value)
                        put(DiagnosticAttribute.MODEL_ID, engine.modelId.value)
                        put(DiagnosticAttribute.FRAME_COUNT, frameCount.toString())
                        if (outcome != DiagnosticOutcome.STARTED) {
                            put(DiagnosticAttribute.CHARACTER_COUNT, characterCount.toString())
                        }
                        errorCode?.let { put(DiagnosticAttribute.ERROR_CODE, it) }
                    },
            ),
        )
    }

    private companion object {
        /**
         * Bound on how long the adapter waits for the engine's response flow to
         * complete after `stopRecognition()`. The engine completes promptly on
         * device; the bound exists so a stuck flow finalizes the transcript
         * instead of hanging the turn.
         */
        const val STOP_COMPLETION_TIMEOUT_MILLIS = 3_000L
    }
}
