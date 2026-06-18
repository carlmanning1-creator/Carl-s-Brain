package com.carlmanning.carlsbrain.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.carlmanning.carlsbrain.data.local.entity.CalendarEventEntity

@Dao
interface CalendarEventDao {
    @Query("SELECT * FROM calendar_events ORDER BY startMs ASC")
    suspend fun getAllEventsOnce(): List<CalendarEventEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(events: List<CalendarEventEntity>)

    @Query("DELETE FROM calendar_events")
    suspend fun deleteAll()

    @Query("SELECT MAX(cachedAt) FROM calendar_events")
    suspend fun getLastCachedAt(): Long?

    @Query("SELECT * FROM calendar_events WHERE startMs >= :dayStart AND startMs < :dayEnd ORDER BY startMs ASC")
    suspend fun getEventsForDay(dayStart: Long, dayEnd: Long): List<CalendarEventEntity>
}
