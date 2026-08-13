package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface RecentlyViewedDao {

    @Query("SELECT * FROM recently_viewed ORDER BY viewedAt DESC LIMIT :limit")
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

    @Query("DELETE FROM recently_viewed WHERE id NOT IN (SELECT id FROM recently_viewed ORDER BY viewedAt DESC LIMIT 30)")
    suspend fun trim()

    @Query("DELETE FROM recently_viewed")
    suspend fun clear()
}
