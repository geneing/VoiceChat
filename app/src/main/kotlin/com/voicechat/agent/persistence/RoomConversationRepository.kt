package com.voicechat.agent.persistence

import com.voicechat.agent.contracts.ConversationRepository
import com.voicechat.agent.domain.Conversation
import com.voicechat.agent.domain.ConversationId
import com.voicechat.agent.domain.ConversationSummary
import com.voicechat.agent.domain.ErrorCode
import com.voicechat.agent.domain.VoiceAgentError
import com.voicechat.agent.domain.VoiceAgentException
import com.voicechat.agent.log.AppLog
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlin.coroutines.cancellation.CancellationException

/**
 * Room-backed [ConversationRepository].
 *
 * Implements the M02 contract without adding storage-specific methods: rename and
 * single-turn deletion are expressed through `load` + a domain copy + `save`
 * (see `Conversation.renamed` / `Conversation.withoutTurn`).
 *
 * **Error mapping.** A failed read, write, or delete is translated to
 * [VoiceAgentException] with [ErrorCode.PERSISTENCE_FAILED]. The exception
 * message is the stable code name and the detail names only the operation, so no
 * transcript, prompt, or credential can leak through an error. Cancellation is
 * rethrown unchanged so structured concurrency still works.
 */
internal class RoomConversationRepository(
    private val dao: ConversationDao,
) : ConversationRepository {
    override fun observeConversations(): Flow<List<ConversationSummary>> =
        dao
            .observeSummaries()
            .map { rows -> rows.map { it.toDomain() } }
            .catch {
                AppLog.e(it) { "persistence: observe conversations failed" }
                throw persistenceFailure("observe")
            }

    override suspend fun load(id: ConversationId): Conversation? =
        persistenceCall("load") {
            val entity = dao.conversation(id.value) ?: return@persistenceCall null
            entity.toDomain(dao.turns(id.value))
        }

    override suspend fun save(conversation: Conversation) =
        persistenceCall("save") {
            AppLog.d {
                "persistence: save conversation turns=${conversation.turns.size}"
            }
            dao.replaceConversation(conversation.toEntity(), conversation.toTurnEntities())
        }

    override suspend fun delete(id: ConversationId) =
        persistenceCall("delete") {
            dao.deleteConversation(id.value)
        }

    private fun persistenceFailure(operation: String): VoiceAgentException =
        VoiceAgentException(VoiceAgentError(ErrorCode.PERSISTENCE_FAILED, "conversation $operation failed"))

    private suspend fun <T> persistenceCall(
        operation: String,
        block: suspend () -> T,
    ): T =
        try {
            val result = block()
            AppLog.d { "persistence: $operation ok" }
            result
        } catch (cancellation: CancellationException) {
            throw cancellation
        } catch (failure: Throwable) {
            AppLog.e(failure) { "persistence: $operation failed" }
            throw VoiceAgentException(
                error = VoiceAgentError(ErrorCode.PERSISTENCE_FAILED, "conversation $operation failed"),
                cause = failure,
            )
        }
}
