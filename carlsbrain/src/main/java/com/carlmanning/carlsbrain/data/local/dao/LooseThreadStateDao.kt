package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.carlmanning.carlsbrain.data.local.entity.LooseThreadStateEntity

@Dao
interface LooseThreadStateDao {

    @Query("SELECT * FROM loose_thread_state")
    suspend fun getAll(): List<LooseThreadStateEntity>

    /** Replace, not ignore: snoozing something already snoozed must push the date out. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: LooseThreadStateEntity)

    @Query("DELETE FROM loose_thread_state WHERE key = :key")
    suspend fun clear(key: String)

    /**
     * Drops state rows whose subject no longer exists.
     *
     * Nothing ever removed a row here, so the table only grew — and worse, the keys are
     * `KIND:refId` where refId is a row id. Ids are reused, so a later to-do landing on a purged
     * id would silently inherit the old "it's dead" dismissal and never surface again. Hiding
     * real work is the one failure the loose-thread feature cannot afford.
     *
     * The kinds match [com.carlmanning.carlsbrain.domain.loosethread.ThreadKind]. Written as
     * one statement per kind rather than a join, because the key is a composed string and the
     * four kinds live in four different tables.
     */
    @Query(
        """
        DELETE FROM loose_thread_state
        WHERE (key LIKE 'TODO:%'
                 AND CAST(SUBSTR(key, 6) AS INTEGER) NOT IN (SELECT id FROM todos))
           OR (key LIKE 'NOTE:%'
                 AND CAST(SUBSTR(key, 6) AS INTEGER) NOT IN (SELECT id FROM notes))
           OR (key LIKE 'MEETING:%'
                 AND CAST(SUBSTR(key, 9) AS INTEGER) NOT IN (SELECT id FROM meetings))
           OR (key LIKE 'JOURNAL_DRAFT:%'
                 AND CAST(SUBSTR(key, 15) AS INTEGER) NOT IN (SELECT id FROM journal_entries))
        """
    )
    suspend fun purgeOrphans()
}
