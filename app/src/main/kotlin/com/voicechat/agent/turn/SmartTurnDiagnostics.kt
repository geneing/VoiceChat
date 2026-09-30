package com.voicechat.agent.turn

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.log.AppLog
import java.io.File

/**
 * Emits privacy-safe Smart Turn diagnostics through the existing M04
 * [DiagnosticsSink] on [DiagnosticStage.TURN_DETECTION].
 *
 * Only the model identity, the byte size, the integrity/verdict reason code, the
 * probability (a number, not audio), and the stage duration are recorded. **Raw
 * audio, transcripts, and the audio window are never logged**; a short
 * release-off `AppLog` line mirrors each event so a developer can follow load,
 * inference, and unavailable reasons in logcat on a debug build.
 */
internal class SmartTurnDiagnostics(
    private val sink: DiagnosticsSink,
    private val clock: MonotonicClock,
    private val traceId: TraceId? = null,
    private val turnId: TurnId? = null,
) {
    /** Records a successful model load and its duration. */
    fun loaded(
        file: File,
        sizeBytes: Long,
        durationNanos: Long,
    ) {
        emit(
            outcome = DiagnosticOutcome.COMPLETED,
            event = EVENT_LOADED,
            reason = INTEGRITY_VERIFIED,
            durationNanos = durationNanos,
            integrity = INTEGRITY_VERIFIED,
            attributes = mapOf(DiagnosticAttribute.BYTE_COUNT to sizeBytes.toString()),
        )
        AppLog.d { "smartturn: loaded size=$sizeBytes bytes durationMs=${durationNanos / 1_000_000} path=${file.name}" }
    }

    /** Records one candidate-pause inference, its probability, and its verdict. */
    fun inference(
        probability: Float,
        verdict: String,
        durationNanos: Long,
    ) {
        emit(
            outcome = DiagnosticOutcome.COMPLETED,
            event = EVENT_INFERENCE,
            reason = verdict,
            durationNanos = durationNanos,
            attributes =
                mapOf(
                    DiagnosticAttribute.MODEL_ID to MODEL_ID,
                    DiagnosticAttribute.TURN_COMPLETION_PROBABILITY to probability.toString(),
                ),
        )
        AppLog.d {
            "smartturn: inference p=$probability verdict=$verdict durationMs=${durationNanos / 1_000_000}"
        }
    }

    /**
     * Records that the detector could not run, with a typed reason.
     *
     * [detail] is a short, safe explanation; it never contains audio or content.
     */
    fun unavailable(
        errorCode: ErrorCode,
        detail: String,
        integrity: String = INTEGRITY_LOAD_FAILED,
    ) {
        emit(
            outcome = DiagnosticOutcome.FAILED,
            event = EVENT_UNAVAILABLE,
            reason = errorCode.name,
            durationNanos = null,
            integrity = integrity,
            attributes =
                mapOf(
                    DiagnosticAttribute.MODEL_ID to MODEL_ID,
                    DiagnosticAttribute.ERROR_CODE to errorCode.name,
                ),
        )
        AppLog.w { "smartturn: unavailable code=$errorCode reason=$detail" }
    }

    private fun emit(
        outcome: DiagnosticOutcome,
        event: String,
        reason: String,
        durationNanos: Long?,
        integrity: String? = null,
        attributes: Map<DiagnosticAttribute, String>,
    ) {
        sink.record(
            DiagnosticEvent(
                stage = DiagnosticStage.TURN_DETECTION,
                outcome = outcome,
                monotonicTimeNanos = clock.nanoTime(),
                turnId = turnId,
                durationNanos = durationNanos,
                attributes =
                    buildMap {
                        put(DiagnosticAttribute.VAD_EVENT, event)
                        put(DiagnosticAttribute.VAD_REASON, reason)
                        put(DiagnosticAttribute.RUNTIME, RUNTIME)
                        integrity?.let { put(DiagnosticAttribute.MODEL_INTEGRITY, it) }
                        putAll(attributes)
                    },
                traceId = traceId,
            ),
        )
    }

    internal companion object {
        const val EVENT_LOADED = "SMART_TURN_LOADED"
        const val EVENT_INFERENCE = "SMART_TURN_INFERENCE"
        const val EVENT_UNAVAILABLE = "SMART_TURN_UNAVAILABLE"

        const val INTEGRITY_VERIFIED = "VERIFIED"
        const val INTEGRITY_LOAD_FAILED = "LOAD_FAILED"

        const val MODEL_ID = "smart-turn-v3.2-int8"
        const val RUNTIME = "ONNX_RUNTIME"
    }
}
