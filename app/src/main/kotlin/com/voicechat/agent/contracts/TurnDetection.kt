package com.voicechat.agent.contracts

import com.voicechat.agent.domain.AudioFrame
import com.voicechat.agent.domain.VoiceAgentError
import kotlinx.coroutines.flow.Flow

/** Fast acoustic speech activity, separate from semantic turn completion. */
enum class SpeechActivity {
    /** Speech onset was detected; used for barge-in, not for committing a turn. */
    SPEECH_STARTED,

    /** Speech energy paused; a candidate endpoint that may still be resumed. */
    CANDIDATE_PAUSE,

    /** Speech resumed after a candidate pause; the same turn stays open. */
    SPEECH_RESUMED,
}

/**
 * A speech-activity observation produced by [VoiceActivityDetector].
 */
sealed interface VadEvent {
    /** An activity transition at a monotonic offset from the start of capture. */
    data class Activity(
        val activity: SpeechActivity,
        val atOffsetMillis: Long,
    ) : VadEvent

    /** Detection failed; no further events are expected. */
    data class Failed(
        val error: VoiceAgentError,
    ) : VadEvent
}

/**
 * Continuous, low-latency voice activity detection.
 *
 * **Ownership and lifecycle.** [observe] returns a cold flow for one capture
 * session; it is consumed by orchestration and completes when [audio] completes
 * or fails. Cancelling collection releases detector state. The detector only
 * reports activity; it does not decide that a thought is complete, and the
 * bounded maximum-silence endpoint remains an orchestration policy so a trailing
 * turn always terminates.
 */
interface VoiceActivityDetector {
    /** Observes speech onset and candidate pauses in [audio]. */
    fun observe(audio: Flow<AudioFrame>): Flow<VadEvent>
}

/** Semantic endpoint decision for a VAD-confirmed candidate pause. */
enum class TurnCompletion {
    /** The speaker's thought is complete. */
    COMPLETE,

    /** The thought is incomplete; keep the same user turn open. */
    INCOMPLETE,

    /** The detector is disabled or its model is unavailable; use the VAD-only policy. */
    UNAVAILABLE,
}

/**
 * Optional semantic end-of-turn detector (for example Smart Turn v3.2).
 *
 * **Ownership and lifecycle.** [evaluate] is called once per VAD-confirmed
 * candidate pause, never per audio frame, and runs off the main thread. A false
 * [TurnCompletion.INCOMPLETE] keeps the existing turn open; resumed speech joins
 * it. [close] releases any loaded model and is idempotent. Implementations must
 * report [TurnCompletion.UNAVAILABLE] rather than guessing when their model is
 * missing or corrupt.
 */
interface TurnCompletionDetector {
    /** Evaluates the recent audio [window] for one candidate pause. */
    suspend fun evaluate(window: AudioFrame): TurnCompletion

    /** Releases the loaded model. Idempotent. */
    suspend fun close()
}
