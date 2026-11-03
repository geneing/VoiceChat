package com.voicechat.assistant.data.repo

import com.voicechat.assistant.core.AppLog
import com.voicechat.assistant.core.LogCategory
import com.voicechat.assistant.data.db.AppDatabase
import com.voicechat.assistant.data.db.ChatEntity
import com.voicechat.assistant.data.db.MessageEntity
import com.voicechat.assistant.data.model.Attachment
import com.voicechat.assistant.data.model.Chat
import com.voicechat.assistant.data.model.Message
import com.voicechat.assistant.data.model.MessageKind
import com.voicechat.assistant.data.model.MessageRole
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.Json
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import java.util.UUID

class ChatRepository(private val db: AppDatabase) {

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    val chats: Flow<List<Chat>> = db.chatDao().observeAll().map { list ->
        list.map { it.toDomain() }
    }

    fun messages(chatId: String): Flow<List<Message>> =
        db.messageDao().observeForChat(chatId).map { list ->
            list.map { it.toDomain(json) }
        }

    suspend fun chat(id: String): Chat? = db.chatDao().find(id)?.toDomain()

    suspend fun createChat(title: String = "New chat"): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        db.chatDao().upsert(
            ChatEntity(
                id = id,
                title = title,
                createdAt = now,
                updatedAt = now,
                preview = "",
            ),
        )
        AppLog.debug(LogCategory.DB, "Created chat $id")
        return id
    }

    suspend fun ensureChat(chatId: String?): String {
        if (chatId != null && db.chatDao().find(chatId) != null) return chatId
        return createChat()
    }

    suspend fun renameChat(chatId: String, title: String) {
        val clean = title.trim().ifBlank { "Untitled" }
        db.chatDao().rename(chatId, clean, System.currentTimeMillis())
    }

    suspend fun deleteChat(chatId: String) {
        db.chatDao().delete(chatId)
        AppLog.debug(LogCategory.DB, "Deleted chat $chatId")
    }

    suspend fun addMessage(message: Message) {
        db.messageDao().upsert(message.toEntity(json))
        db.chatDao().touch(
            id = message.chatId,
            updatedAt = System.currentTimeMillis(),
            preview = message.text.take(120),
        )
        maybeTitle(message)
    }

    suspend fun updateMessage(
        id: String,
        text: String? = null,
        streaming: Boolean? = null,
        inputTokens: Int? = null,
        outputTokens: Int? = null,
        ttftMs: Long? = null,
        costUsd: Double? = null,
    ) {
        val existing = db.messageDao().find(id) ?: return
        val updated = existing.copy(
            text = text ?: existing.text,
            streaming = streaming ?: existing.streaming,
            inputTokens = inputTokens ?: existing.inputTokens,
            outputTokens = outputTokens ?: existing.outputTokens,
            ttftMs = ttftMs ?: existing.ttftMs,
            costUsd = costUsd ?: existing.costUsd,
        )
        db.messageDao().upsert(updated)
        if (updated.role == MessageRole.ASSISTANT.name) {
            db.chatDao().touch(
                id = updated.chatId,
                updatedAt = System.currentTimeMillis(),
                preview = updated.text.take(120),
            )
        }
    }

    suspend fun deleteMessage(id: String) {
        db.messageDao().delete(id)
    }

    suspend fun message(id: String): Message? = db.messageDao().find(id)?.toDomain(json)

    suspend fun lastMessage(chatId: String): Message? =
        db.messageDao().lastForChat(chatId)?.toDomain(json)

    private suspend fun maybeTitle(message: Message) {
        if (message.role != MessageRole.USER || message.text.isBlank()) return
        val existing = db.chatDao().find(message.chatId) ?: return
        if (existing.title == "New chat" || existing.title.isBlank()) {
            val title = message.text
                .replace(Regex("\\s+"), " ")
                .trim()
                .take(48)
                .ifBlank { "New chat" }
            db.chatDao().rename(message.chatId, title, System.currentTimeMillis())
        }
    }
}

internal fun ChatEntity.toDomain(): Chat = Chat(
    id = id,
    title = title,
    createdAt = createdAt,
    updatedAt = updatedAt,
    preview = preview,
)

internal fun MessageEntity.toDomain(json: Json): Message = Message(
    id = id,
    chatId = chatId,
    role = MessageRole.entries.firstOrNull { it.name == role } ?: MessageRole.USER,
    kind = MessageKind.entries.firstOrNull { it.name == kind } ?: MessageKind.TEXT,
    text = text,
    attachments = runCatching {
        json.decodeFromString<List<Attachment>>(attachmentsJson)
    }.getOrDefault(emptyList()),
    createdAt = createdAt,
    streaming = streaming,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    ttftMs = ttftMs,
    costUsd = costUsd,
)

internal fun Message.toEntity(json: Json): MessageEntity = MessageEntity(
    id = id,
    chatId = chatId,
    role = role.name,
    kind = kind.name,
    text = text,
    attachmentsJson = json.encodeToString(attachments),
    createdAt = createdAt,
    streaming = streaming,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    ttftMs = ttftMs,
    costUsd = costUsd,
)
