package com.carlmanning.carlsbrain.domain.model

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class CalendarEvent(
    val id: String,
    val title: String,
    val startMs: Long,
    val endMs: Long,
    val isAllDay: Boolean,
    val location: String? = null,
    val colorHex: String? = null,
    val calendarName: String? = null,
    /**
     * Whether this came from Carl's primary calendar.
     *
     * Only the to-do importer reads it. Every other consumer wants the full diary across
     * shared, SES and family calendars — but minting a to-do from a birthday, a public
     * holiday or someone else's shared event is noise, and the briefing prompt says as much
     * twenty lines from where the importer used to file them as work.
     *
     * Not persisted on [CalendarEventEntity]: the cache feeds display, and an event read back
     * from it defaults to false, which is the safe direction for something that creates rows.
     */
    val isPrimary: Boolean = false
) {
    fun formattedTime(): String {
        if (isAllDay) return "All day"
        val fmt = DateTimeFormatter.ofPattern("HH:mm")
        val zone = ZoneId.systemDefault()
        val start = Instant.ofEpochMilli(startMs).atZone(zone).format(fmt)
        val end = Instant.ofEpochMilli(endMs).atZone(zone).format(fmt)
        return "$start – $end"
    }
}
