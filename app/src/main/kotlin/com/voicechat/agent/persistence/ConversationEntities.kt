package com.voicechat.agent.persistence

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room row for one conversation.
 *
 * This is a storage detail only. The platform-free conversation domain types are
 * mapped at the repository boundary in [ConversationMappers]; no Room type
 * appears in `com.voicechat.agent.domain`.
 */
@Entity(tableName = "conversations")
internal data class ConversationEntity(
    @PrimaryKey val id: String,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
    val title: String?,
)

/**
 * Room row for one user or assistant turn.
 *
 * User and assistant fields share one table so a conversation's turns keep one
 * monotonic [position] order. Exactly one group of fields is populated, chosen
 * by [kind] ([TurnKind.USER] or [TurnKind.ASSISTANT]); the mapper enforces that
 * grouping when reading a row back.
 *
 * Enum values are stored by name, matching the persisted-contract rule in
 * `com.voicechat.agent.domain.ErrorCode`.
 */
@Entity(
    tableName = "turns",
    foreignKeys =
        [
            ForeignKey(
                entity = ConversationEntity::class,
                parentColumns = ["id"],
                childColumns = ["conversationId"],
                onDelete = ForeignKey.CASCADE,
            ),
        ],
    indices = [Index("conversationId")],
)
internal data class TurnEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val position: Int,
    val kind: String,
    // User turn fields.
    val transcriptText: String? = null,
    val transcriptRevision: Int? = null,
    val transcriptIsFinal: Boolean? = null,
    val transcriptLanguageTag: String? = null,
    val transcriptConfidence: Float? = null,
    val userTurnSource: String? = null,
    // Assistant turn fields.
    val generatedText: String? = null,
    val generationState: String? = null,
    val deliveredText: String? = null,
    val deliveryState: String? = null,
)

/** Stable [TurnEntity.kind] values. Names are part of the storage contract. */
internal object TurnKind {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
}

/**
 * Projection for the conversation list.
 *
 * Room maps query columns to these names; [RoomConversationRepository] then
 * maps them to the platform-free `ConversationSummary`.
 */
internal data class ConversationSummaryRow(
    val id: String,
    val updatedAtEpochMillis: Long,
    val title: String?,
    val turnCount: Int,
)
