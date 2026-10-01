package com.voicechat.agent.persistence

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/**
 * SQL surface for conversations and turns.
 *
 * All mutations go through [replaceConversation] (full replacement, for recovery,
 * rename, and deletion) or [upsertTurnAndConversation] (append/update the active
 * turn without rewriting its siblings); each is one transaction, so a crash can
 * never leave a conversation whose turn list is half updated.
 */
@Dao
internal abstract class ConversationDao {
    /** Conversation summaries, newest update first (stable by ID on ties). */
    @Query(
        """
        SELECT c.id AS id,
               c.updatedAtEpochMillis AS updatedAtEpochMillis,
               c.title AS title,
               COUNT(t.id) AS turnCount
        FROM conversations c
        LEFT JOIN turns t ON t.conversationId = c.id
        GROUP BY c.id
        ORDER BY c.updatedAtEpochMillis DESC, c.id ASC
        """,
    )
    abstract fun observeSummaries(): Flow<List<ConversationSummaryRow>>

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    abstract suspend fun conversation(id: String): ConversationEntity?

    /** Turns for one conversation in stored order. */
    @Query("SELECT * FROM turns WHERE conversationId = :conversationId ORDER BY position ASC")
    abstract suspend fun turns(conversationId: String): List<TurnEntity>

    /** Total turn rows for one conversation; used by retention/deletion checks. */
    @Query("SELECT COUNT(*) FROM turns WHERE conversationId = :conversationId")
    abstract suspend fun turnCount(conversationId: String): Int

    @Upsert
    abstract suspend fun upsertConversation(conversation: ConversationEntity)

    @Upsert
    abstract suspend fun upsertTurns(turns: List<TurnEntity>)

    @Upsert
    abstract suspend fun upsertTurn(turn: TurnEntity)

    @Query("DELETE FROM turns WHERE conversationId = :conversationId")
    abstract suspend fun deleteTurns(conversationId: String)

    @Query("DELETE FROM conversations WHERE id = :id")
    abstract suspend fun deleteConversation(id: String)

    /**
     * Replaces a conversation and its full turn list atomically.
     *
     * Deleting the old turns before inserting guarantees that renamed, removed,
     * or reordered turns are not left behind by an upsert-only path.
     */
    @Transaction
    open suspend fun replaceConversation(
        conversation: ConversationEntity,
        turns: List<TurnEntity>,
    ) {
        upsertConversation(conversation)
        deleteTurns(conversation.id)
        if (turns.isNotEmpty()) {
            upsertTurns(turns)
        }
    }

    /**
     * Upserts one conversation row and one turn row atomically, leaving every
     * sibling turn untouched (CODE_REVIEW P2, R-0222).
     *
     * The conversation is written first so the turn's foreign key is satisfied.
     * Unlike [replaceConversation], this never deletes the turn list, so appending
     * or updating the active turn does not rewrite the whole history.
     */
    @Transaction
    open suspend fun upsertTurnAndConversation(
        conversation: ConversationEntity,
        turn: TurnEntity,
    ) {
        upsertConversation(conversation)
        upsertTurn(turn)
    }
}
