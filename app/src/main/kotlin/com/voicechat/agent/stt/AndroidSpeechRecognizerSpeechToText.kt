package com.voicechat.agent.stt

import android.content.Context
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.SpeechRecognizer
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
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * On-device [SpeechToText] backed by the platform `android.speech.SpeechRecognizer`
 * API (M27) — the standard Android speech recognition path, replacing the
 * app-managed Parakeet-EOU ONNX engine.
 *
 * **On device, always.** The recognizer is created with
 * `createOnDeviceSpeechRecognizer` when the device exposes one; otherwise the
 * generic recognizer is started with `EXTRA_PREFER_OFFLINE`. No network
 * recognizer is selected on the user's behalf, and availability is gated by
 * [AndroidSpeechSttStatus] before a session starts.
 *
 * **Audio path.** The recognizer's custom-audio source
 * (`RecognizerIntent.EXTRA_AUDIO_SOURCE`, API 33+) is fed the same captured
 * 16 kHz mono 16-bit PCM frames the VAD already sees: the adapter drains the M02
 * [AudioFrame] stream into a pipe, so there is one microphone owner and the same
 * code path serves live capture and replay of a staged file. When the
 * recognizer does not honour the extra it falls back to the microphone; that is
 * a documented limitation, not a silent substitution
 * (`docs/android-speech-recognition.md`).
 *
 * **End of speech.** The recognizer's `onEndOfSpeech` is surfaced as
 * [SttEvent.EndOfSpeech] and recorded, but it is **not** the authority for
 * ending a turn: the bounded M09 VAD/Smart Turn endpoint closes the turn and
 * this adapter's input, which then calls `stopListening()` and waits, bounded,
 * for the final result. Partial hypotheses are surfaced as interim
 * [SttEvent.Result]s through [SttResultAssembler].
 *
 * **Logging.** Every recognition callback is logged. Partial text is logged only
 * at verbose level (developer logs, off in release); the structured logs and the
 * diagnostics sink carry counts, stable codes, and timings, never transcript
 * content.
 *
 * **Lifecycle.** One recognizer is created per [transcribe] session and released
 * when the session ends; [close] is idempotent. Platform recognizer calls run on
 * the main thread, as the API requires.
 */
