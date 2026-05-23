package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface NoteDao {

    @Query("SELECT * FROM notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bucketId = :bucketId AND deletedAt IS NULL ORDER BY updatedAt DESC")
    fun getNotesByBucket(bucketId: Long): Flow<List<NoteEntity>>

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0
          AND n.deletedAt IS NULL
        ORDER BY n.updatedAt DESC
    """)
    fun getNonVaultNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE isSynced = 0 AND deletedAt IS NULL")
    suspend fun getUnsyncedNotes(): List<NoteEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertNote(note: NoteEntity): Long

    @Update
    suspend fun updateNote(note: NoteEntity)

    @Delete
    suspend fun deleteNote(note: NoteEntity)

    @Query("DELETE FROM notes WHERE id = :id")
    suspend fun deleteNoteById(id: Long)

    @Query("UPDATE notes SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0
          AND n.deletedAt IS NULL
          AND (n.title LIKE '%' || :query || '%' OR n.content LIKE '%' || :query || '%' OR n.tags LIKE '%' || :query || '%')
        ORDER BY n.updatedAt DESC
        LIMIT 50
    """)
    suspend fun searchNotes(query: String): List<NoteEntity>

    @Query("UPDATE notes SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("UPDATE notes SET isPinned = :isPinned WHERE id = :id")
    suspend fun updateIsPinned(id: Long, isPinned: Boolean)

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0
          AND n.deletedAt IS NULL
          AND n.reminderAt IS NOT NULL
          AND n.reminderAt >= :from
          AND n.reminderAt < :to
        ORDER BY n.reminderAt ASC
    """)
    suspend fun getNotesWithReminders(from: Long, to: Long): List<NoteEntity>

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE n.deletedAt IS NULL
          AND n.reminderAt IS NOT NULL
          AND n.reminderAt >= :from
          AND n.reminderAt < :to
        ORDER BY n.reminderAt ASC
    """)
    suspend fun getAllNotesWithReminders(from: Long, to: Long): List<NoteEntity>

    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getDeletedNotes(): Flow<List<NoteEntity>>

    // Includes soft-deleted rows — used by sync to avoid resurrecting deleted notes
    @Query("SELECT id FROM notes")
    suspend fun getAllNoteIds(): List<Long>

    @Query("UPDATE notes SET isSynced = 0 WHERE deletedAt IS NULL")
    suspend fun markAllNotesUnsynced()

    @Query("UPDATE notes SET deletedAt = :deletedAt, isSynced = 0 WHERE id = :id")
    suspend fun softDeleteNote(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET deletedAt = NULL, isSynced = 0 WHERE id = :id")
    suspend fun restoreNoteFromBin(id: Long)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedNotes(cutoffMs: Long)
}
