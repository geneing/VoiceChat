package com.voicechat.agent.orchestration

import com.voicechat.agent.domain.VoiceAgentError

/**
 * Why a recognized utterance produced no user turn.
 *
 * This is deliberately distinct from a provider failure: the pipeline never
 * reached the LLM. [NO_SPEECH] means the VAD/endpoint decided the capture held
 * no speech; [EMPTY] means a final transcript was blank after trimming;
 * [LOW_CONFIDENCE] means a recognizer supplied a confidence below the usable
 * threshold. None of these is a silent success (see
 * `docs/architecture.md`, "Streaming and lifecycle").
 */
enum class TranscriptRejection {
    NO_SPEECH,
    EMPTY,
    LOW_CONFIDENCE,
}

/**
 * Terminal classification of one turn.
 *
 * The set makes the states M21 must distinguish explicit: normal completion, no
 * speech, an unusable transcript, a provider error, a TTS failure, a user
 * cancellation, and a barge-in interruption of a partially delivered reply.
 */
sealed interface TurnOutcome {
    /** Generation completed and (when speech was used) all queued text was delivered. */
    data object Completed : TurnOutcome

    /** No speech was found in the capture; no turn was committed. */
    data class NoSpeech(
        val rejection: TranscriptRejection = TranscriptRejection.NO_SPEECH,
    ) : TurnOutcome

    /** A final transcript was empty or below the usable confidence threshold. */
    data class EmptyTranscript(
        val rejection: TranscriptRejection,
    ) : TurnOutcome

    /** The provider failed; [error] is the stable typed code. */
    data class ProviderError(
        val error: VoiceAgentError,
    ) : TurnOutcome

    /** Synthesis or playback failed; the turn stops short of a normal completion. */
    data class TtsFailure(
        val error: VoiceAgentError,
    ) : TurnOutcome

    /** The user cancelled the in-flight turn. */
    data object Cancelled : TurnOutcome

    /** Assistant output was cut short by detected user speech (barge-in). */
    data object Interrupted : TurnOutcome

    /** A durable write failed so the turn could not be recorded truthfully. */
    data class PersistenceFailure(
        val error: VoiceAgentError,
    ) : TurnOutcome
}
