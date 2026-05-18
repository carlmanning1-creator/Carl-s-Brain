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

    @Query("SELECT * FROM notes ORDER BY updatedAt DESC")
    fun getAllNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE bucketId = :bucketId ORDER BY updatedAt DESC")
    fun getNotesByBucket(bucketId: Long): Flow<List<NoteEntity>>

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0
        ORDER BY n.updatedAt DESC
    """)
    fun getNonVaultNotes(): Flow<List<NoteEntity>>

    @Query("SELECT * FROM notes WHERE id = :id")
    suspend fun getNoteById(id: Long): NoteEntity?

    @Query("SELECT * FROM notes WHERE isSynced = 0")
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
          AND (n.title LIKE '%' || :query || '%' OR n.content LIKE '%' || :query || '%')
        ORDER BY n.updatedAt DESC
        LIMIT 50
    """)
    suspend fun searchNotes(query: String): List<NoteEntity>

    @Query("UPDATE notes SET sortOrder = :sortOrder WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int)

    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0
          AND n.reminderAt IS NOT NULL
          AND n.reminderAt >= :from
          AND n.reminderAt < :to
        ORDER BY n.reminderAt ASC
    """)
    suspend fun getNotesWithReminders(from: Long, to: Long): List<NoteEntity>
}
