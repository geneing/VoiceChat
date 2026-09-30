package com.voicechat.agent.audio

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.contracts.TurnCompletionDetector
import com.voicechat.agent.contracts.VoiceActivityDetector
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.TraceId
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.EnergyVoiceActivityDetector
import com.voicechat.agent.vad.VadConfig

/**
 * App-boundary factory that builds M09 turn detection for the active capture
 * route.
 *
 * Route kind is platform information: it comes from
 * [AudioRouteMonitor]/[AudioRoute] at the capture boundary, so the mapping from
 * a route to validated [VadConfig] thresholds lives here rather than in the
 * platform-free `vad` package. Callers that have no route (replay, tests) can
 * pass `null` and get the built-in-mic defaults.
 *
 * Nothing here requests the microphone or starts capture; it only assembles the
 * pure detectors so a future orchestrator (M21) can wire them to the M07
 * [AudioInput].
 */
object CaptureTurnDetection {
    /** Returns route-aware, validated thresholds for an observed route. */
    fun configFor(route: AudioRoute?): VadConfig = VadConfig.forRoute(route?.type ?: AudioRouteType.UNKNOWN)

    /** Builds the fast onset/activity detector for the observed route. */
    fun voiceActivityDetector(
        route: AudioRoute? = null,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        traceId: TraceId? = null,
        turnId: TurnId? = null,
    ): VoiceActivityDetector =
        EnergyVoiceActivityDetector(
            config = configFor(route),
            sink = diagnostics,
            clock = clock,
            route = route?.type,
            traceId = traceId,
            turnId = turnId,
        )

    /**
     * Builds the bounded endpoint policy for the observed route.
     *
     * [semanticDetector] is the M10 extension point; leave it `null` for the
     * VAD-only bounded endpoint that must always work without Smart Turn.
     */
    fun endpointPolicy(
        route: AudioRoute? = null,
        semanticDetector: TurnCompletionDetector? = null,
        diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
        clock: MonotonicClock = SystemMonotonicClock,
        traceId: TraceId? = null,
        turnId: TurnId? = null,
    ): BoundedTurnEndpointPolicy =
        BoundedTurnEndpointPolicy(
            config = configFor(route),
            semanticDetector = semanticDetector,
            sink = diagnostics,
            clock = clock,
            route = route?.type,
            traceId = traceId,
            turnId = turnId,
        )
}
