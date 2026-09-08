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

    /** Todos created from a meeting's action items — the reverse of TodoEntity.sourceMeetingId. */
    @Query("SELECT * FROM todos WHERE sourceMeetingId = :meetingId AND deletedAt IS NULL ORDER BY isDone ASC, priority ASC")
    fun getTodosFromMeeting(meetingId: Long): Flow<List<TodoEntity>>

    /**
     * Archived rows are excluded here, not just in [getVisibleTodos]: archiving is Carl saying
     * "stop showing me this", and these feed the Dashboard, the urgent badge and the loose-thread
     * detector — all of which were re-raising work he had deliberately put away.
     */
    @Query("SELECT * FROM todos WHERE isDone = 0 AND isArchived = 0 AND deletedAt IS NULL ORDER BY priority ASC, dueDate ASC")
    fun getActiveTodos(): Flow<List<TodoEntity>>

    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0
          AND t.isDone = 0
          AND t.isArchived = 0
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

    /**
     * `updatedAt` moves with the change, not just `isSynced`.
     *
     * Clearing the sync flag alone published a row whose stamp predated the archive, and the
     * other side's `remote <= local` guard then discarded it — so the archive appeared to undo
     * itself on the next sync. Every write path that changes what a row *means* has to move the
     * stamp too.
     */
    @Query("UPDATE todos SET isArchived = 1, archivedAt = :archivedAt, updatedAt = :archivedAt, isSynced = 0 WHERE id = :id")
    suspend fun archiveTodo(id: Long, archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET isArchived = 1, archivedAt = :archivedAt, updatedAt = :archivedAt, isSynced = 0 WHERE isDone = 1 AND isArchived = 0 AND deletedAt IS NULL")
    suspend fun archiveAllCompleted(archivedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET isArchived = 0, archivedAt = NULL, isDone = 0, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun restoreTodo(id: Long, updatedAt: Long = System.currentTimeMillis())

    /**
     * Un-archives a todo while preserving its done state. Used by swipe-to-archive undo,
     * where the todo must return exactly as it was — unlike [restoreTodo], which deliberately
     * re-opens a completed todo when pulling it back out of History.
     */
    @Query("UPDATE todos SET isArchived = 0, archivedAt = NULL, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun unarchiveTodo(id: Long, updatedAt: Long = System.currentTimeMillis())

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

    /**
     * Completion signal — how many to-dos were ticked off since [since].
     * Archived rows still count: archiving is filing finished work away, not undoing it.
     */
    @Query("SELECT COUNT(*) FROM todos WHERE isDone = 1 AND updatedAt >= :since AND deletedAt IS NULL")
    suspend fun countCompletedSince(since: Long): Int

    /** Vault-safe variant of [countCompletedSince] — used whenever the vault is closed. */
    @Query("""SELECT COUNT(*) FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0 AND t.isDone = 1 AND t.updatedAt >= :since AND t.deletedAt IS NULL""")
    suspend fun countCompletedSinceNonVault(since: Long): Int

    /**
     * Urgent and high-priority to-dos, vault included — only ever called with the vault open.
     *
     * LIMIT 5, matching [getUrgentHighTodosNonVault]. This was unlimited while its vault-filtered
     * twin was capped, so opening the vault changed how much of the Dashboard was shown —
     * unrelated to what the vault actually contains, and read as the list glitching.
     */
    @Query("SELECT * FROM todos WHERE priority IN (0,1) AND isArchived = 0 AND isDone = 0 AND deletedAt IS NULL ORDER BY priority ASC, dueDate ASC LIMIT 5")
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

    /**
     * Overdue rows, not just the count, at any priority. The widget needs these so its
     * overdue titles match its overdue count — deriving titles from the urgent/high list
     * silently omits Normal and Someday items that are genuinely overdue.
     */
    @Query("""SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE t.dueDate < :now AND t.isDone = 0 AND t.isArchived = 0 AND t.deletedAt IS NULL AND b.isVault = 0
        ORDER BY t.dueDate ASC""")
    suspend fun getOverdueNonVault(now: Long = System.currentTimeMillis()): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE reminderAt IS NOT NULL AND reminderAt > :now AND isDone = 0 AND isArchived = 0 AND deletedAt IS NULL")
    suspend fun getActiveReminders(now: Long = System.currentTimeMillis()): List<TodoEntity>

    @Query("SELECT * FROM todos WHERE calendarEventId = :eventId AND deletedAt IS NULL LIMIT 1")
    suspend fun findByCalendarEventId(eventId: String): TodoEntity?

    /**
     * Includes soft-deleted rows — used by the calendar import guard so a todo Carl
     * deleted is never recreated on the next dashboard refresh.
     */
    @Query("SELECT * FROM todos WHERE calendarEventId = :eventId LIMIT 1")
    suspend fun findAnyByCalendarEventId(eventId: String): TodoEntity?

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

    /** Search including vault buckets — only ever called with the vault open. */
    @Query("""
        SELECT t.* FROM todos t
        WHERE t.isArchived = 0
          AND t.deletedAt IS NULL
          AND t.title LIKE '%' || :query || '%'
        ORDER BY t.isDone ASC, t.priority ASC, t.dueDate ASC
        LIMIT 50
    """)
    suspend fun searchAllTodos(query: String): List<TodoEntity>

    /**
     * Ordering and pinning clear `isSynced` like every other edit.
     *
     * They did not, so a pin was a purely local fact: it never reached Drive, never reached the
     * web app, and was silently gone on a replacement phone. `updatedAt` moves with it so the
     * other side does not discard the change as stale.
     */
    @Query("UPDATE todos SET sortOrder = :sortOrder, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE todos SET isPinned = :isPinned, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun updateIsPinned(id: Long, isPinned: Boolean, updatedAt: Long = System.currentTimeMillis())

    @Query("SELECT * FROM todos WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getDeletedTodos(): Flow<List<TodoEntity>>

    /**
     * Recently Deleted with the vault closed.
     *
     * The bin was built from [getDeletedTodos] regardless of vault state, so every deleted vault
     * to-do listed its title for the ninety days it sat there — the vault rule applied to the
     * live list and stopped at the recycle bin.
     */
    @Query("""
        SELECT t.* FROM todos t
        INNER JOIN buckets b ON t.bucketId = b.id
        WHERE b.isVault = 0 AND t.deletedAt IS NOT NULL
        ORDER BY t.deletedAt DESC
    """)
    fun getDeletedNonVaultTodos(): Flow<List<TodoEntity>>

    // Includes soft-deleted rows — used by sync to avoid resurrecting deleted items
    @Query("SELECT * FROM todos")
    suspend fun getAllTodosIncludingDeleted(): List<TodoEntity>

    /** Live (non-deleted) todos in a bucket — used to warn before bucket deletion. */
    @Query("SELECT COUNT(*) FROM todos WHERE bucketId = :bucketId AND deletedAt IS NULL")
    suspend fun countInBucket(bucketId: Long): Int

    /** Live (non-deleted) todo ids in a bucket — used to soft-delete a bucket's contents. */
    @Query("SELECT id FROM todos WHERE bucketId = :bucketId AND deletedAt IS NULL")
    suspend fun getIdsInBucket(bucketId: Long): List<Long>

    /**
     * Reassigns EVERY todo off [fromBucketId] — including soft-deleted ones sitting in
     * Recently Deleted. Deliberately not filtered on deletedAt: any row left pointing at
     * the bucket would be destroyed by the FK CASCADE when the bucket row is removed.
     */
    @Query("UPDATE todos SET bucketId = :toBucketId, updatedAt = :updatedAt, isSynced = 0 WHERE bucketId = :fromBucketId")
    suspend fun moveAllToBucket(
        fromBucketId: Long,
        toBucketId: Long,
        updatedAt: Long = System.currentTimeMillis()
    )

    @Query("UPDATE todos SET deletedAt = :deletedAt, isSynced = 0 WHERE id = :id")
    suspend fun softDeleteTodo(id: Long, deletedAt: Long = System.currentTimeMillis())

    // updatedAt moves too — see NoteDao.restoreNoteFromBin.
    @Query("UPDATE todos SET deletedAt = NULL, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun restoreTodoFromBin(id: Long, updatedAt: Long = System.currentTimeMillis())

    @Query("DELETE FROM todos WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedTodos(cutoffMs: Long)
}
