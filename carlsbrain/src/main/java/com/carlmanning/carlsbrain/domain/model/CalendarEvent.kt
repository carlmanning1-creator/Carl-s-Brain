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
    val location: String? = null
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
