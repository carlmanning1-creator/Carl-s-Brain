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

    /**
     * Swaps the whole cache in one transaction.
     *
     * The delete and the insert were two separate calls, so a crash or a cancellation between
     * them left no cached calendar at all — the offline Dashboard showing an empty day, which
     * is indistinguishable from a genuinely clear one.
     */
    @androidx.room.Transaction
    suspend fun replaceAll(events: List<CalendarEventEntity>) {
        deleteAll()
        insertAll(events)
    }

    @Query("SELECT MAX(cachedAt) FROM calendar_events")
    suspend fun getLastCachedAt(): Long?

    @Query("SELECT * FROM calendar_events WHERE startMs >= :dayStart AND startMs < :dayEnd ORDER BY startMs ASC")
    suspend fun getEventsForDay(dayStart: Long, dayEnd: Long): List<CalendarEventEntity>
}
