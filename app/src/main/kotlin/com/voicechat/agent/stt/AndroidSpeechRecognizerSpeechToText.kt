package com.voicechat.agent.stt

import android.content.Context
import android.os.Build
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The platform operations one recognition session needs.
 *
 * It is the seam that keeps [AndroidSpeechRecognizerSpeechToText]'s lifecycle
 * logic JVM-testable: the real implementation delegates to
 * `android.speech.SpeechRecognizer`, and a test drives a fake.
 */
interface PlatformRecognizer {
    fun setListener(listener: RecognitionListener)

    fun startListening(intent: android.content.Intent)

    fun stopListening()

    fun cancel()

    fun destroy()
}

/** Supplies the platform recognizer for a session; faked in JVM tests. */
interface RecognizerProvider {
    /** True when the device exposes an on-device recognizer. */
    fun isOnDeviceAvailable(): Boolean

    /** Creates one recognizer, on-device when [onDevice] is true. */
    fun create(onDevice: Boolean): PlatformRecognizer
}

/** The production [RecognizerProvider] over the platform `SpeechRecognizer`. */
class AndroidRecognizerProvider(
    context: Context,
) : RecognizerProvider {
    private val appContext = context.applicationContext

    override fun isOnDeviceAvailable(): Boolean = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)

    override fun create(onDevice: Boolean): PlatformRecognizer =
        AndroidPlatformRecognizer(
            if (onDevice) {
                SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            } else {
                SpeechRecognizer.createSpeechRecognizer(appContext)
            },
        )
}

