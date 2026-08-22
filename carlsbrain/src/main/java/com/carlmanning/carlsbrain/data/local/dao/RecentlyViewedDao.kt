package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyViewedDao {

    /**
     * The recently-viewed strip, minus anything that is no longer live.
     *
     * This table is a snapshot — it keeps its own copy of the title and nothing ever joined
     * back to the source row — so a to-do Carl ticked off, or a note he deleted, sat in the
     * strip indefinitely with a stale title, offering him work that was already finished.
     * That is the opposite of what the strip is for.
     *
     * The filter is here, in SQL, rather than in the Dashboard: a screen that has to remember
     * to check is exactly how this project has leaked before, and doing it in the query also
     * fixes history for free — rows recorded before this existed vanish on the next read
     * without any cleanup pass.
     *
     * The `LIMIT` deliberately applies *after* the filter, so ten viewed-and-since-completed
     * to-dos cannot empty the strip.
     *
     * A row whose source has been hard-purged is dropped too: `NOT EXISTS` is false when there
     * is nothing to match, so a purged item disappears rather than lingering as a dead title.
     * MEETING and EVENT rows are checked the same way, except that events live on Google's
     * calendar rather than in a table here, so there is nothing local to test and they pass
     * through — an event that has been cancelled elsewhere is the one case this cannot catch.
     */
    @Query("""
        SELECT rv.* FROM recently_viewed rv
        WHERE
            (rv.itemType = 'TODO' AND EXISTS (
                SELECT 1 FROM todos t
                WHERE t.id = rv.itemId
                  AND t.isDone = 0
                  AND t.deletedAt IS NULL
                  AND t.isArchived = 0
            ))
            OR (rv.itemType = 'NOTE' AND EXISTS (
                SELECT 1 FROM notes n WHERE n.id = rv.itemId AND n.deletedAt IS NULL
            ))
            OR (rv.itemType = 'MEETING' AND EXISTS (
                SELECT 1 FROM meetings m WHERE m.id = rv.itemId AND m.deletedAt IS NULL
            ))
            OR rv.itemType NOT IN ('TODO', 'NOTE', 'MEETING')
        ORDER BY rv.viewedAt DESC
        LIMIT :limit
    """)
    fun getRecent(limit: Int = 10): Flow<List<RecentlyViewedEntity>>

    @Query("DELETE FROM recently_viewed WHERE itemType = :itemType AND itemId = :itemId")
    suspend fun deleteFor(itemType: String, itemId: Long)

    @Insert
    suspend fun insert(entry: RecentlyViewedEntity)

    // Record a view: remove any prior entry for the same item, insert fresh, then trim to 30 rows
    @Transaction
    suspend fun recordView(entry: RecentlyViewedEntity) {
        deleteFor(entry.itemType, entry.itemId)
        insert(entry)
        trim()
    }

    /**
     * Caps the table. Raised from 30 to 50 when [getRecent] started filtering out completed and
     * deleted items: rows that no longer show still occupy the cap, so a run of ticked-off
     * to-dos could otherwise leave the strip half empty.
     */
    @Query("DELETE FROM recently_viewed WHERE id NOT IN (SELECT id FROM recently_viewed ORDER BY viewedAt DESC LIMIT 50)")
    suspend fun trim()

    @Query("DELETE FROM recently_viewed")
    suspend fun clear()
}
