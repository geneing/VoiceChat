package com.voicechat.agent.orchestration

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ProviderModelSelection
import com.voicechat.agent.domain.ReasoningLevel
import com.voicechat.agent.domain.UserTurn
import com.voicechat.agent.domain.VoiceAgentError

/**
 * One turn to execute: a finalized user turn plus the conversation it belongs to.
 *
 * [userTurn] is expected to be final and, for a voice turn, already corrected by
 * the user; orchestration never rewrites recognition text. The [conversation]
 * snapshot has [userTurn] not yet appended — orchestration appends and persists
 * it before starting the provider request.
 */
data class TurnRequest(
    val conversation: Conversation,
    val userTurn: UserTurn,
    val selection: ProviderModelSelection,
    val reasoning: ReasoningLevel? = null,
)

/**
 * The outcome of running one turn.
 *
 * [conversation] is the last durably saved conversation, so the caller can adopt
 * exactly what was persisted. [record]/[listening] are the machine snapshots;
 * [persistenceFailure] is set when the user or assistant turn could not be
 * written and the caller must surface it instead of pretending success.
 */
data class TurnResult(
    val conversation: Conversation,
    val record: TurnRecord? = null,
    val listening: ListeningRecord? = null,
    val assistantPersisted: Boolean = false,
    val userTurnPersisted: Boolean = true,
    val persistenceFailure: VoiceAgentError? = null,
)

/**
 * Optional progress sink for a running turn.
 *
 * The orchestrator calls these on its own coroutine; implementations must be
 * cheap and must not suspend or mutate pipeline state. The UI uses this to
 * render live text; it never drives the pipeline.
 */
interface TurnObserver {
    /** The finalized user turn was persisted; [conversation] includes it. */
    fun onUserTurnCommitted(conversation: Conversation) = Unit

    /** Newly streamed assistant text (the running total), for live rendering. */
    fun onLiveAssistantText(text: String) = Unit

    /** The turn reached a terminal state (also called when it is cancelled). */
    fun onTurnFinished(result: TurnResult) = Unit

    /** Records nothing. */
    object NONE : TurnObserver
}
