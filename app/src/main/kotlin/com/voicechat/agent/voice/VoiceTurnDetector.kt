package com.voicechat.agent.voice

import com.voicechat.agent.contracts.DiagnosticsSink
import com.voicechat.agent.contracts.NoOpDiagnosticsSink
import com.voicechat.agent.diagnostics.MonotonicClock
import com.voicechat.agent.diagnostics.SystemMonotonicClock
import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.vad.BoundedTurnEndpointPolicy
import com.voicechat.agent.vad.TurnDetectionEvent
import kotlinx.coroutines.flow.Flow

/**
 * Speech-onset and logical-turn-endpoint detection over one capture session.
 *
 * It is the seam between the coordinator and the M09 implementation, so the
 * coordinator can be driven by the real bounded policy in production and by a
 * deterministic script in tests without changing the loop. The events are the
 * M09 [TurnDetectionEvent]s: activity (onset/pause/resume), holds, and exactly
 * one endpoint per logical turn.
 *
 * **Ownership.** [detect] returns a cold flow tied to one capture stream; the
 * coordinator collects it for the session's lifetime. It must remain usable for
 * many logical turns and must never block on semantic inference for the onset
 * path, so barge-in stays low-latency (see `docs/vad-endpointing.md`).
 */
fun interface VoiceTurnDetector {
    /** Observes [audio] and emits activity, holds, and endpoints. */
    fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent>
}

/**
 * Production [VoiceTurnDetector] backed by the M09 [BoundedTurnEndpointPolicy].
 *
 * The policy already separates the fast onset path (correct for barge-in) from
 * the bounded turn-completion decision, so the coordinator gets both from one
 * event stream and never runs a semantic model on the onset path.
 */
class PolicyVoiceTurnDetector(
    private val policy: BoundedTurnEndpointPolicy,
) : VoiceTurnDetector {
    override fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> = policy.observe(audio)

    /** Convenience factory for the app boundary. */
    companion object {
        fun forRoute(
            route: com.voicechat.agent.audio.AudioRoute? = null,
            diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
            clock: MonotonicClock = SystemMonotonicClock,
        ): PolicyVoiceTurnDetector =
            PolicyVoiceTurnDetector(
                com.voicechat.agent.audio.CaptureTurnDetection.endpointPolicy(
                    route = route,
                    diagnostics = diagnostics,
                    clock = clock,
                ),
            )
    }
}
