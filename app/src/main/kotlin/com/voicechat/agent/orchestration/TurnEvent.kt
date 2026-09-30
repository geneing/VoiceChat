package com.voicechat.agent.orchestration

import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.UtteranceId
import com.voicechat.agent.domain.VoiceAgentError

/**
 * One typed input to the M21 turn state machine.
 *
 * **Every event carries the [turnId] it belongs to.** That is the mechanism that
 * discards late events from cancelled or superseded work: the machine only
 * accepts an event whose [turnId] is its current turn, and counts every rejected
 * event instead of silently ignoring it (see `docs/orchestration.md`).
 *
 * Listening events ([Provisional], [TranscriptRejected]) belong to a turn that
 * is still being transcribed; provider and TTS events belong to the generation
 * turn. [Cancelled][CancelRequested] and [InterruptRequested] are distinct so a
 * user cancel is never confused with a barge-in interruption.
 */
sealed interface TurnEvent {
    /** The turn this event describes. */
    val turnId: TurnId

    /** A new interim (revisable) transcript hypothesis. */
    data class Provisional(
        override val turnId: TurnId,
        val transcript: Transcript,
    ) : TurnEvent

    /** The capture produced no usable transcript; no turn is committed. */
    data class TranscriptRejected(
        override val turnId: TurnId,
        val rejection: TranscriptRejection,
    ) : TurnEvent

    /** Incremental assistant text arrived. */
    data class ProviderDelta(
        override val turnId: TurnId,
        val text: String,
    ) : TurnEvent

    /** The provider finished the response normally. */
    data class ProviderCompleted(
        override val turnId: TurnId,
    ) : TurnEvent

    /** The provider failed; [partialText] is the assistant text received so far. */
    data class ProviderFailed(
        override val turnId: TurnId,
        val error: VoiceAgentError,
        val partialText: String,
    ) : TurnEvent

    /** The stream ended without completing (remote stop or a silent EOF). */
    data class ProviderCancelled(
        override val turnId: TurnId,
        val partialText: String,
    ) : TurnEvent

    /** Text was accepted for synthesis. */
    data class TtsQueued(
        override val turnId: TurnId,
        val utteranceId: UtteranceId,
        val text: String,
    ) : TurnEvent

    /** Audible playback started; the first-audible timing point. */
    data class TtsStarted(
        override val turnId: TurnId,
        val utteranceId: UtteranceId,
        val text: String,
    ) : TurnEvent

    /** An utterance finished playing in full. */
    data class TtsDelivered(
        override val turnId: TurnId,
        val utteranceId: UtteranceId,
        val text: String,
    ) : TurnEvent

    /** Playback stopped early; [deliveredText] was actually audible. */
    data class TtsInterrupted(
        override val turnId: TurnId,
        val utteranceId: UtteranceId,
        val deliveredText: String,
    ) : TurnEvent

    /** Synthesis or playback failed. */
    data class TtsFailed(
        override val turnId: TurnId,
        val utteranceId: UtteranceId,
        val error: VoiceAgentError,
    ) : TurnEvent

    /** The user cancelled the turn. */
    data class CancelRequested(
        override val turnId: TurnId,
    ) : TurnEvent

    /** Detected user speech interrupted assistant output (barge-in). */
    data class InterruptRequested(
        override val turnId: TurnId,
        val onsetAtNanos: Long? = null,
    ) : TurnEvent
}
