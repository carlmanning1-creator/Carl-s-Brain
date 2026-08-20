package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalDao {

    /**
     * ## Drafts
     * Carl asked for drafts to appear in the entry list marked DRAFT, so they are real rows
     * here rather than isolated storage. That means every query below has to decide about them
     * deliberately, and the decision is made **in SQL** for the same reason `isPrivate` is: a
     * screen or worker that forgets to filter cannot then leak one. Only [getVisibleEntries]
     * and [getAllEntries] — the two that feed the Journal list — include drafts. Claude,
     * search, the private count and the Drive push all exclude them.
     */

    /**
     * Entries visible while the vault is closed — private ones are excluded in SQL rather than
     * filtered in the UI, so a screen that forgets to filter cannot leak them.
     *
     * Includes drafts: this is the list Carl wants to see them in.
     */
    @Query("""
        SELECT * FROM journal_entries
        WHERE isPrivate = 0 AND deletedAt IS NULL
        ORDER BY createdAt DESC
    """)
    fun getVisibleEntries(): Flow<List<JournalEntryEntity>>

    /** Everything, for when the vault is open. */
    @Query("SELECT * FROM journal_entries WHERE deletedAt IS NULL ORDER BY createdAt DESC")
    fun getAllEntries(): Flow<List<JournalEntryEntity>>

    /** Count of entries the vault is currently hiding — a number only, never the content. */
    @Query("SELECT COUNT(*) FROM journal_entries WHERE isPrivate = 1 AND isDraft = 0 AND deletedAt IS NULL")
    fun getPrivateCount(): Flow<Int>

    @Query("SELECT * FROM journal_entries WHERE id = :id")
    suspend fun getEntryById(id: Long): JournalEntryEntity?

    /**
     * Non-private entries since [since], newest first — what Claude is allowed to read.
     *
     * Private entries are excluded here rather than by the caller, so no future call site can
     * accidentally hand them to an API. Deliberately not a Flow: Claude reads a snapshot.
     */
    @Query("""
        SELECT * FROM journal_entries
        WHERE isPrivate = 0 AND isDraft = 0 AND deletedAt IS NULL AND createdAt >= :since
        ORDER BY createdAt DESC
        LIMIT :limit
    """)
    suspend fun getEntriesForClaude(since: Long, limit: Int = 30): List<JournalEntryEntity>

    /** Most recent entry time, for the "you have not written in a while" nudge. */
    @Query("SELECT MAX(createdAt) FROM journal_entries WHERE isDraft = 0 AND deletedAt IS NULL")
    fun getLastEntryTime(): Flow<Long?>

    @Query("""
        SELECT * FROM journal_entries
        WHERE isDraft = 0 AND deletedAt IS NULL AND content LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT 50
    """)
    suspend fun searchAll(query: String): List<JournalEntryEntity>

    /** Search that respects a closed vault — used by the global search screen. */
    @Query("""
        SELECT * FROM journal_entries
        WHERE isPrivate = 0 AND isDraft = 0 AND deletedAt IS NULL AND content LIKE '%' || :query || '%'
        ORDER BY createdAt DESC
        LIMIT 50
    """)
    suspend fun searchVisible(query: String): List<JournalEntryEntity>

    @Insert
    suspend fun insertEntry(entry: JournalEntryEntity): Long

    @Update
    suspend fun updateEntry(entry: JournalEntryEntity)

    @Delete
    suspend fun deleteEntry(entry: JournalEntryEntity)

    @Query("UPDATE journal_entries SET deletedAt = :deletedAt, isSynced = 0 WHERE id = :id")
    suspend fun softDeleteEntry(id: Long, deletedAt: Long = System.currentTimeMillis())

    @Query("UPDATE journal_entries SET deletedAt = NULL, isSynced = 0 WHERE id = :id")
    suspend fun restoreEntry(id: Long)

    @Query("SELECT * FROM journal_entries WHERE deletedAt IS NOT NULL ORDER BY deletedAt DESC")
    fun getDeletedEntries(): Flow<List<JournalEntryEntity>>

    @Query("DELETE FROM journal_entries WHERE deletedAt IS NOT NULL AND deletedAt < :cutoffMs")
    suspend fun purgeOldDeletedEntries(cutoffMs: Long)

    // ── Sync ────────────────────────────────────────────────────────────

    /** Drafts are never pushed — an unfinished entry should not reach Drive or the web app. */
    @Query("SELECT * FROM journal_entries WHERE isSynced = 0 AND isDraft = 0 AND deletedAt IS NULL")
    suspend fun getUnsyncedEntries(): List<JournalEntryEntity>

    @Query("UPDATE journal_entries SET isSynced = 1 WHERE id = :id")
    suspend fun markSynced(id: Long)

    /** Ids the app believes are on Drive — used to detect ones that are not. */
    @Query("SELECT id FROM journal_entries WHERE isSynced = 1 AND isDraft = 0 AND deletedAt IS NULL")
    suspend fun getSyncedIds(): List<Long>

    @Query("UPDATE journal_entries SET isSynced = 0 WHERE id IN (:ids)")
    suspend fun markUnsynced(ids: List<Long>)

    /** Includes soft-deleted rows, so a pull never resurrects a deleted entry. */
    @Query("SELECT id FROM journal_entries")
    suspend fun getAllIds(): List<Long>

    /** Every entry, deleted and draft included — the edit-pull decides what to skip itself. */
    @Query("SELECT * FROM journal_entries")
    suspend fun getAllEntriesIncludingDeleted(): List<JournalEntryEntity>

    // ── Drafts ──────────────────────────────────────────────────────────

    /**
     * The unfinished entry for [templateId], if there is one.
     *
     * At most one draft per template: starting the training template twice should continue
     * what Carl already wrote, not quietly accumulate a pile of half-entries he has to sort out.
     */
    @Query("""
        SELECT * FROM journal_entries
        WHERE isDraft = 1 AND deletedAt IS NULL
          AND ((:templateId IS NULL AND templateId IS NULL) OR templateId = :templateId)
        ORDER BY updatedAt DESC LIMIT 1
    """)
    suspend fun getDraftForTemplate(templateId: Long?): JournalEntryEntity?
}
