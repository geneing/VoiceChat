package com.voicechat.agent.voice

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError

/** Audio-timeline observations for Parakeet EOU and the independent VAD endpoint. */
data class ParakeetEndpointTiming(
    /** Confirmed Parakeet EOU offset from this recognition turn's input start. */
    val eouOffsetMillis: Long?,
    /** VAD endpoint offset from the same turn start. */
    val vadEndpointOffsetMillis: Long?,
) {
    /** Signed EOU-minus-VAD delay; negative means Parakeet reported EOU first. */
    val eouMinusVadMillis: Long?
        get() =
            if (eouOffsetMillis != null && vadEndpointOffsetMillis != null) {
                eouOffsetMillis - vadEndpointOffsetMillis
            } else {
                null
            }
}

/** Progress sink for one STT-only voice session. */
interface VoiceSessionListener {
    /** The session-level state changed. */
    fun onSessionState(state: VoiceSessionState) = Unit

    /** A new logical turn started capture/recognition. */
    fun onListeningStarted(turnId: TurnId) = Unit

    /** Interim text for the active recognition turn. */
    fun onProvisionalTranscript(
        turnId: TurnId,
        text: String,
    ) = Unit

    /** A Parakeet EOU observation or VAD endpoint was updated for a turn. */
    fun onParakeetEndpointTiming(
        turnId: TurnId,
        timing: ParakeetEndpointTiming,
    ) = Unit

    /** A finalized transcript has been saved as a user turn. */
    fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) = Unit

    /** The persisted transcript history changed. */
    fun onConversationChanged(conversation: Conversation) = Unit

    /** A logical turn ended without usable speech. */
    fun onNoSpeech() = Unit

    /** A typed capture, recognition, or persistence error surfaced. */
    fun onError(error: VoiceAgentError) = Unit

    /** Records nothing. */
    object NONE : VoiceSessionListener
}
