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
}
