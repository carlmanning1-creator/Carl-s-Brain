package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.ChatMessageEntity
import com.carlmanning.carlsbrain.data.local.entity.ChatThreadEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ChatDao {

    // ── Threads ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM chat_threads ORDER BY updatedAt DESC")
    fun getAllThreads(): Flow<List<ChatThreadEntity>>

    @Query("SELECT * FROM chat_threads WHERE id = :id")
    suspend fun getThreadById(id: Long): ChatThreadEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertThread(thread: ChatThreadEntity): Long

    @Update
    suspend fun updateThread(thread: ChatThreadEntity)

    @Query("DELETE FROM chat_threads WHERE id = :id")
    suspend fun deleteThread(id: Long)

    // ── Messages ──────────────────────────────────────────────────────────────

    @Query("SELECT * FROM chat_messages WHERE threadId = :threadId ORDER BY createdAt ASC")
    suspend fun getMessagesForThread(threadId: Long): List<ChatMessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: ChatMessageEntity): Long

    @Query("DELETE FROM chat_messages WHERE threadId = :threadId")
    suspend fun deleteMessagesForThread(threadId: Long)
}