private class AndroidPlatformRecognizer(
    private val speech: SpeechRecognizer,
) : PlatformRecognizer {
    override fun setListener(listener: RecognitionListener) = speech.setRecognitionListener(listener)

    override fun startListening(intent: android.content.Intent) = speech.startListening(intent)

    override fun stopListening() = speech.stopListening()

    override fun cancel() = speech.cancel()

    override fun destroy() = speech.destroy()
}

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
 * **One session at a time.** The platform recognizer permits exactly one active
 * session and answers a second `startListening` with `ERROR_RECOGNIZER_BUSY`.
 * The coordinator deliberately lets turn *N*'s finalization overlap turn *N+1*,
 * so [transcribe] serializes on [sessionMutex]: a new turn's session waits for
 * the previous one to finish instead of colliding with it.
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
 * [SttEvent.EndOfSpeech] and recorded (`END_OF_SPEECH_MILLIS`), but it is **not**
 * a turn boundary. Measured on Pixel 10 (M27), the on-device recognizer emits
 * `onEndOfSpeech` well before the utterance ends and keeps streaming partials,
 * and it delivers `onResults` only when the custom audio closes. So the bounded
 * M09 VAD/Smart Turn endpoint closes the turn input, the adapter closes the pipe,
 * and `onResults` then follows (measured 150-350 ms after input end); the
 * silence cap remains the fallback when the engine never signals end-of-speech.
 * Neither a stop from inside the `onEndOfSpeech` callback nor a
 * `stopListening()` while the recognizer reads the pipe is used: both made the
 * on-device recognizer answer `ERROR_CLIENT`.
 *
 * **Logging.** Every recognition callback is logged. High-frequency callbacks
 * (`onRmsChanged`, `onBufferReceived`) and repeated `onEndOfSpeech` are
 * verbose-only, and partial text is verbose-only, so release logs stay
 * content-free and the main thread is not saturated by logging.
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
    private val recognizerProvider: RecognizerProvider = AndroidRecognizerProvider(context),
) : SpeechToText {
    private val appContext = context.applicationContext

    override val engineId: EngineId = engine.engineId

    private val activeRecognizer = AtomicReference<PlatformRecognizer?>(null)
    private val closed = AtomicBoolean(false)

    /** Serializes sessions: the platform recognizer allows only one at a time. */
    private val sessionMutex = Mutex()

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        flow {
            check(!closed.get()) { "AndroidSpeechRecognizerSpeechToText has been closed" }
            sessionMutex.withLock {
                val languageTag = AndroidSpeechSttMapping.languageTag(engine)
                val startedAtNanos = clock.nanoTime()
                val onDevice = preferOnDevice && recognizerProvider.isOnDeviceAvailable()
                var frameCount = 0
                var partialCount = 0
                var firstPartialNanos: Long? = null
                var endOfSpeechNanos: Long? = null
                var finalCharacterCount = 0
                var outcome = DiagnosticOutcome.CANCELLED
                var errorCode: String? = null
                AppLog.i {
                    "asr: session start engine=${engine.engineId.value} model=${engine.modelId.value} " +
                        "mode=${AndroidSpeechSttMapping.modeLabel(onDevice)} locale=$languageTag"
                }
                record(
                    DiagnosticOutcome.STARTED,
                    startedAtNanos,
                    frameCount,
                    partialCount,
                    firstPartialNanos,
                    endOfSpeechNanos,
                    onDevice,
                    null,
                )

                val pipe = ParcelFileDescriptor.createPipe()
                val readEnd = pipe[0]
                val writeEnd = pipe[1]
                val responses = Channel<SttEngineResponse>(Channel.UNLIMITED)
                val terminated = CompletableDeferred<Unit>()
                val assembler = SttResultAssembler(languageTag)

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
                                recognizerProvider.create(onDevice)
                            }
                        activeRecognizer.set(recognizer)

                        // One stop per session, shared by the end-of-speech handler and the
                        // input-end stopper, so the recognizer is never stopped twice.
                        val stopRequested = AtomicBoolean(false)

                        fun requestStop() {
                            if (stopRequested.compareAndSet(false, true)) {
                                runCatching { recognizer.stopListening() }
                            }
                        }

                        val bridge =
                            PlatformRecognitionBridge(
                                responses = responses,
                                terminated = terminated,
                                onRecognitionEvent = { event -> logCallback(event) },
                                onPartial = {
                                    partialCount++
                                    if (firstPartialNanos == null) firstPartialNanos = clock.nanoTime()
                                },
                                recordEndOfSpeech = {
                                    if (endOfSpeechNanos == null) endOfSpeechNanos = clock.nanoTime()
                                },
                            )
                        withContext(Dispatchers.Main) {
                            recognizer.setListener(bridge)
                            AppLog.i { "asr: startListening mode=${AndroidSpeechSttMapping.modeLabel(onDevice)}" }
                            recognizer.startListening(AndroidSpeechSttMapping.recognizerIntent(engine, readEnd))
                        }

                        // When the turn's input ends (the bounded VAD endpoint closed it),
                        // end recognition and bound the wait for the final result. With a
                        // custom audio source the documented end signal is closing the
                        // audio, so the pipe is closed first; an explicit stop is only
                        // needed for a recognizer that opened the microphone (API < 33,
                        // where EXTRA_AUDIO_SOURCE does not exist). Calling stopListening
                        // on the on-device recognizer while it reads the pipe made it
                        // answer ERROR_CLIENT (observed on Pixel 10, M27).
                        val stopper =
                            launch {
                                drain.join()
                                AppLog.i { "asr: input ended frames=$frameCount; ending recognition" }
                                writer.cancel()
                                runCatching { writeEnd.close() }
                                if (Build.VERSION.SDK_INT < CUSTOM_AUDIO_SOURCE_MIN_API) {
                                    withContext(Dispatchers.Main) { requestStop() }
                                }
                                if (withTimeoutOrNull(STOP_COMPLETION_TIMEOUT_MILLIS) { terminated.await() } == null) {
                                    AppLog.w { "asr: recognition did not complete after input end; finalizing anyway" }
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
                    record(
                        outcome,
                        startedAtNanos,
                        frameCount,
                        partialCount,
                        firstPartialNanos,
                        endOfSpeechNanos,
                        onDevice,
                        errorCode,
                    )
                    AppLog.i {
                        "asr: session end outcome=$outcome frames=$frameCount partials=$partialCount chars=$finalCharacterCount"
                    }
                    activeRecognizer.getAndSet(null)?.let { recognizer ->
                        withContext(NonCancellable) {
                            runCatching { withContext(Dispatchers.Main) { recognizer.destroy() } }
                        }
                    }
                    runCatching { readEnd.close() }
                    runCatching { writeEnd.close() }
                }
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
            RecognitionEvent.EndOfSpeech -> AppLog.v { "asr: onEndOfSpeech" }
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
                AppLog.v { "asr: end of speech" }
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
        onDevice: Boolean,
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
                        put(DiagnosticAttribute.RECOGNIZER_MODE, AndroidSpeechSttMapping.modeLabel(onDevice))
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

        /** API level that added `RecognizerIntent.EXTRA_AUDIO_SOURCE`. */
        const val CUSTOM_AUDIO_SOURCE_MIN_API = 33
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
 * session flow does the actual assembling off the main thread. `onEndOfSpeech`
 * is forwarded at most once and never stops the recognizer from the callback:
 * the stop is driven by the input-end path, and a re-entrant stop made the
 * on-device recognizer answer `ERROR_CLIENT`.
 */
private class PlatformRecognitionBridge(
    private val responses: Channel<SttEngineResponse>,
    private val terminated: CompletableDeferred<Unit>,
    private val onRecognitionEvent: (RecognitionEvent) -> Unit,
    private val onPartial: () -> Unit,
    private val recordEndOfSpeech: () -> Unit,
) : RecognitionListener {
    private val endOfSpeechForwarded = AtomicBoolean(false)

    override fun onReadyForSpeech(params: Bundle?) {
        onRecognitionEvent(RecognitionEvent.ReadyForSpeech)
    }

    override fun onBeginningOfSpeech() {
        onRecognitionEvent(RecognitionEvent.BeginningOfSpeech)
    }

    override fun onRmsChanged(rmsdB: Float) {
        onRecognitionEvent(RecognitionEvent.RmsChanged(rmsdB))
    }

    override fun onBufferReceived(buffer: ByteArray?) {
        onRecognitionEvent(RecognitionEvent.BufferReceived(buffer?.size ?: 0))
    }

    override fun onEndOfSpeech() {
        onRecognitionEvent(RecognitionEvent.EndOfSpeech)
        recordEndOfSpeech()
        // Forwarded at most once. The recognizer finalizes on its own after this;
        // stopping re-entrantly from the callback made the on-device recognizer
        // answer ERROR_CLIENT (observed on Pixel 10, M27), so the stop is driven
        // only by the input-end path.
        if (endOfSpeechForwarded.compareAndSet(false, true)) {
            responses.trySend(SttEngineResponse.EndOfSpeech)
        }
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
        onRecognitionEvent(RecognitionEvent.Event(eventType))
    }
}

private fun Bundle?.firstResult(): String = this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

private fun Bundle?.firstConfidence(): Float? {
    val scores = this?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES) ?: return null
    val value = scores.firstOrNull() ?: return null
    return value.takeIf { it >= 0f }
}
