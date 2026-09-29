package com.voicechat.agent.audio

import android.media.AudioRecord
import com.voicechat.agent.contracts.AudioInput
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield

/**
 * [AudioInput] backed by the device microphone.
 *
 * The implementation is the physical counterpart of the M03
 * [com.voicechat.agent.replay.ReplayAudioInput]: both emit
 * [AudioFormat.MONO_16_KHZ]-compatible [AudioFrame]s, so STT, VAD, and turn
 * detection consume live and replayed audio through the same contract.
 *
 * **Threading and lifecycle.** One collection of [frames] is one capture
 * session. All platform work — permission re-check, `AudioRecord` start, the
 * blocking read loop, route monitoring, and focus changes — runs on [dispatcher],
 * never the main thread. The session releases the recorder and focus in a
 * `finally` block, so completion, collector cancellation, and read failure all
 * clean up. [close] is idempotent, marks the input unusable, and stops an
 * in-flight session to unblock a pending read.
 *
 * **Backpressure.** Frames are offered to a bounded buffer; if the consumer
 * falls behind, frames are dropped and counted rather than letting the hardware
 * buffer overflow invisibly. Levels, clipping, and drops are reported through
 * the M04 [DiagnosticsSink] without retaining sample values.
 */
class MicrophoneAudioInput(
    private val permission: MicrophonePermission,
    private val recorderFactory: PcmRecorderFactory,
    private val config: MicrophoneCaptureConfig = MicrophoneCaptureConfig(),
    private val routeMonitor: AudioRouteMonitor? = null,
    private val focusController: AudioFocusController? = null,
    private val sourceLabel: String = DEFAULT_SOURCE_LABEL,
    private val diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
    private val clock: MonotonicClock = SystemMonotonicClock,
    private val traceId: TraceId? = null,
    private val turnId: TurnId? = null,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : AudioInput {
    override val format: AudioFormat = config.format

    private val reporter = AudioCaptureDiagnostics(diagnostics, clock, traceId, turnId)

    @Volatile
    private var closed = false

    @Volatile
    private var activeEngine: PcmRecorderEngine? = null

    override fun frames(): Flow<AudioFrame> =
        channelFlow { runCaptureSession() }
            .buffer(config.bufferFrameCapacity)
            .flowOn(dispatcher)

    override suspend fun close() {
        closed = true
        val engine = activeEngine ?: return
        withContext(dispatcher) { runCatching { engine.stop() } }
    }

    private suspend fun ProducerScope<AudioFrame>.runCaptureSession() {
        if (closed) {
            throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_DEVICE_UNAVAILABLE, "capture input is closed"))
        }
        if (!permission.isGranted()) {
            AppLog.w { "capture: RECORD_AUDIO not granted; refusing to start" }
            reporter.failed(ErrorCode.AUDIO_PERMISSION_DENIED, sourceLabel)
            throw VoiceAgentException(VoiceAgentError(ErrorCode.AUDIO_PERMISSION_DENIED, "RECORD_AUDIO is not granted"))
        }

        val engine =
            try {
                recorderFactory.create()
            } catch (e: VoiceAgentException) {
                AppLog.e(e) { "capture: recorder create failed ${e.error.code}" }
                reporter.failed(e.error.code, sourceLabel)
                throw e
            }
        activeEngine = engine

        val levels = CaptureLevelAccumulator()
        var frameCount = 0L
        var droppedFrames = 0L
        var routeJob: Job? = null
        runCatching { focusController?.acquire() }

        try {
            engine.start()
            reporter.started(engine.format, sourceLabel, currentRoute())
            AppLog.i {
                "capture: started source=$sourceLabel format=${engine.format} route=${currentRoute()?.label ?: "unknown"}"
            }
            routeJob = monitorRoutes()

            val buffer = ShortArray(config.frameSizeSamples)
            while (currentCoroutineContext().isActive && !closed) {
                val read = engine.read(buffer, 0, buffer.size)
                if (read < 0) {
                    if (closed) break
                    throw readError(read)
                }
                if (read == 0) {
                    if (closed) break
                    yield()
                    continue
                }
                if (frameCount > 0 &&
                    frameCount % config.permissionRecheckIntervalFrames == 0L &&
                    !permission.isGranted()
                ) {
                    AppLog.w { "capture: RECORD_AUDIO revoked during capture" }
                    throw VoiceAgentException(
                        VoiceAgentError(ErrorCode.AUDIO_PERMISSION_DENIED, "RECORD_AUDIO was revoked during capture"),
                    )
                }

                val samples = buffer.copyOf(read)
                levels.add(samples)
                val frame = AudioFrame(engine.format, samples, clock.nanoTime())
                frameCount++
                if (frameCount % config.diagnosticIntervalFrames == 0L) {
                    reporter.progress(frameCount, droppedFrames, levels)
                }
                if (trySend(frame).isFailure) droppedFrames++
                yield()
            }
            reporter.stopped(frameCount, droppedFrames, levels)
            AppLog.i {
                "capture: stopped frames=$frameCount drops=$droppedFrames " +
                    "peak=${levels.peakLevel} clipped=${levels.clippedSamples}"
            }
        } catch (cancellation: CancellationException) {
            reporter.cancelled(frameCount, droppedFrames, levels)
            AppLog.d { "capture: cancelled frames=$frameCount drops=$droppedFrames" }
            throw cancellation
        } catch (failure: VoiceAgentException) {
            reporter.failed(failure.error.code, sourceLabel)
            AppLog.e(failure) { "capture: failed ${failure.error.code} after frames=$frameCount" }
            throw failure
        } finally {
            routeJob?.cancel()
            runCatching { engine.stop() }
            runCatching { engine.release() }
            activeEngine = null
            runCatching { focusController?.abandon() }
        }
    }

    private fun ProducerScope<AudioFrame>.monitorRoutes(): Job? {
        val monitor = routeMonitor ?: return null
        return launch {
            monitor
                .routes()
                .catch { cause -> if (cause is CancellationException) throw cause }
                .collect { route ->
                    AppLog.d { "capture: route changed to ${route.label}" }
                    reporter.routeChanged(route)
                }
        }
    }

    private fun currentRoute(): AudioRoute? = routeMonitor?.let { monitor -> runCatching { monitor.current() }.getOrNull() }

    private fun readError(code: Int): VoiceAgentException {
        AppLog.e { "capture: AudioRecord.read returned $code" }
        val errorCode =
            if (code == AudioRecord.ERROR_DEAD_OBJECT) {
                ErrorCode.AUDIO_DEVICE_UNAVAILABLE
            } else {
                ErrorCode.AUDIO_CAPTURE_FAILED
            }
        return VoiceAgentException(VoiceAgentError(errorCode, "AudioRecord.read returned $code"))
    }

    companion object {
        const val DEFAULT_SOURCE_LABEL: String = "UNKNOWN"
    }
}
