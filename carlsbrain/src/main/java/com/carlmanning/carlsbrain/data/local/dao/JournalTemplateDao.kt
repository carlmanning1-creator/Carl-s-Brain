package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.carlmanning.carlsbrain.data.local.entity.JournalOptionListEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface JournalTemplateDao {

    /** Every template — only ever collected with the vault open. */
    @Query("SELECT * FROM journal_templates WHERE deletedAt IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getTemplates(): Flow<List<JournalTemplateEntity>>

    /**
     * Templates visible while the vault is closed.
     *
     * This pair did not exist, so a private-by-default template, or one whose default bucket is
     * a vault bucket, had its *name* on the Journal chip row and in the manager with the vault
     * shut — and the name is the sensitive part, which is exactly why the web app withholds
     * these same templates. The journal reminder already refuses to announce such a name.
     *
     * Both halves of the rule, as everywhere else in the journal: the template's own privacy
     * default and its bucket. NULL bucket is kept — unfiled is the ordinary case.
     */
    @Query("""
        SELECT * FROM journal_templates
        WHERE deletedAt IS NULL AND isPrivateByDefault = 0
          AND (bucketId IS NULL OR bucketId NOT IN (SELECT id FROM buckets WHERE isVault = 1))
        ORDER BY sortOrder ASC, name ASC
    """)
    fun getVisibleTemplates(): Flow<List<JournalTemplateEntity>>

    @Query("SELECT * FROM journal_templates WHERE id = :id")
    suspend fun getTemplateById(id: Long): JournalTemplateEntity?

    /**
     * Includes soft-deleted rows on purpose.
     *
     * The seeder uses this to distinguish "Carl has never had this template" from "Carl had it
     * and deleted it". Without the distinction, a template he threw away would reappear on
     * every launch.
     */
    @Query("SELECT * FROM journal_templates WHERE builtInKey = :key LIMIT 1")
    suspend fun getByBuiltInKey(key: String): JournalTemplateEntity?

    @Insert
    suspend fun insertTemplate(template: JournalTemplateEntity): Long

    @Update
    suspend fun updateTemplate(template: JournalTemplateEntity)

    @Query("UPDATE journal_templates SET deletedAt = :at WHERE id = :id")
    suspend fun softDeleteTemplate(id: Long, at: Long = System.currentTimeMillis())

    @Query("SELECT * FROM journal_templates")
    suspend fun getAllTemplatesIncludingDeleted(): List<JournalTemplateEntity>

    /**
     * Reassigns every template off a bucket being deleted, soft-deleted ones included.
     *
     * A template's default bucket is what a private-by-default template relies on to stay
     * hidden, and it carries no foreign key — so a template left pointing at a dead id would
     * start filing new entries into a bucket that does not exist.
     */
    @Query("UPDATE journal_templates SET bucketId = :toBucketId WHERE bucketId = :fromBucketId")
    suspend fun moveAllToBucket(fromBucketId: Long, toBucketId: Long)

    // ── Option lists ─────────────────────────────────────────────────────────

    @Query("SELECT * FROM journal_option_lists ORDER BY name ASC")
    fun getOptionLists(): Flow<List<JournalOptionListEntity>>

    @Query("SELECT * FROM journal_option_lists")
    suspend fun getOptionListsOnce(): List<JournalOptionListEntity>

    @Query("SELECT * FROM journal_option_lists WHERE id = :id")
    suspend fun getOptionListById(id: Long): JournalOptionListEntity?

    @Query("SELECT * FROM journal_option_lists WHERE builtInKey = :key LIMIT 1")
    suspend fun getOptionListByKey(key: String): JournalOptionListEntity?

    @Insert
    suspend fun insertOptionList(list: JournalOptionListEntity): Long

    @Update
    suspend fun updateOptionList(list: JournalOptionListEntity)
}
