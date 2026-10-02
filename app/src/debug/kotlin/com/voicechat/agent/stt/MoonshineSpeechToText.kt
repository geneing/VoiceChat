package com.voicechat.agent.stt

import android.content.Context
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
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.EngineId
import com.voicechat.agent.domain.ModelId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.job
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/** English Moonshine streaming model candidates; deliberately not a user setting yet. */
enum class MoonshineSttModel(
    val modelId: ModelId,
    internal val architecture: Int,
) {
    TINY_STREAMING(ModelId("moonshine-tiny-streaming-en"), 2),
    SMALL_STREAMING(ModelId("moonshine-small-streaming-en"), 4),
    ;

    companion object {
        val ENGINE_ID = EngineId("moonshine-voice")
    }
}

/**
 * Debug-only Moonshine Voice adapter for candidate evaluation.
 *
 * Each cold collection owns one streaming session. Model files and the native
 * transcriber are retained for this adapter's lifetime, while each turn uses a
 * fresh native stream. Cancellation frees that stream without calling Moonshine
 * `stopStream` (which flushes a final result). All native calls for a session
 * happen serially on its collector coroutine; [close] cancels that collector and
 * waits for its cleanup before releasing the native transcriber.
 */
class MoonshineSpeechToText internal constructor(
    context: Context,
    val model: MoonshineSttModel,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val runtime: MoonshineSttRuntime = AndroidMoonshineSttRuntime(context.applicationContext, model),
    private val workerDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val updateIntervalSeconds: Double = DEFAULT_UPDATE_INTERVAL_SECONDS,
) : SpeechToText {
    init {
        require(updateIntervalSeconds >= 0.0) { "updateIntervalSeconds must not be negative" }
    }

    override val engineId: EngineId = MoonshineSttModel.ENGINE_ID

    private val sessionGate = Mutex()
    private val closeStarted = AtomicBoolean(false)
    private val lifecycleLock = Any()
    private val closeCompleted = CompletableDeferred<Unit>()
    private val activeCollector = AtomicReference<Job?>(null)

    override fun transcribe(audio: Flow<AudioFrame>): Flow<SttEvent> =
        channelFlow {
            val collectorJob = currentCoroutineContext().job
            sessionGate.withLock {
                synchronized(lifecycleLock) {
                    check(!closeStarted.get()) { "MoonshineSpeechToText has been closed" }
                    activeCollector.set(collectorJob)
                }

                val startedAtNanos = clock.nanoTime()
                val assembler = SttResultAssembler(languageTag = "en")
                var session: MoonshineSttSession? = null
                var frameCount = 0
                var finalCharacterCount = 0
                var outcome = DiagnosticOutcome.CANCELLED
                var errorCode: String? = null
                var firstPartialRecorded = false
                record(DiagnosticOutcome.STARTED, startedAtNanos, frameCount, finalCharacterCount, errorCode)

                fun emitResponse(response: SttEngineResponse) {
                    synchronized(assembler) {
                        assembler.onResponse(response).forEach { event ->
                            if (event is SttEvent.Result && !event.transcript.isFinal && !firstPartialRecorded) {
                                firstPartialRecorded = true
                                val now = clock.nanoTime()
                                diagnostics.record(
                                    DiagnosticEvent(
                                        stage = DiagnosticStage.SPEECH_TO_TEXT,
                                        outcome = DiagnosticOutcome.PROGRESS,
                                        monotonicTimeNanos = now,
                                        durationNanos = (now - startedAtNanos).coerceAtLeast(0L),
                                        attributes =
                                            mapOf(
                                                DiagnosticAttribute.ENGINE_ID to engineId.value,
                                                DiagnosticAttribute.MODEL_ID to model.modelId.value,
                                                DiagnosticAttribute.STREAM_STATE to "first_partial",
                                            ),
                                    ),
                                )
                            }
                            if (event is SttEvent.Failed) {
                                outcome = DiagnosticOutcome.FAILED
                                errorCode = event.error.code.name
                            }
                            trySend(event)
                        }
                    }
                }

                try {
                    try {
                        session =
                            withContext(workerDispatcher) {
                                runtime.openSession(updateIntervalSeconds, ::emitResponse)
                            }
                        val loadedAtNanos = clock.nanoTime()
                        diagnostics.record(
                            DiagnosticEvent(
                                stage = DiagnosticStage.SPEECH_TO_TEXT,
                                outcome = DiagnosticOutcome.PROGRESS,
                                monotonicTimeNanos = loadedAtNanos,
                                durationNanos = (loadedAtNanos - startedAtNanos).coerceAtLeast(0L),
                                attributes =
                                    mapOf(
                                        DiagnosticAttribute.ENGINE_ID to engineId.value,
                                        DiagnosticAttribute.MODEL_ID to model.modelId.value,
                                        DiagnosticAttribute.STREAM_STATE to "model_loaded",
                                    ),
                            ),
                        )
                    } catch (cancellation: CancellationException) {
                        throw cancellation
                    } catch (_: Exception) {
                        emitResponse(
                            SttEngineResponse.Failure(
                                SttFailureKind.NOT_READY,
                                detail = "Moonshine model download or load failed",
                            ),
                        )
                    }

                    val activeSession = session
                    if (activeSession != null && !assembler.isFinished) {
                        audio.collect { frame ->
                            if (assembler.isFinished) throw MoonshineTerminalResponse()
                            require(frame.format == AudioFormat.MONO_16_KHZ) {
                                "Moonshine requires mono 16 kHz PCM frames"
                            }
                            val samples = frame.samples.toNormalizedFloatPcm()
                            withContext(workerDispatcher) {
                                activeSession.addAudio(samples, frame.format.sampleRateHz)
                            }
                            frameCount++
                            if (assembler.isFinished) throw MoonshineTerminalResponse()
                        }

                        assembler.onInputEnded()
                        withContext(workerDispatcher) { activeSession.stopAndFlush() }
                        synchronized(assembler) {
                            assembler.finish().forEach { event ->
                                if (event is SttEvent.Result && event.transcript.isFinal) {
                                    finalCharacterCount = event.transcript.text.length
                                    if (outcome != DiagnosticOutcome.FAILED) outcome = DiagnosticOutcome.COMPLETED
                                }
                                trySend(event)
                            }
                        }
                    }
                } catch (cancellation: CancellationException) {
                    outcome = DiagnosticOutcome.CANCELLED
                    throw cancellation
                } catch (_: MoonshineTerminalResponse) {
                    // The listener already emitted the typed terminal failure.
                } catch (_: Exception) {
                    if (!assembler.isFinished) {
                        emitResponse(
                            SttEngineResponse.Failure(
                                SttFailureKind.RECOGNITION_FAILED,
                                detail = "Moonshine recognition failed",
                            ),
                        )
                    }
                } finally {
                    withContext(NonCancellable + workerDispatcher) {
                        runCatching { session?.close() }
                    }
                    activeCollector.compareAndSet(collectorJob, null)
                    record(outcome, startedAtNanos, frameCount, finalCharacterCount, errorCode)
                }
            }
        }.flowOn(workerDispatcher)

    override suspend fun close() {
        val shouldClose =
            synchronized(lifecycleLock) {
                closeStarted.compareAndSet(false, true)
            }
        if (!shouldClose) {
            closeCompleted.await()
            return
        }

        try {
            activeCollector.get()?.cancel(CancellationException("Moonshine STT closed"))
            withContext(NonCancellable + workerDispatcher) {
                sessionGate.withLock { runtime.close() }
            }
        } finally {
            closeCompleted.complete(Unit)
        }
    }

    private fun ShortArray.toNormalizedFloatPcm(): FloatArray = FloatArray(size) { index -> this[index].toFloat() / 32_768f }

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
                durationNanos = if (outcome == DiagnosticOutcome.STARTED) null else (now - startedAtNanos).coerceAtLeast(0L),
                attributes =
                    buildMap {
                        put(DiagnosticAttribute.ENGINE_ID, engineId.value)
                        put(DiagnosticAttribute.MODEL_ID, model.modelId.value)
                        put(DiagnosticAttribute.FRAME_COUNT, frameCount.toString())
                        if (outcome != DiagnosticOutcome.STARTED) {
                            put(DiagnosticAttribute.CHARACTER_COUNT, characterCount.toString())
                        }
                        errorCode?.let { put(DiagnosticAttribute.ERROR_CODE, it) }
                    },
            ),
        )
    }

    companion object {
        const val DEFAULT_UPDATE_INTERVAL_SECONDS = 0.5
    }

    private class MoonshineTerminalResponse : RuntimeException()
}

/** Narrow adapter seam that lets JVM tests exercise ordering without loading JNI. */
internal interface MoonshineSttRuntime {
    suspend fun openSession(
        updateIntervalSeconds: Double,
        onResponse: (SttEngineResponse) -> Unit,
    ): MoonshineSttSession

    fun close()
}

/** One native Moonshine stream. Calls are serialized by [MoonshineSpeechToText]. */
internal interface MoonshineSttSession {
    fun addAudio(
        samples: FloatArray,
        sampleRateHz: Int,
    )

    /** Stops this stream and forces the trailing final transcript update. */
    fun stopAndFlush()

    /** Releases this stream without requesting a final result. */
    fun close()
}
