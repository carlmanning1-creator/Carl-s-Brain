package com.carlmanning.carlsbrain.util

import android.content.Context
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Whether the app renders times on a 24-hour clock.
 *
 * Defaults to true because every time picker in the app is hardcoded `is24Hour = true`, so
 * 24-hour is the format Carl *enters* times in. [initDateFormatting] refreshes this from the
 * platform ("Use 24-hour format" in system settings) so entry and display always agree.
 *
 * Kept as a module-level flag rather than a parameter so the format functions stay callable
 * with no Context from any composable.
 */
@Volatile
private var use24HourClock: Boolean = true

/**
 * Syncs the display clock with the device's "Use 24-hour format" setting.
 * Call once from `CarlsBrainApp.onCreate()` (and it is safe to call again after a config change).
 */
fun initDateFormatting(context: Context) {
    use24HourClock = android.text.format.DateFormat.is24HourFormat(context)
}

/** The time-of-day pattern matching the platform clock setting. */
private fun timePattern(): String = if (use24HourClock) "HH:mm" else "h:mm a"

/** Formats an epoch millis timestamp as a short, context-aware date + time string. */
fun formatSmartDateTime(epochMillis: Long, now: Long = System.currentTimeMillis()): String {
    if (epochMillis <= 0L) return ""
    val zone = ZoneId.systemDefault()
    val locale = Locale.getDefault()
    val target = Instant.ofEpochMilli(epochMillis).atZone(zone)
    val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    val targetDate = target.toLocalDate()
    val daysApart = ChronoUnit.DAYS.between(today, targetDate)
    val time = timePattern()

    val pattern = when (daysApart) {
        0L -> time
        1L -> "'Tomorrow' $time"
        -1L -> "'Yesterday' $time"
        in 2L..6L -> "EEE $time"
        in -6L..-2L -> "'Last' EEE $time"
        else -> if (targetDate.year == today.year) "d MMM" else "d MMM yyyy"
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
    val time = timePattern()

    val pattern = when (daysApart) {
        0L -> time
        1L -> "'Tomorrow' $time"
        -1L -> "'Yesterday' $time"
        in 2L..6L -> "EEE $time"
        in -6L..-2L -> "'Last' EEE $time"
        else -> if (targetDate.year == today.year) "d MMM, $time" else "d MMM yyyy, $time"
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

    val pattern = when (daysApart) {
        0L -> "'Today'"
        1L -> "'Tomorrow'"
        -1L -> "'Yesterday'"
        in 2L..6L -> "EEE"
        in -6L..-2L -> "'Last' EEE"
        else -> if (targetDate.year == today.year) "d MMM" else "d MMM yyyy"
    }
    return target.format(DateTimeFormatter.ofPattern(pattern, locale))
}

/** Convenience overload for [formatSmartDate] when working with a [LocalDate]. */
fun formatSmartDate(date: LocalDate, now: Long = System.currentTimeMillis()): String =
    formatSmartDate(date.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli(), now)
