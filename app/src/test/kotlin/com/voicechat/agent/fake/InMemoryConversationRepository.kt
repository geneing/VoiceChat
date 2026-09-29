package com.voicechat.agent.fake

import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update

/**
 * In-memory [ConversationRepository] for tests.
 *
 * Emits summaries newest-updated first, mirroring the production ordering
 * contract. Set [failOnNextSave] to exercise persistence failure handling.
 */
class InMemoryConversationRepository : ConversationRepository {
    private val conversations = MutableStateFlow<Map<ConversationId, Conversation>>(emptyMap())

    /** When set, the next [save] throws this error (and then clears it). */
    var failOnNextSave: VoiceAgentError? = null

    override fun observeConversations(): Flow<List<ConversationSummary>> =
        conversations.map { stored ->
            stored.values
                .sortedByDescending { it.updatedAtEpochMillis }
                .map { conversation ->
                    ConversationSummary(
                        id = conversation.id,
                        updatedAtEpochMillis = conversation.updatedAtEpochMillis,
                        title = conversation.title,
                        turnCount = conversation.turns.size,
                    )
                }
        }

    override suspend fun load(id: ConversationId): Conversation? = conversations.value[id]

    override suspend fun save(conversation: Conversation) {
        failOnNextSave?.let { error ->
            failOnNextSave = null
            throw VoiceAgentException(error)
        }
        conversations.update { it + (conversation.id to conversation) }
    }

    override suspend fun delete(id: ConversationId) {
        conversations.update { it - id }
    }

    /** Convenience for tests that need an empty-state failure. */
    companion object {
        /** A standard persistence failure. */
        val PERSISTENCE_FAILURE: VoiceAgentError = VoiceAgentError(ErrorCode.PERSISTENCE_FAILED)
    }
}
