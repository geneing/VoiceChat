package com.voicechat.agent.stt.parakeet

import kotlinx.coroutines.flow.StateFlow

/**
 * The Parakeet `<EOU>` signal, exposed so the turn policy can corroborate a VAD
 * candidate pause without a second decode.
 *
 * This is the frozen interface from `docs/parakeet-eou-streaming.md`. The decoder
 * runs exactly once per captured chunk inside [ParakeetEouSpeechToText];
 * `EouTurnCompletionDetector` reads this signal rather than re-decoding the audio
 * window, so EOU adds no second inference pass on the endpoint path.
 *
 * **Semantics.** A "confirmed EOU" is a decode step whose `eouDetected` was true
 * *and* that was actually delivered. It is debounced by the adapter (see
 * [ParakeetEouSpeechToTextConfig.eouDebounceMillis]) because the head fires
 * mid-word on disfluencies. The offset is measured on the capture/audio timeline
 * in milliseconds from the start of the current recognition session, not wall
 * time.
 *
 * **Ownership.** One signal instance is owned by one
 * [ParakeetEouSpeechToText]; it is read by the turn policy and cleared at the
 * start of each new capture session.
 */
interface EndOfUtteranceSignal {
    /**
     * The observed EOU events, most recent last.
     *
     * The detector subscribes to this so it can evaluate a candidate pause the
     * moment it is reported, without polling. Emitted on the decode worker, but a
     * [StateFlow] makes the latest value observable from any thread.
     */
    val eouEvents: StateFlow<EouObservation>

    /** Monotonic millis offset of the last confirmed EOU in the current capture, or null. */
    fun lastEouOffsetMillis(): Long?

    /**
     * Consumes the pending confirmed EOU, returning true if one was seen since the
     * last consumption. The detector never needs this (it compares offsets against
     * the pause), but it is part of the frozen interface for a policy that wants
     * one-shot confirmation.
     */
    fun consumeEou(): Boolean
}

/**
 * One confirmed end-of-utterance observation.
 *
 * @param offsetMillis offset from the start of the current capture session, in
 *   milliseconds, on the audio timeline.
 * @param confidence mean token confidence of the decode step that produced it,
 *   or `null` when the decoder did not report one.
 * @param sequence a monotonic counter so an observer can tell a new confirmation
 *   from a repeated read of the latest value.
 */
data class EouObservation(
    val offsetMillis: Long,
    val confidence: Float?,
    val sequence: Long,
)
