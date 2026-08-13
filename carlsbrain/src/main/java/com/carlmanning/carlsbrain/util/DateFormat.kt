package com.carlmanning.carlsbrain.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Formats an epoch millis timestamp as a short, context-aware date + time string. */
fun formatSmartDateTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val target = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val targetDate = target.toLocalDate()
    val daysApart = ChronoUnit.DAYS.between(today, targetDate)

    val pattern = when {
        targetDate == today -> "h:mm a"
        daysApart in -7..7 -> "EEE h:mm a"
        targetDate.year == today.year -> "d MMM"
        else -> "d MMM yyyy"
    }
    return target.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/**
 * Like [formatSmartDateTime] but never drops the time, for due dates and reminders where the
 * time of day is the point. Beyond a week out this appends the time to the date rather than
 * showing the date alone.
 */
fun formatSmartDueDateTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val target = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val targetDate = target.toLocalDate()
    val daysApart = ChronoUnit.DAYS.between(today, targetDate)

    val pattern = when {
        targetDate == today -> "h:mm a"
        daysApart in -7..7 -> "EEE h:mm a"
        targetDate.year == today.year -> "d MMM, h:mm a"
        else -> "d MMM yyyy, h:mm a"
    }
    return target.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** Formats an epoch millis timestamp as a short, context-aware date string (no time). */
fun formatSmartDate(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val target = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today: LocalDate = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val targetDate = target.toLocalDate()
    val daysApart = ChronoUnit.DAYS.between(today, targetDate)

    return when {
        targetDate == today -> "Today"
        daysApart in -7..7 -> target.format(DateTimeFormatter.ofPattern("EEE", locale))
        targetDate.year == today.year -> target.format(DateTimeFormatter.ofPattern("d MMM", locale))
        else -> target.format(DateTimeFormatter.ofPattern("d MMM yyyy", locale))
    }
}