class AndroidSpeechRecognizerSpeechToText(
    private val context: Context,
    private val engine: SttEngine,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val preferOnDevice: Boolean = true,
) : SpeechToText {
    private val appContext = context.applicationContext

    override val engineId: EngineId = engine.engineId

    private val activeRecognizer = AtomicReference<SpeechRecognizer?>(null)
    private val closed = AtomicBoolean(false)

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            check(!closed.get()) { "AndroidSpeechRecognizerSpeechToText has been closed" }

            val languageTag = AndroidSpeechSttMapping.languageTag(engine)
            val startedAtNanos = clock.nanoTime()
            var frameCount = 0
            var partialCount = 0
            var firstPartialNanos: Long? = null
            var endOfSpeechNanos: Long? = null
            var finalCharacterCount = 0
            var outcome = DiagnosticOutcome.CANCELLED
            var errorCode: String? = null
            AppLog.i {
                "asr: session start engine=${engine.engineId.value} model=${engine.modelId.value} locale=$languageTag"
            }
            record(DiagnosticOutcome.STARTED, startedAtNanos, frameCount, partialCount, firstPartialNanos, endOfSpeechNanos, null)

            val pipe = ParcelFileDescriptor.createPipe()
            val readEnd = pipe[0]
            val writeEnd = pipe[1]
            val responses = Channel<SttEngineResponse>(Channel.UNLIMITED)
            val terminated = CompletableDeferred<Unit>()
            val assembler = SttResultAssembler(languageTag)
            val onDevice = preferOnDevice && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            val bridge =
                PlatformRecognitionBridge(
                    responses = responses,
                    terminated = terminated,
                    onEvent = { event -> logCallback(event) },
                    onPartial = {
                        partialCount++
                        if (firstPartialNanos == null) firstPartialNanos = clock.nanoTime()
                    },
                    onEndOfSpeech = {
                        if (endOfSpeechNanos == null) endOfSpeechNanos = clock.nanoTime()
                    },
                )

            try {
                coroutineScope {
                    // Drain the M02 audio stream into a small channel, then write it
                    // to the pipe from a separate coroutine. Decoupling the two keeps
                    // input completion observable even when the recognizer never
                    // reads the pipe (for example a recognizer that ignores
                    // EXTRA_AUDIO_SOURCE and opens the microphone instead).
                    val frames =
                        Channel<AudioFrame>(
                            capacity = FRAME_BUFFER,
                            onBufferOverflow = BufferOverflow.DROP_OLDEST,
                        )
                    val drain =
                        launch {
                            try {
                                audio.collect { frame ->
                                    frameCount++
                                    frames.trySend(frame)
                                }
                            } finally {
                                frames.close()
                            }
                        }
                    val writer =
                        launch(Dispatchers.IO) {
                            writeFrames(frames, writeEnd)
                        }

                    val recognizer =
                        withContext(Dispatchers.Main) {
                            createRecognizer(onDevice).also { it.setRecognitionListener(bridge) }
                        }
                    activeRecognizer.set(recognizer)

                    val intent = AndroidSpeechSttMapping.recognizerIntent(engine, readEnd)
                    withContext(Dispatchers.Main) {
                        AppLog.i { "asr: startListening mode=${AndroidSpeechSttMapping.modeLabel(onDevice)}" }
                        recognizer.startListening(intent)
                    }

                    // When the turn's input ends (the bounded VAD endpoint closed it),
                    // stop recognition and bound the wait for the final result.
                    val stopper =
                        launch {
                            drain.join()
                            AppLog.i { "asr: input ended frames=$frameCount; stopping recognition" }
                            if (assembler.onInputEnded()) {
                                withContext(Dispatchers.Main) { runCatching { recognizer.stopListening() } }
                            }
                            writer.cancel()
                            runCatching { writeEnd.close() }
                            if (withTimeoutOrNull(STOP_COMPLETION_TIMEOUT_MILLIS) { terminated.await() } == null) {
                                AppLog.w { "asr: recognition did not complete after stop; finalizing anyway" }
                                responses.close()
                            }
                        }

                    try {
                        for (response in responses) {
                            logResponse(response)
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
                                        errorCode = event.error.detail ?: event.error.code.name
                                    }

                                    SttEvent.EndOfSpeech -> {
                                        Unit
                                    }
                                }
                                emit(event)
                            }
                        }
                    } finally {
                        stopper.cancel()
                        writer.cancel()
                        drain.cancel()
                    }

                    // The terminal response (onResults/onError) always finishes the
                    // assembler; this is a no-op then, and a safety net otherwise.
                    assembler.finish().forEach { event ->
                        if (event is SttEvent.Result) {
                            outcome = DiagnosticOutcome.COMPLETED
                            finalCharacterCount = event.transcript.text.length
                        }
                        emit(event)
                    }

                    withContext(NonCancellable) { withContext(Dispatchers.Main) { runCatching { recognizer.stopListening() } } }
                    withContext(Dispatchers.Main) { runCatching { recognizer.destroy() } }
                    activeRecognizer.compareAndSet(recognizer, null)
                }
            } catch (cancellation: CancellationException) {
                AppLog.i { "asr: session cancelled frames=$frameCount" }
                outcome = DiagnosticOutcome.CANCELLED
                throw cancellation
            } catch (failure: Throwable) {
                AppLog.w(failure) { "asr: recognition session failed" }
                outcome = DiagnosticOutcome.FAILED
                errorCode = "ASR_SESSION_ERROR"
                emit(
                    SttEvent.Failed(
                        VoiceAgentError(
                            ErrorCode.STT_RECOGNITION_FAILED,
                            "speech recognition failed (${failure.javaClass.simpleName})",
                        ),
                    ),
                )
            } finally {
                runCatching { responses.close() }
                record(outcome, startedAtNanos, frameCount, partialCount, firstPartialNanos, endOfSpeechNanos, errorCode)
                AppLog.i {
                    "asr: session end outcome=$outcome frames=$frameCount partials=$partialCount chars=$finalCharacterCount"
                }
                activeRecognizer.getAndSet(null)?.let { recognizer ->
                    withContext(NonCancellable) { runCatching { withContext(Dispatchers.Main) { recognizer.destroy() } } }
                }
                runCatching { readEnd.close() }
                runCatching { writeEnd.close() }
            }
        }

    override suspend fun close() {
        closed.set(true)
        withContext(Dispatchers.Main) {
            activeRecognizer.getAndSet(null)?.let { recognizer ->
                runCatching { recognizer.cancel() }
                runCatching { recognizer.destroy() }
            }
        }
    }

    private fun createRecognizer(onDevice: Boolean): SpeechRecognizer =
        if (onDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }

    /** Writes framed PCM to the recognizer's audio pipe until [frames] closes. */
    private suspend fun writeFrames(
        frames: Channel<AudioFrame>,
        writeEnd: ParcelFileDescriptor,
    ) {
        try {
            FileOutputStream(writeEnd.fileDescriptor).use { output ->
                for (frame in frames) {
                    output.write(PcmFraming.toLittleEndianPcm(frame))
                    output.flush()
                }
            }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (_: Throwable) {
            // The recognizer closed the read side, or the session was cancelled;
            // either way the audio pump for this session simply ends.
        }
    }

    private fun logCallback(event: RecognitionEvent) {
        when (event) {
            RecognitionEvent.ReadyForSpeech -> AppLog.i { "asr: onReadyForSpeech" }
            RecognitionEvent.BeginningOfSpeech -> AppLog.i { "asr: onBeginningOfSpeech" }
            RecognitionEvent.EndOfSpeech -> AppLog.i { "asr: onEndOfSpeech" }
            is RecognitionEvent.RmsChanged -> AppLog.v { "asr: onRmsChanged rms=${event.rms}" }
            is RecognitionEvent.BufferReceived -> AppLog.v { "asr: onBufferReceived bytes=${event.byteCount}" }
            is RecognitionEvent.Event -> AppLog.d { "asr: onEvent type=${event.type}" }
        }
    }

    private fun logResponse(response: SttEngineResponse) {
        when (response) {
            is SttEngineResponse.Partial -> {
                // Partial recognition is logged (M27 developer visibility). The text
                // is verbose-only so release logs stay content-free.
                AppLog.v { "asr: partial chars=${response.text.length}" }
            }

            is SttEngineResponse.Final -> {
                AppLog.i { "asr: final segment chars=${response.text.length}" }
            }

            is SttEngineResponse.Failure -> {
                AppLog.w { "asr: failure kind=${response.kind} detail=${response.detail.orEmpty()}" }
            }

            SttEngineResponse.EndOfSpeech -> {
                AppLog.i { "asr: end of speech" }
            }

            SttEngineResponse.Stopped -> {
                AppLog.i { "asr: stopped" }
            }

            SttEngineResponse.Completed -> {
                AppLog.i { "asr: completed" }
            }
        }
    }

    private fun record(
        outcome: DiagnosticOutcome,
        startedAtNanos: Long,
        frameCount: Int,
        partialCount: Int,
        firstPartialNanos: Long?,
        endOfSpeechNanos: Long?,
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
                            put(DiagnosticAttribute.PARTIAL_COUNT, partialCount.toString())
                            firstPartialNanos?.let {
                                put(
                                    DiagnosticAttribute.FIRST_PARTIAL_MILLIS,
                                    ((it - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI).toString(),
                                )
                            }
                            endOfSpeechNanos?.let {
                                put(
                                    DiagnosticAttribute.END_OF_SPEECH_MILLIS,
                                    ((it - startedAtNanos).coerceAtLeast(0L) / NANOS_PER_MILLI).toString(),
                                )
                            }
                        }
                        errorCode?.let { put(DiagnosticAttribute.ERROR_CODE, it) }
                    },
            ),
        )
    }

    private companion object {
        const val FRAME_BUFFER = 64
        const val STOP_COMPLETION_TIMEOUT_MILLIS = 3_000L
        const val NANOS_PER_MILLI = 1_000_000L
    }
}

