package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
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

    // ── Drive sync ────────────────────────────────────────────────────────────
    //
    // A thread's Drive file holds the whole conversation, so "unsynced" has to mean "the row
    // OR any of its messages changed". Every write path therefore calls [markUnsynced] on the
    // thread, not just the ones that touch the thread row.

    @Query("SELECT * FROM chat_threads WHERE isSynced = 0")
    suspend fun getUnsyncedThreads(): List<ChatThreadEntity>

    @Query("SELECT id FROM chat_threads WHERE isSynced = 1")
    suspend fun getSyncedIds(): List<Long>

    @Query("SELECT id FROM chat_threads")
    suspend fun getAllThreadIds(): List<Long>

    @Query("UPDATE chat_threads SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("UPDATE chat_threads SET isSynced = 0 WHERE id = :id")
    suspend fun markUnsynced(id: Long)

    @Query("UPDATE chat_threads SET isSynced = 0 WHERE id IN (:ids)")
    suspend fun markUnsynced(ids: List<Long>)

    /**
     * Replaces a thread's messages with what Drive holds.
     *
     * Wholesale rather than reconciled row by row, for the same reason subtasks travel by
     * value in the wire format: message ids are per-device autoincrement values and mean
     * nothing on the other client, so matching them up would guess wrong.
     */
    @Transaction
    suspend fun replaceMessages(threadId: Long, messages: List<ChatMessageEntity>) {
        deleteMessagesForThread(threadId)
        messages.forEach { insertMessage(it.copy(id = 0, threadId = threadId)) }
    }
}
