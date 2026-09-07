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

    /**
     * Search including vault buckets — only ever called with the vault open.
     *
     * The vault-closed variant above is the default; this exists because searching with the
     * vault open used to return nothing for a vault note, with no indication why. Meetings and
     * journal entries already had this pair.
     */
    @Query("""
        SELECT n.* FROM notes n
        WHERE n.deletedAt IS NULL
          AND (n.title LIKE '%' || :query || '%' OR n.content LIKE '%' || :query || '%' OR n.tags LIKE '%' || :query || '%')
        ORDER BY n.updatedAt DESC
        LIMIT 50
    """)
    suspend fun searchAllNotes(query: String): List<NoteEntity>

    /** Clears `isSynced` and moves `updatedAt` — see the same pair on TodoDao. */
    @Query("UPDATE notes SET sortOrder = :sortOrder, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, updatedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET isPinned = :isPinned, updatedAt = :updatedAt, isSynced = 0 WHERE id = :id")
    suspend fun updateIsPinned(id: Long, isPinned: Boolean, updatedAt: Long = System.currentTimeMillis())

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

    /**
     * Soft-deleted notes whose Drive file has not been stamped deleted yet.
     *
     * The push re-stamped every deleted note on every fifteen-minute sync, because nothing
     * recorded that the stamp had landed — ninety days of deletions meant that many pointless
     * Drive writes an hour, competing for the sync's own time budget. `isSynced` is the flag:
     * `softDeleteNote` clears it, and the stamp sets it, so each deletion is published once.
     * Nothing else reads `isSynced` on a deleted row — `getUnsyncedNotes` and `getSyncedNoteIds`
     * both require `deletedAt IS NULL`.
     */
    @Query("SELECT * FROM notes WHERE deletedAt IS NOT NULL AND isSynced = 0")
    suspend fun getUnstampedDeletedNotes(): List<NoteEntity>

    /** Recently Deleted with the vault closed — see TodoDao.getDeletedNonVaultTodos. */
    @Query("""
        SELECT n.* FROM notes n
        INNER JOIN buckets b ON n.bucketId = b.id
        WHERE b.isVault = 0 AND n.deletedAt IS NOT NULL
        ORDER BY n.deletedAt DESC
    """)
    fun getDeletedNonVaultNotes(): Flow<List<NoteEntity>>

    // Includes soft-deleted rows — used by sync to avoid resurrecting deleted notes
    /** Every note, deleted ones included — the edit-pull needs to skip locally-deleted rows. */
    @Query("SELECT * FROM notes")
    suspend fun getAllNotesIncludingDeleted(): List<NoteEntity>

    @Query("SELECT id FROM notes")
    suspend fun getAllNoteIds(): List<Long>

    @Query("UPDATE notes SET isSynced = 0 WHERE deletedAt IS NULL")
    suspend fun markAllNotesUnsynced()

    /** Live notes the app believes are already on Drive — used to detect ones that are not. */
    @Query("SELECT id FROM notes WHERE isSynced = 1 AND deletedAt IS NULL")
    suspend fun getSyncedNoteIds(): List<Long>

    @Query("UPDATE notes SET isSynced = 0 WHERE id IN (:ids)")
    suspend fun markNotesUnsynced(ids: List<Long>)

    /**
     * Notes with a reminder still in the future — used to rearm the alarms after a reboot.
     *
     * AlarmManager clears every alarm on reboot and only `BootReceiver` rebuilds them. It knew
     * about to-dos and not about notes, so a reminder set on a note simply never fired again.
     */
    @Query("SELECT * FROM notes WHERE reminderAt IS NOT NULL AND reminderAt > :now AND deletedAt IS NULL")
    suspend fun getActiveReminders(now: Long = System.currentTimeMillis()): List<NoteEntity>

    /** Live (non-deleted) notes in a bucket — used to warn before bucket deletion. */
    @Query("SELECT COUNT(*) FROM notes WHERE bucketId = :bucketId AND deletedAt IS NULL")
    suspend fun countInBucket(bucketId: Long): Int

    /** Live (non-deleted) note ids in a bucket — used to soft-delete a bucket's contents. */
    @Query("SELECT id FROM notes WHERE bucketId = :bucketId AND deletedAt IS NULL")
    suspend fun getIdsInBucket(bucketId: Long): List<Long>

    /**
     * Reassigns EVERY note off [fromBucketId] — including soft-deleted ones sitting in
     * Recently Deleted. Deliberately not filtered on deletedAt: any row left pointing at
     * the bucket would be destroyed by the FK CASCADE when the bucket row is removed.
     */
    @Query("UPDATE notes SET bucketId = :toBucketId, isSynced = 0 WHERE bucketId = :fromBucketId")
    suspend fun moveAllToBucket(fromBucketId: Long, toBucketId: Long)

    @Query("UPDATE notes SET deletedAt = :deletedAt, isSynced = 0 WHERE id = :id")
    suspend fun softDeleteNote(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE notes SET deletedAt = NULL, isSynced = 0 WHERE id = :id")
    suspend fun restoreNoteFromBin(id: Long)

    @Query("DELETE FROM notes WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedNotes(cutoffMs: Long)
}
