package com.voicechat.agent.voice

import com.voicechat.agent.contracts.LanguageModel
import com.voicechat.agent.contracts.TextToSpeech
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.orchestration.TurnObserver
import com.voicechat.agent.orchestration.TurnOrchestrator
import com.voicechat.agent.orchestration.TurnOutcome
import com.voicechat.agent.orchestration.TurnRequest
import com.voicechat.agent.orchestration.TurnResult
import kotlinx.coroutines.CancellationException

/**
 * One assistant reply for one finalized user turn: the conversation **before**
 * the user turn is appended, and the turn itself.
 *
 * The assistant persists both turns (through M21 orchestration), so the loop
 * never writes the user turn itself when an assistant is wired. That is what
 * keeps the saved order truthful after a barge-in: the interrupted assistant
 * turn is persisted with only what was actually audible, and the interrupting
 * user turn follows it.
 */
data class VoiceAssistantRequest(
    val conversation: Conversation,
    val userTurn: UserTurn,
)

/** What one assistant reply produced. */
data class VoiceAssistantReply(
    /** The conversation including the persisted user turn and assistant turn. */
    val conversation: Conversation,
    /** The assistant text the provider generated. */
    val generatedText: String,
    /** The text TTS confirmed was audible; a prefix when interrupted. */
    val deliveredText: String,
    /** The terminal classification, or `null` when the orchestrator had none. */
    val outcome: TurnOutcome?,
    /** The typed failure, when the turn failed rather than completing. */
    val failure: VoiceAgentError?,
) {
    /** True when less was heard than generated (a barge-in or a stop). */
    val interrupted: Boolean get() = deliveredText.length < generatedText.length

    /** True when the reply ended normally. */
    val completed: Boolean get() = outcome is TurnOutcome.Completed
}

/**
 * The assistant side of the voice loop: generate one reply and speak it, or stop
 * immediately when the user talks over it.
 *
 * It is a seam, exactly like [VoiceTurnDetector] and `SpeechToText`, so the
 * coordinator can be driven by the real M21 orchestration in production and by a
 * deterministic fake in tests. The coordinator relies on only two things:
 * [reply] suspends until the reply is delivered or interrupted, and [interrupt]
 * is safe to call from the detection coroutine at speech onset, before any
 * transcript or Smart Turn decision exists.
 */
interface VoiceAssistant {
    /** Runs one reply, reporting progress to [listener]; returns when it ends. */
    suspend fun reply(
        request: VoiceAssistantRequest,
        listener: VoiceSessionListener,
    ): VoiceAssistantReply

    /**
     * Stops audible output now and cancels the in-flight request.
     *
     * Called at VAD speech onset, so it must not block and must not wait for a
     * transcript, a Smart Turn result, or the provider.
     */
    fun interrupt(onsetAtNanos: Long)

    /** Releases what this assistant owns (the TTS engine, not the shared model). */
    suspend fun close() = Unit
}

/**
 * Production [VoiceAssistant]: the M21 [TurnOrchestrator] behind the voice loop.
 *
 * The orchestrator already owns the streaming, sentence chunking, delivery
 * accounting, and interrupt semantics (`docs/orchestration.md`), so the voice
 * loop reuses it instead of re-implementing them. This class only maps the
 * orchestrator's [TurnObserver] callbacks onto the loop's [VoiceSessionListener]
 * and folds the terminal [TurnResult] into a [VoiceAssistantReply].
 *
 * **Ownership.** It closes the [textToSpeech] it was given (the session created
 * it) but never the [languageModel]: an adapter shares the app-scoped transport,
 * so closing it would break every later turn.
 */
class OrchestratingVoiceAssistant(
    private val orchestrator: TurnOrchestrator,
    private val selection: ProviderModelSelection,
    private val reasoning: ReasoningLevel?,
    private val languageModel: LanguageModel,
    private val textToSpeech: TextToSpeech? = null,
) : VoiceAssistant {
    override suspend fun reply(
        request: VoiceAssistantRequest,
        listener: VoiceSessionListener,
    ): VoiceAssistantReply {
        var finished: TurnResult? = null
        val observer =
            object : TurnObserver {
                override fun onUserTurnCommitted(conversation: Conversation) {
                    // The orchestrator persisted the user turn; the dialog shows the
                    // committed transcript and the updated history.
                    listener.onUtteranceCommitted(request.userTurn.id, request.userTurn.transcript)
                    listener.onConversationChanged(conversation)
                }

                override fun onLiveAssistantText(text: String) {
                    listener.onLiveAssistantText(text)
                }

                override fun onTurnFinished(result: TurnResult) {
                    finished = result
                }
            }
        // A previous turn may have left the interrupt latch set; a fresh reply
        // starts un-interrupted.
        orchestrator.resetInterrupt()
        val result =
            orchestrator.run(
                TurnRequest(
                    conversation = request.conversation,
                    userTurn = request.userTurn,
                    selection = selection,
                    reasoning = reasoning,
                ),
                observer,
                languageModel = languageModel,
            )
        val terminal = finished ?: result
        return VoiceAssistantReply(
            conversation = terminal.conversation,
            generatedText = terminal.record?.generatedText.orEmpty(),
            deliveredText = terminal.record?.deliveredText.orEmpty(),
            outcome = terminal.record?.outcome,
            failure = terminal.record?.failure ?: terminal.persistenceFailure,
        )
    }

    override fun interrupt(onsetAtNanos: Long) {
        orchestrator.interrupt(onsetAtNanos)
    }

    override suspend fun close() {
        try {
            textToSpeech?.close()
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (ignored: Throwable) {
            // Release best-effort; the session has already ended.
        }
    }

    companion object {
        /**
         * The turn-orchestration seam for one voice session, built per session so
         * the TTS engine follows the current settings (locale and voice).
         */
        fun create(
            orchestrator: TurnOrchestrator,
            selection: ProviderModelSelection,
            reasoning: ReasoningLevel?,
            languageModel: LanguageModel,
            textToSpeech: TextToSpeech?,
        ): VoiceAssistant =
            OrchestratingVoiceAssistant(
                orchestrator = orchestrator,
                selection = selection,
                reasoning = reasoning,
                languageModel = languageModel,
                textToSpeech = textToSpeech,
            )
    }
}
