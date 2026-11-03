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
