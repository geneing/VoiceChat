package com.voicechat.agent.voice

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError

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

    /**
     * The recognizer's own end-of-speech detector fired for a turn.
     *
     * Corroborating only: the bounded VAD/Smart Turn endpoint remains the
     * authority for ending the turn, so this never commits a transcript by
     * itself. It exists so the timing of the engine's signal can be observed.
     */
    fun onSttEndOfSpeech(turnId: TurnId) = Unit

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
