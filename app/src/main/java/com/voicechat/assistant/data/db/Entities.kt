package com.voicechat.assistant.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "chats")
data class ChatEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val preview: String = "",
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ChatEntity::class,
            parentColumns = ["id"],
            childColumns = ["chatId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("chatId"),
        Index("createdAt"),
    ],
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val chatId: String,
    val role: String,
    val kind: String,
    val text: String,
    val attachmentsJson: String = "[]",
    val createdAt: Long,
    val streaming: Boolean = false,
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val ttftMs: Long = -1,
    val costUsd: Double = 0.0,
)

/**
 * One remembered fact about the user.
 *
 * Deliberately plain: no foreign key, so deleting a chat never deletes what was
 * learned from it, and no vector column, because the whole table is small enough
 * to rank with a single lexical pass in memory.
 */
@Entity(
    tableName = "memory_facts",
    indices = [Index(value = ["kind", "subject"], unique = true)],
)
data class MemoryFactEntity(
    @PrimaryKey val id: String,
    val kind: String,
    val subject: String,
    val text: String,
    val keywords: String = "",
    val confidence: Float = 0.6f,
    val pinned: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
    val lastUsedAt: Long = 0L,
    val useCount: Int = 0,
    val sourceChatId: String? = null,
)
