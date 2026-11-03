package com.voicechat.assistant.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    @Query("SELECT * FROM chats ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<ChatEntity>>

    @Query("SELECT * FROM chats WHERE id = :id")
    suspend fun find(id: String): ChatEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(chat: ChatEntity)

    @Query("UPDATE chats SET title = :title, updatedAt = :updatedAt WHERE id = :id")
    suspend fun rename(id: String, title: String, updatedAt: Long)

    @Query("UPDATE chats SET updatedAt = :updatedAt, preview = :preview WHERE id = :id")
    suspend fun touch(id: String, updatedAt: Long, preview: String)

    @Query("DELETE FROM chats WHERE id = :id")
    suspend fun delete(id: String)
}

@Dao
interface MessageDao {

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt ASC")
    fun observeForChat(chatId: String): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages WHERE id = :id")
    suspend fun find(id: String): MessageEntity?

    @Query("SELECT * FROM messages WHERE chatId = :chatId ORDER BY createdAt DESC LIMIT 1")
    suspend fun lastForChat(chatId: String): MessageEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(message: MessageEntity)

    @Query("DELETE FROM messages WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM messages WHERE chatId = :chatId")
    suspend fun deleteForChat(chatId: String)
}
