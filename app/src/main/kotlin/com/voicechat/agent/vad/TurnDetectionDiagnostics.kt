package com.voicechat.agent.vad

import com.voicechat.agent.audio.AudioRouteType
import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.SpeechActivity
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.log.AppLog

/**
 * Emits privacy-safe turn-detection events through the M04 diagnostics seam.
 *
 * This is not a parallel tracing mechanism: it builds the same typed
 * [DiagnosticEvent] values every other stage does, on
 * [DiagnosticStage.TURN_DETECTION]. Only the event name, a stable reason code,
 * the offset from capture start, the active route kind, and a normalized RMS
 * level are recorded — never a sample value or any other audio content. A short
 * `AppLog` debug line mirrors each transition so a developer can follow onset,
 * pause, resume, and endpoint decisions in logcat on a debug build.
 */
internal class TurnDetectionDiagnostics(
    private val sink: DiagnosticsSink,
    private val clock: MonotonicClock,
    private val route: AudioRouteType? = null,
    private val traceId: TraceId? = null,
    private val turnId: TurnId? = null,
) {
    /** Records one speech-activity transition and its threshold reason. */
    fun speechActivity(
        activity: SpeechActivity,
        reason: VadReason,
        rmsLevel: Float,
        atOffsetMillis: Long,
    ) {
        emit(
            outcome = DiagnosticOutcome.PROGRESS,
            event = activity.name,
            reason = reason.name,
            atOffsetMillis = atOffsetMillis,
            rmsLevel = rmsLevel,
        )
        AppLog.d { "vad: ${activity.name} reason=${reason.name} at=${atOffsetMillis}ms rms=$rmsLevel route=${route?.name}" }
    }

    /** Records that a candidate pause did not finalize the turn. */
    fun held(
        reason: EndpointHoldReason,
        atOffsetMillis: Long,
    ) {
        emit(
            outcome = DiagnosticOutcome.PROGRESS,
            event = EVENT_HELD,
            reason = reason.name,
            atOffsetMillis = atOffsetMillis,
        )
        AppLog.d { "vad: turn held reason=${reason.name} at=${atOffsetMillis}ms" }
    }

    /** Records the single final endpoint of a logical user turn. */
    fun endpointed(
        reason: EndpointReason,
        atOffsetMillis: Long,
    ) {
        emit(
            outcome = DiagnosticOutcome.COMPLETED,
            event = EVENT_ENDPOINTED,
            reason = reason.name,
            atOffsetMillis = atOffsetMillis,
        )
        AppLog.i { "vad: turn endpointed reason=${reason.name} at=${atOffsetMillis}ms" }
    }

    /** Records a typed detector failure; only the stable code is kept. */
    fun failed(errorCode: ErrorCode) {
        emit(
            outcome = DiagnosticOutcome.FAILED,
            event = EVENT_FAILED,
            reason = errorCode.name,
            atOffsetMillis = null,
            errorCode = errorCode,
        )
        AppLog.e { "vad: detector failed code=$errorCode" }
    }

    private fun emit(
        outcome: DiagnosticOutcome,
        event: String,
        reason: String,
        atOffsetMillis: Long?,
        rmsLevel: Float? = null,
        errorCode: ErrorCode? = null,
    ) {
        sink.record(
            DiagnosticEvent(
                stage = DiagnosticStage.TURN_DETECTION,
                outcome = outcome,
                monotonicTimeNanos = clock.nanoTime(),
                turnId = turnId,
                attributes =
                    buildMap {
                        put(DiagnosticAttribute.VAD_EVENT, event)
                        put(DiagnosticAttribute.VAD_REASON, reason)
                        atOffsetMillis?.let { put(DiagnosticAttribute.VAD_OFFSET_MILLIS, it.toString()) }
                        rmsLevel?.let { put(DiagnosticAttribute.AUDIO_RMS_LEVEL, it.toString()) }
                        route?.let { put(DiagnosticAttribute.AUDIO_ROUTE, it.name) }
                        errorCode?.let { put(DiagnosticAttribute.ERROR_CODE, it.name) }
                    },
                traceId = traceId,
            ),
        )
    }

    private companion object {
        const val EVENT_HELD = "HELD"
        const val EVENT_ENDPOINTED = "ENDPOINTED"
        const val EVENT_FAILED = "FAILED"
    }
}
