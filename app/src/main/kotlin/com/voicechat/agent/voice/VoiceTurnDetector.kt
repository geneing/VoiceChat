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

    /**
     * Reports whether assistant playback is currently audible.
     *
     * Onset raises its bar while it is true, so the assistant's own residual audio
     * through the microphone cannot start a turn, and drops back the moment it ends
     * — a state, not a fixed grace period. The default is a no-op, so a detector
     * that does not model playback (a test script) is unaffected.
     */
    fun setPlaybackActive(active: Boolean) = Unit
}

/**
 * Production [VoiceTurnDetector] backed by the M09 [BoundedTurnEndpointPolicy].
 *
 * The policy already separates the fast onset path (correct for barge-in) from
 * the bounded turn-completion decision, so the coordinator gets both from one
 * event stream and never runs a semantic model on the onset path.
 */
class PolicyVoiceTurnDetector private constructor(
    private val policy: BoundedTurnEndpointPolicy,
    private val playback: PlaybackFlag,
) : VoiceTurnDetector {
    override fun detect(audio: Flow<AudioFrame>): Flow<TurnDetectionEvent> = policy.observe(audio)

    override fun setPlaybackActive(active: Boolean) {
        playback.active = active
    }

    /** Mutable playback state the policy's onset reads on the detection coroutine. */
    private class PlaybackFlag {
        @Volatile
        var active: Boolean = false
    }

    /** Convenience factory for the app boundary. */
    companion object {
        /**
         * Builds the production detector for one capture session.
         *
         * [semanticDetector] is the optional M10 Smart Turn detector: `null`
         * (disabled or unavailable) keeps the bounded VAD-only endpoint, and a
         * present detector is evaluated only at a candidate pause, never on the
         * onset/barge-in path.
         */
        fun forRoute(
            route: com.voicechat.agent.audio.AudioRoute? = null,
            diagnostics: DiagnosticsSink = NoOpDiagnosticsSink,
            clock: MonotonicClock = SystemMonotonicClock,
            semanticDetector: com.voicechat.agent.contracts.TurnCompletionDetector? = null,
        ): PolicyVoiceTurnDetector {
            val playback = PlaybackFlag()
            val policy =
                com.voicechat.agent.audio.CaptureTurnDetection.endpointPolicy(
                    route = route,
                    semanticDetector = semanticDetector,
                    diagnostics = diagnostics,
                    clock = clock,
                    playbackActive = { playback.active },
                )
            return PolicyVoiceTurnDetector(policy, playback)
        }
    }
}
