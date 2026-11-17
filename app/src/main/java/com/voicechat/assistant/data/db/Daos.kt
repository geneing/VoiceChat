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

@Dao
interface MemoryDao {

    @Query("SELECT * FROM memory_facts")
    fun observeAll(): Flow<List<MemoryFactEntity>>

    @Query("SELECT * FROM memory_facts")
    suspend fun all(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE id = :id")
    suspend fun find(id: String): MemoryFactEntity?

    @Query("SELECT * FROM memory_facts WHERE kind = :kind AND subject = :subject LIMIT 1")
    suspend fun findBySubject(kind: String, subject: String): MemoryFactEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: MemoryFactEntity)

    @Query(
        "UPDATE memory_facts SET text = :text, keywords = :keywords, updatedAt = :updatedAt " +
            "WHERE id = :id",
    )
    suspend fun edit(id: String, text: String, keywords: String, updatedAt: Long)

    @Query(
        "UPDATE memory_facts SET lastUsedAt = :now, useCount = useCount + 1 " +
            "WHERE id IN (:ids)",
    )
    suspend fun markUsed(ids: List<String>, now: Long)

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM memory_facts")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM memory_facts")
    suspend fun count(): Int

    /** Drops the least useful facts first, never a pinned one. */
    @Query(
        "DELETE FROM memory_facts WHERE id IN (" +
            "SELECT id FROM memory_facts WHERE pinned = 0 " +
            "ORDER BY lastUsedAt ASC, useCount ASC, updatedAt ASC LIMIT :excess)",
    )
    suspend fun prune(excess: Int)
}
