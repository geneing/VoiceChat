package com.voicechat.agent.turn

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TurnCompletion
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The M10 `TurnCompletionDetector` backed by the pinned Smart Turn v3.2 ONNX
 * graph, evaluated once per VAD-confirmed candidate pause.
 *
 * The detector owns a [SmartTurnInferenceEngine] and maps one probability to the
 * M02 [TurnCompletion] verdict:
 *
 * - `probability > config.completionThreshold` → [TurnCompletion.COMPLETE];
 * - otherwise → [TurnCompletion.INCOMPLETE] (the turn stays open and resumed
 *   speech joins it);
 * - a wrong audio format, an inference failure, or a closed detector →
 *   [TurnCompletion.UNAVAILABLE], never a guessed `COMPLETE`, so the bounded
 *   VAD-only policy still terminates the turn.
 *
 * [evaluate] never blocks the caller's thread: window preparation is cheap
 * (allocation/copy) and native inference runs on
 * [Dispatchers.Default]. [close] releases the engine and is idempotent.
 */
class SmartTurnCompletionDetector(
    private val engine: SmartTurnInferenceEngine,
    private val config: SmartTurnConfig = SmartTurnConfig.default(),
    sink: DiagnosticsSink = NoOpDiagnosticsSink,
    clock: MonotonicClock = SystemMonotonicClock,
    traceId: TraceId? = null,
    turnId: TurnId? = null,
) : TurnCompletionDetector {
    private val diagnostics = SmartTurnDiagnostics(sink, clock, traceId, turnId)
    private val clock = clock
    private val closed = AtomicBoolean(false)

    override suspend fun evaluate(window: AudioFrame): TurnCompletion {
        if (closed.get()) return TurnCompletion.UNAVAILABLE
        if (!window.format.isMono || window.format.sampleRateHz != config.sampleRateHz) {
            diagnostics.unavailable(
                ErrorCode.TURN_DETECTION_FAILED,
                "Smart Turn requires $SUPPORTED_FORMAT_DESCRIPTION",
                integrity = INTEGRITY_FORMAT_MISMATCH,
            )
            return TurnCompletion.UNAVAILABLE
        }

        val input = SmartTurnWindow.prepare(window.samples, config.windowSamples)
        val startedAt = clock.nanoTime()
        val probability =
            try {
                withContext(Dispatchers.Default) { engine.probability(input) }
            } catch (cancellation: CancellationException) {
                throw cancellation
            } catch (failure: Throwable) {
                diagnostics.unavailable(ErrorCode.TURN_DETECTION_FAILED, "Smart Turn inference failed")
                return TurnCompletion.UNAVAILABLE
            }
        if (!probability.isFinite()) {
            diagnostics.unavailable(ErrorCode.TURN_DETECTION_FAILED, "Smart Turn produced a non-finite probability")
            return TurnCompletion.UNAVAILABLE
        }

        val verdict = if (probability > config.completionThreshold) TurnCompletion.COMPLETE else TurnCompletion.INCOMPLETE
        diagnostics.inference(probability, verdict.name, clock.nanoTime() - startedAt)
        return verdict
    }

    override suspend fun close() {
        if (!closed.compareAndSet(false, true)) return
        try {
            withContext(Dispatchers.Default) { engine.close() }
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Releasing a broken session is best-effort; the detector is already closed.
        }
    }

    private companion object {
        const val SUPPORTED_FORMAT_DESCRIPTION = "16 kHz mono PCM"
        const val INTEGRITY_FORMAT_MISMATCH = "FORMAT_MISMATCH"
    }
}