/** The subset of recognition callbacks the adapter mirrors into the engine model. */
private sealed interface RecognitionEvent {
    data object ReadyForSpeech : RecognitionEvent

    data object BeginningOfSpeech : RecognitionEvent

    data object EndOfSpeech : RecognitionEvent

    data class RmsChanged(
        val rms: Float,
    ) : RecognitionEvent

    data class BufferReceived(
        val byteCount: Int,
    ) : RecognitionEvent

    data class Event(
        val type: Int,
    ) : RecognitionEvent
}

/**
 * Bridges `RecognitionListener` callbacks into the channel the session flow reads.
 *
 * Callbacks arrive on the main thread, so they only log and `trySend`; the
 * session flow does the actual assembling off the main thread.
 */
private class PlatformRecognitionBridge(
    private val responses: Channel<SttEngineResponse>,
    private val terminated: CompletableDeferred<Unit>,
    private val onEvent: (RecognitionEvent) -> Unit,
    private val onPartial: () -> Unit,
    private val onEndOfSpeech: () -> Unit,
) : RecognitionListener {
    override fun onReadyForSpeech(params: Bundle?) {
        onEvent(RecognitionEvent.ReadyForSpeech)
    }

    override fun onBeginningOfSpeech() {
        onEvent(RecognitionEvent.BeginningOfSpeech)
    }

    override fun onRmsChanged(rmsdB: Float) {
        onEvent(RecognitionEvent.RmsChanged(rmsdB))
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        onEvent(RecognitionEvent.BufferReceived(buffer?.size ?: 0))
    }

    override fun onEndOfSpeech() {
        onEvent(RecognitionEvent.EndOfSpeech)
        onEndOfSpeech()
        responses.trySend(SttEngineResponse.EndOfSpeech)
    }

    override fun onError(error: Int) {
        AppLog.w { "asr: onError code=$error" }
        responses.trySend(AndroidSpeechSttMapping.toEngineResponse(error))
        responses.close()
        terminated.complete(Unit)
    }

    override fun onResults(results: Bundle?) {
        val text = results.firstResult()
        val confidence = results.firstConfidence()
        AppLog.i { "asr: onResults chars=${text.length}" }
        responses.trySend(SttEngineResponse.Final(text, confidence))
        responses.trySend(SttEngineResponse.Completed)
        responses.close()
        terminated.complete(Unit)
    }

    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults.firstResult()
        if (text.isNotBlank()) {
            onPartial()
            AppLog.v { "asr: onPartialResults text=$text" }
            responses.trySend(SttEngineResponse.Partial(text))
        }
    }

    override fun onEvent(
        eventType: Int,
        params: Bundle?,
    ) {
        onEvent(RecognitionEvent.Event(eventType))
    }
}

private fun Bundle?.firstResult(): String = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun Bundle?.firstConfidence(): Float? {
    val scores = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) ?: return null
    val value = scores.firstOrNull() ?: return null
    return value.takeIf { it >= 0f }
}
