package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.carlmanning.carlsbrain.domain.model.CalendarEvent

@Entity(tableName = "calendar_events")
data class CalendarEventEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val isAllDay: Boolean,
    val location: String?,
    val colorHex: String?,
    val calendarName: String?,
    val cachedAt: Long = System.currentTimeMillis()
) {
    fun toDomain() = CalendarEvent(
        id = id,
        title = title,
        startMs = startMs,
        endMs = endMs,
        isAllDay = isAllDay,
        location = location,
        colorHex = colorHex,
        calendarName = calendarName
    )
}

fun CalendarEvent.toEntity(cachedAt: Long = System.currentTimeMillis()) = CalendarEventEntity(
    id = id,
    title = title,
    startMs = startMs,
    endMs = endMs,
    isAllDay = isAllDay,
    location = location,
    colorHex = colorHex,
    calendarName = calendarName,
    cachedAt = cachedAt
)
