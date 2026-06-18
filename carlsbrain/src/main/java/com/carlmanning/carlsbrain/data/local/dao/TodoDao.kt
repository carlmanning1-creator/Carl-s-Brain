package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TodoDao {

    @Query("SELECT * FROM todos WHERE deletedAt IS NULL ORDER BY priority ASC, dueDate ASC, createdAt DESC")
    fun getAllTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE bucketId = :bucketId AND deletedAt IS NULL ORDER BY priority ASC, dueDate ASC")
    fun getTodosByBucket(bucketId: Long): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE priority = :priority AND deletedAt IS NULL ORDER BY dueDate ASC, createdAt DESC")
    fun getTodosByPriority(priority: Int): Flow<List<TodoEntity>>

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
          AND t.deletedAt IS NULL
        ORDER BY t.priority ASC, t.dueDate ASC
    """)
    fun getNonVaultTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isDone = 0 AND deletedAt IS NULL ORDER BY priority ASC, dueDate ASC")
    fun getActiveTodos(): Flow<List<TodoEntity>>

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
          AND t.isDone = 0
          AND t.deletedAt IS NULL
        ORDER BY t.priority ASC, t.dueDate ASC
    """)
    fun getActiveNonVaultTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isArchived = 0 AND deletedAt IS NULL ORDER BY isDone ASC, priority ASC, dueDate ASC, createdAt DESC")
    fun getVisibleTodos(): Flow<List<TodoEntity>>

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
          AND t.isArchived = 0
          AND t.deletedAt IS NULL
        ORDER BY t.isDone ASC, t.priority ASC, t.dueDate ASC, t.createdAt DESC
    """)
    fun getVisibleNonVaultTodos(): Flow<List<TodoEntity>>

    @Query("SELECT * FROM todos WHERE isArchived = 1 AND deletedAt IS NULL ORDER BY archivedAt DESC")
    fun getArchivedTodos(): Flow<List<TodoEntity>>

    @Query("UPDATE todos SET isArchived = 1, archivedAt = :archivedAt, isSynced = 0 WHERE id = :id")
    suspend fun archiveTodo(id: Long, archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET isArchived = 1, archivedAt = :archivedAt, isSynced = 0 WHERE isDone = 1 AND isArchived = 0 AND deletedAt IS NULL")
    suspend fun archiveAllCompleted(archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET isArchived = 0, archivedAt = NULL, isDone = 0, isSynced = 0 WHERE id = :id")
    suspend fun restoreTodo(id: Long)

    @Query("SELECT * FROM todos WHERE id = :id")
    suspend fun getTodoById(id: Long): TodoEntity?

    @Query("SELECT * FROM todos WHERE isSynced = 0 AND deletedAt IS NULL")
    suspend fun getUnsyncedTodos(): List<TodoEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTodo(todo: TodoEntity): Long

    @Update
    suspend fun updateTodo(todo: TodoEntity)

    @Delete
    suspend fun deleteTodo(todo: TodoEntity)

    @Query("UPDATE todos SET isDone = :isDone, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun setTodoDone(id: Long, isDone: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM todos WHERE priority IN (0,1) AND isArchived = 0 AND isDone = 0 AND deletedAt IS NULL ORDER BY priority ASC, dueDate ASC")
    suspend fun getUrgentHighTodos(): List<TodoEntity>

    @Query("""SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE t.priority IN (0,1) AND t.isDone = 0 AND t.isArchived = 0 AND t.deletedAt IS NULL AND b.isVault = 0
        ORDER BY t.priority ASC, t.dueDate ASC LIMIT 5""")
    suspend fun getUrgentHighTodosNonVault(): List<TodoEntity>

    @Query("""SELECT COUNT(*) FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE t.dueDate < :now AND t.isDone = 0 AND t.isArchived = 0 AND t.deletedAt IS NULL AND b.isVault = 0""")
    suspend fun getOverdueCountNonVault(now: Long = System.currentTimeMillis()): Int

    @Query("SELECT * FROM todos WHERE reminderAt IS NOT NULL AND reminderAt > :now AND isDone = 0 AND isArchived = 0 AND deletedAt IS NULL")
    suspend fun getActiveReminders(now: Long = System.currentTimeMillis()): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE calendarEventId = :eventId AND deletedAt IS NULL LIMIT 1")
    suspend fun findByCalendarEventId(eventId: String): TodoEntity?

    @Query("SELECT * FROM todos WHERE title = :title AND recurrence = :recurrence AND isDone = 0 AND deletedAt IS NULL LIMIT 1")
    suspend fun findActiveRecurringByTitleAndRecurrence(title: String, recurrence: String): TodoEntity?

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
          AND t.isArchived = 0
          AND t.deletedAt IS NULL
          AND t.title LIKE '%' || :query || '%'
        ORDER BY t.isDone ASC, t.priority ASC, t.dueDate ASC
        LIMIT 50
    """)
    suspend fun searchTodos(query: String): List<TodoEntity>

    @Query("UPDATE todos SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE todos SET isPinned = :isPinned WHERE id = :id")
    suspend fun updateIsPinned(id: Long, isPinned: Boolean)

    @Query("SELECT * FROM todos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getDeletedTodos(): Flow<List<TodoEntity>>

    // Includes soft-deleted rows — used by sync to avoid resurrecting deleted items
    @Query("SELECT * FROM todos")
    suspend fun getAllTodosIncludingDeleted(): List<TodoEntity>

    @Query("UPDATE todos SET deletedAt = :deletedAt, isSynced = 0 WHERE id = :id")
    suspend fun softDeleteTodo(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET deletedAt = NULL, isSynced = 0 WHERE id = :id")
    suspend fun restoreTodoFromBin(id: Long)

    @Query("DELETE FROM todos WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedTodos(cutoffMs: Long)
}
