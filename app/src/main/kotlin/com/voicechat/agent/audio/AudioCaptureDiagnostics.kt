package com.voicechat.agent.audio

import com.voicechat.agent.contracts.DiagnosticAttribute
import com.voicechat.agent.contracts.DiagnosticEvent
import com.voicechat.agent.contracts.DiagnosticOutcome
import com.voicechat.agent.contracts.DiagnosticStage
import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.domain.AudioFormat
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId

/**
 * Emits privacy-safe capture events through the M04 diagnostics seam.
 *
 * This is intentionally *not* a parallel tracing mechanism: it constructs the
 * same [DiagnosticEvent] values and sends them to the same [DiagnosticsSink] as
 * every other stage. Only identifiers, the negotiated format, the route kind,
 * normalized levels, and counts are recorded; sample values and raw audio are
 * never retained or emitted (`docs/privacy-and-security.md`).
 */
internal class AudioCaptureDiagnostics(
    private val sink: DiagnosticsSink,
    private val clock: MonotonicClock,
    private val traceId: TraceId? = null,
    private val turnId: TurnId? = null,
) {
    private var lastRoute: AudioRoute? = null

    /** Records the start of a capture session with its format, source, and route. */
    fun started(
        format: AudioFormat,
        source: String,
        route: AudioRoute?,
    ) {
        lastRoute = route
        emit(
            outcome = DiagnosticOutcome.STARTED,
            attributes =
                buildMap {
                    put(DiagnosticAttribute.AUDIO_SOURCE, source)
                    put(DiagnosticAttribute.AUDIO_FORMAT, describe(format))
                    route?.let { put(DiagnosticAttribute.AUDIO_ROUTE, it.label) }
                },
        )
    }

    /** Records a route change; duplicate routes are ignored. */
    fun routeChanged(route: AudioRoute) {
        if (route == lastRoute) return
        lastRoute = route
        emit(
            outcome = DiagnosticOutcome.PROGRESS,
            attributes = mapOf(DiagnosticAttribute.AUDIO_ROUTE to route.label),
        )
    }

    /** Records a periodic level/clip/drop sample without sample content. */
    fun progress(
        frameCount: Long,
        droppedFrames: Long,
        levels: CaptureLevelAccumulator,
    ) = emit(
        outcome = DiagnosticOutcome.PROGRESS,
        attributes = levelAttributes(frameCount, droppedFrames, levels),
    )

    /** Records the normal end of a capture session with its totals. */
    fun stopped(
        frameCount: Long,
        droppedFrames: Long,
        levels: CaptureLevelAccumulator,
    ) = emit(
        outcome = DiagnosticOutcome.COMPLETED,
        attributes = levelAttributes(frameCount, droppedFrames, levels),
    )

    /** Records a cancelled capture session (the collector went away). */
    fun cancelled(
        frameCount: Long,
        droppedFrames: Long,
        levels: CaptureLevelAccumulator,
    ) = emit(
        outcome = DiagnosticOutcome.CANCELLED,
        attributes = levelAttributes(frameCount, droppedFrames, levels),
    )

    /** Records a typed capture failure. Only the stable error code is kept. */
    fun failed(
        errorCode: ErrorCode,
        source: String,
    ) = emit(
        outcome = DiagnosticOutcome.FAILED,
        attributes =
            mapOf(
                DiagnosticAttribute.AUDIO_SOURCE to source,
                DiagnosticAttribute.ERROR_CODE to errorCode.name,
            ),
    )

    private fun levelAttributes(
        frameCount: Long,
        droppedFrames: Long,
        levels: CaptureLevelAccumulator,
    ): Map<DiagnosticAttribute, String> =
        mapOf(
            DiagnosticAttribute.FRAME_COUNT to frameCount.toString(),
            DiagnosticAttribute.AUDIO_DROPPED_FRAMES to droppedFrames.toString(),
            DiagnosticAttribute.AUDIO_PEAK_LEVEL to levels.peakLevel.toString(),
            DiagnosticAttribute.AUDIO_RMS_LEVEL to levels.rmsLevel.toString(),
            DiagnosticAttribute.AUDIO_CLIPPED_SAMPLES to levels.clippedSamples.toString(),
        )

    private fun emit(
        outcome: DiagnosticOutcome,
        attributes: Map<DiagnosticAttribute, String>,
    ) {
        sink.record(
            DiagnosticEvent(
                stage = DiagnosticStage.AUDIO_INPUT,
                outcome = outcome,
                monotonicTimeNanos = clock.nanoTime(),
                turnId = turnId,
                attributes = attributes,
                traceId = traceId,
            ),
        )
    }

    private fun describe(format: AudioFormat): String =
        "${format.sampleRateHz}Hz/${if (format.isMono) "mono" else "${format.channelCount}ch"}/16bit"
}
