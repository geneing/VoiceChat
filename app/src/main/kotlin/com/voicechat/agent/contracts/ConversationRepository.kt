package com.voicechat.agent.contracts

import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.Turn
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

    /**
     * Persists [conversation]'s metadata and the single [turn] in it, without
     * rewriting its sibling turns.
     *
     * This is the append/update path for the active turn (CODE_REVIEW P2, R-0222):
     * the turn just committed (user) or just settled (assistant) is upserted by id
     * in one transaction with the conversation row, so a long history is not
     * deleted and reinserted on every turn. [turn] must be present in
     * [conversation].[turns]; the default implementation falls back to the
     * authoritative full replacement in [save], so an implementation that does not
     * override this stays correct (just less efficient).
     *
     * [save] remains the replacement path for recovery, rename, and deletion,
     * where the turn list can shrink or be reordered.
     */
    suspend fun saveTurn(
        conversation: Conversation,
        turn: Turn,
    ) {
        require(conversation.turns.any { it.id == turn.id }) {
            "turn ${turn.id.value} is not part of conversation ${conversation.id.value}"
        }
        save(conversation)
    }

    /** Deletes a conversation and its turns. Deleting a missing ID is a no-op. */
    suspend fun delete(id: ConversationId)
}
