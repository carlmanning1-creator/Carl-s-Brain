package com.carlmanning.carlsbrain.domain

import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Turning what Carl picked into the instant a to-do is actually due.
 *
 * ## The bug this exists to kill
 *
 * Material 3's `DatePicker` reports the selected day as **UTC midnight**, not local midnight.
 * Both to-do screens stored that value verbatim — the editor's "No time" branch even said
 * "Save date-only at midnight", which is what its author believed.
 *
 * In Dubbo that is UTC+10, so a to-do due "Monday" was stored as Monday 10:00 am local, and
 * went overdue at 10 am. Carl read that as a deliberate 10:00 default. It was not: nothing in
 * the app ever chose a time, and on the other side of the world the same code would have dated
 * the to-do to the previous day.
 *
 * ## The rule
 *
 * A date with no time means **the end of that day**. Carl's own call: a day is a day, and a
 * to-do due Monday is not late until Monday is over. So date-only stores 23:59:59.999 local,
 * and nothing is overdue until after midnight.
 *
 * [END_OF_DAY_SENTINEL] is deliberately a real time rather than a separate "has time" column:
 * it needs no migration, it sorts correctly against timed to-dos on the same day, and it
 * degrades honestly if anything ignores it. [hasExplicitTime] is how the UI tells the two apart.
 */
object DueDate {

    /**
     * The local time-of-day a date-only to-do is stored at.
     *
     * One millisecond before midnight, so it belongs to the day Carl chose rather than the next
     * one, and so `dueDate < now` — the overdue test used everywhere — only becomes true once
     * that day is genuinely over.
     */
    val END_OF_DAY_SENTINEL: LocalTime = LocalTime.of(23, 59, 59, 999_000_000)

    /**
     * Reads the calendar day out of a Material 3 date picker value.
     *
     * The picker's value is midnight **UTC** on the chosen day, so the day has to be read in
     * UTC. Reading it in the device zone gives the wrong date wherever the offset is negative.
     */
    fun dayFromPicker(pickerMillis: Long): LocalDate =
        Instant.ofEpochMilli(pickerMillis).atZone(ZoneOffset.UTC).toLocalDate()

    /** The instant a date-only to-do falls due: the last moment of that local day. */
    fun endOfDay(day: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Long =
        day.atTime(END_OF_DAY_SENTINEL).atZone(zone).toInstant().toEpochMilli()

    /** The instant a to-do with a chosen time falls due. */
    fun atTime(
        day: LocalDate,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = day.atTime(hour, minute).atZone(zone).toInstant().toEpochMilli()

    /** Convenience: a picker value straight to a date-only due instant. */
    fun endOfDayFromPicker(pickerMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Long =
        endOfDay(dayFromPicker(pickerMillis), zone)

    /** Convenience: a picker value plus a chosen time. */
    fun atTimeFromPicker(
        pickerMillis: Long,
        hour: Int,
        minute: Int,
        zone: ZoneId = ZoneId.systemDefault()
    ): Long = atTime(dayFromPicker(pickerMillis), hour, minute, zone)

    /**
     * Whether Carl chose a time, or only a day.
     *
     * Used to decide whether to show a time alongside the date, and to seed the time picker
     * when he goes back to edit: a date-only to-do should not open its picker at 11:59 pm.
     */
    fun hasExplicitTime(dueMillis: Long, zone: ZoneId = ZoneId.systemDefault()): Boolean =
        Instant.ofEpochMilli(dueMillis).atZone(zone).toLocalTime() != END_OF_DAY_SENTINEL

    /**
     * The hour and minute to open a time picker at.
     *
     * 9:00 for a date-only to-do — a plausible working time, and far better than the 23:59 the
     * sentinel would otherwise suggest.
     */
    fun pickerTime(dueMillis: Long?, zone: ZoneId = ZoneId.systemDefault()): Pair<Int, Int> {
        if (dueMillis == null || !hasExplicitTime(dueMillis, zone)) return 9 to 0
        val time = Instant.ofEpochMilli(dueMillis).atZone(zone).toLocalTime()
        return time.hour to time.minute
    }
}
