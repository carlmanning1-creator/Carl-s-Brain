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

    @Query("SELECT * FROM journal_templates WHERE deletedAt IS NULL ORDER BY sortOrder ASC, name ASC")
    fun getTemplates(): Flow<List<JournalTemplateEntity>>

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
