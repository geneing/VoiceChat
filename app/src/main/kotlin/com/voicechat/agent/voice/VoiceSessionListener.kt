package com.voicechat.agent.voice

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.Transcript
import com.voicechat.agent.domain.TurnId
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.orchestration.TurnResult

/**
 * Progress sink for one voice session (M24).
 *
 * It is the seam between the pure coordinator and the lifecycle-aware state
 * holder (M06 `ConversationViewModel`), so the dialog can render live provisional
 * STT text and streamed assistant text without the coordinator depending on
 * Compose or a ViewModel. Every callback is called on the coordinator's
 * coroutine; implementations must be cheap and must not suspend or mutate
 * pipeline state. The listener never drives the pipeline.
 *
 * [onConversationChanged] and [onTurnFinished] always carry the last *persisted*
 * conversation, so the dialog shows only what was durably written — an
 * interrupted reply is persisted with just the delivered prefix, never the
 * unheard suffix.
 */
interface VoiceSessionListener {
    /** The session-level state changed. */
    fun onSessionState(state: VoiceSessionState) = Unit

    /** A new logical turn started capture/recognition; [turnId] identifies it. */
    fun onListeningStarted(turnId: TurnId) = Unit

    /** Interim STT text for the active turn, for provisional display. Only the
     * active turn's revisions are delivered; a superseded turn's late interim is
     * dropped (the "no stale events" rule). */
    fun onProvisionalTranscript(
        turnId: TurnId,
        text: String,
    ) = Unit

    /** A finalized (possibly corrected upstream) transcript was committed as a voice turn. */
    fun onUtteranceCommitted(
        turnId: TurnId,
        transcript: Transcript,
    ) = Unit

    /** Newly streamed assistant text (running total) for live rendering. */
    fun onAssistantText(
        turnId: TurnId,
        text: String,
    ) = Unit

    /** A turn's persisted conversation changed (user turn committed / assistant turn stored). */
    fun onConversationChanged(conversation: Conversation) = Unit

    /** A turn reached its terminal state; [result] carries the persisted truth. */
    fun onTurnFinished(result: TurnResult) = Unit

    /** A barge-in was detected and playback stop was issued. */
    fun onBargeIn(timing: BargeInTiming) = Unit

    /** A barge-in's new utterance resolved as committed speech or as noise/no-speech. */
    fun onInterruptionRecovered(recovery: VoiceInterruptionRecovery) = Unit

    /** A logical turn ended with no speech or an unusable transcript; no turn committed. */
    fun onNoSpeech() = Unit

    /** A typed error (STT, capture, or provider) surfaced; not a silent success. */
    fun onError(error: VoiceAgentError) = Unit

    /**
     * The selected on-device TTS could not be used, so this session is **text-only**.
     *
     * The voice loop still runs and streams assistant text, but nothing is spoken.
     * A typed [error] is surfaced instead of silently dropping the voice response
     * stage, so the dialog can explain the degradation
     * (`docs/risks-and-decisions.md` R-0180). [error] distinguishes a device with
     * no embedded voice ([com.voicechat.agent.domain.ErrorCode.TTS_NO_ON_DEVICE_VOICE])
     * from an initialization failure.
     */
    fun onTextToSpeechUnavailable(error: VoiceAgentError) = Unit

    /** Records nothing. */
    object NONE : VoiceSessionListener
}
