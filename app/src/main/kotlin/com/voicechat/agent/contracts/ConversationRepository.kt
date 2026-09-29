package com.voicechat.agent.contracts

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import kotlinx.coroutines.flow.Flow

/**
 * Durable conversation storage.
 *
 * **Ownership and lifecycle.** [observeConversations] is a cold flow that emits
 * the current list and re-emits on change; collection does not start or stop
 * storage, which the implementation owns. Suspending methods may throw
 * [com.voicechat.agent.domain.VoiceAgentException] with
 * [com.voicechat.agent.domain.ErrorCode.PERSISTENCE_FAILED]. The repository
 * stores the full conversation independently of the smaller context sent to a
 * model; it does not decide what context an LLM request includes.
 */
interface ConversationRepository {
    /** Observes conversation summaries, newest update first. */
    fun observeConversations(): Flow<List<ConversationSummary>>

    /** Loads one conversation with its turns, or null when it does not exist. */
    suspend fun load(id: ConversationId): Conversation?

    /** Inserts or replaces a conversation. */
    suspend fun save(conversation: Conversation)

    /** Deletes a conversation and its turns. Deleting a missing ID is a no-op. */
    suspend fun delete(id: ConversationId)
}
