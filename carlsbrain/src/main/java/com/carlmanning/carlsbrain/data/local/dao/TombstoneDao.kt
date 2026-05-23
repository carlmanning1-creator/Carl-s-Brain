package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity

@Dao
interface TombstoneDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(tombstone: TombstoneEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(tombstones: List<TombstoneEntity>)

    @Query("SELECT COUNT(*) > 0 FROM tombstones WHERE id = :id AND type = :type")
    suspend fun isTombstoned(id: Long, type: String): Boolean

    // Purge tombstones older than the given cutoff to prevent unbounded growth
    @Query("DELETE FROM tombstones WHERE deletedAt < :cutoffMs")
    suspend fun purgeOld(cutoffMs: Long)
}
