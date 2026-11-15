package com.carlmanning.carlsbrain.domain.capture

import com.carlmanning.carlsbrain.domain.DueDate
import com.carlmanning.carlsbrain.domain.model.Priority
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * A to-do as Claude describes one — in a `[TODO: …]` marker (Hey Brain, Chat) or in the capture
 * overlay's JSON — turned into the values that get saved.
 *
 * One parser for all three, because they had drifted into the same gap separately: each took a
 * title, a bucket and a priority and nothing else. A to-do spoken as "call the dentist tomorrow
 * at three" was saved with no date at all, so it never reached the Today surfaces, and the
 * overlay would even ask "what time?" and then throw the answer away.
 *
 * Pure: no Android, no database. The callers look up the bucket and do the saving.
 *
 * The rule that matters most: **a date that cannot be read is dropped, never a reason to lose
 * the to-do.** Capture has to stay frictionless; a to-do with no date is a small loss, a to-do
 * that silently was not created is the failure this app exists to prevent.
 */
object TodoMarker {

    data class Parsed(
        val title: String,
        /** As Claude wrote it; blank when it gave none. The caller matches it to a real bucket. */
        val bucket: String,
        val priority: Priority,
        val dueDate: Long?,
        val reminderAt: Long?
    )

    /** The hour a "remind me on Friday" with no time fires. Carl's choice, November 2026. */
    const val DEFAULT_REMINDER_HOUR = 9

    /**
     * What every prompt says about the two optional fields, so the three cannot describe them
     * differently. The prompt must also carry the current date — see PromptContext.rightNow.
     */
    const val DATE_RULES =
        "due and remind are optional. Write each as a local date (2026-11-16) or a local date " +
            "and 24-hour time (2026-11-16T15:00), worked out from the current date given " +
            "above. Leave a field empty when Carl gave no date for it. due is when the to-do " +
            "must be done by: use it whenever he mentions a day or a time. remind sets an " +
            "alarm: use it ONLY when he asks to be reminded. Never invent a date he did not say."

    /**
     * The inside of a `[TODO: …]` marker: `title | bucket | priority | due | remind`.
     *
     * The last two are trailing and optional, so a three-field marker from before they existed
     * parses exactly as it always did.
     *
     * @return null when there is no title — the only thing a to-do cannot do without.
     */
    fun parse(body: String, zone: ZoneId = ZoneId.systemDefault()): Parsed? {
        val parts = body.split("|").map { it.trim() }
        return build(
            title = parts.getOrElse(0) { "" },
            bucket = parts.getOrElse(1) { "" },
            priority = parts.getOrElse(2) { "" },
            due = parts.getOrNull(3),
            remind = parts.getOrNull(4),
            zone = zone
        )
    }

    /** The same, from fields that arrived separately — the overlay's JSON. */
    fun build(
        title: String,
        bucket: String,
        priority: String,
        due: String?,
        remind: String?,
        zone: ZoneId = ZoneId.systemDefault()
    ): Parsed? {
        if (title.isBlank()) return null
        return Parsed(
            title = title.trim(),
            bucket = bucket.trim(),
            priority = runCatching { Priority.valueOf(priority.trim().uppercase(Locale.ROOT)) }
                .getOrDefault(Priority.NORMAL),
            dueDate = parseDue(due, zone),
            reminderAt = parseReminder(remind, zone)
        )
    }

    /** A date-only due follows the app's convention: the last moment of that day. */
    fun parseDue(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? =
        read(raw, zone) { day -> DueDate.endOfDay(day, zone) }

    /** A date-only reminder fires in the morning — an alarm at 11:59 pm helps nobody. */
    fun parseReminder(raw: String?, zone: ZoneId = ZoneId.systemDefault()): Long? =
        read(raw, zone) { day -> DueDate.atTime(day, DEFAULT_REMINDER_HOUR, 0, zone) }

    private fun read(raw: String?, zone: ZoneId, dateOnly: (LocalDate) -> Long): Long? {
        // Claude sometimes fills an empty field with a word, and sometimes uses a space where
        // ISO wants a T. Neither is worth losing the date over; anything else is dropped.
        val text = raw?.trim()?.trim('"', '\'')?.replace(' ', 'T').orEmpty()
        if (text.isEmpty()) return null
        runCatching { LocalDateTime.parse(text) }.getOrNull()?.let {
            return it.atZone(zone).toInstant().toEpochMilli()
        }
        runCatching { LocalDate.parse(text) }.getOrNull()?.let { return dateOnly(it) }
        return null
    }

    // ── Saying it back ────────────────────────────────────────────────────────

    /**
     * "today 3 pm", "tomorrow", "Friday 9:30 am", "20 November".
     *
     * Read aloud and shown in the confirmation, so Carl hears the date that was actually
     * saved. That is the safeguard against Claude misreading "next Friday": a wrong date
     * that is spoken is caught on the spot, a wrong date saved silently is found on the day.
     */
    fun describe(
        atMillis: Long,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String {
        val at = Instant.ofEpochMilli(atMillis).atZone(zone)
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val days = ChronoUnit.DAYS.between(today, at.toLocalDate())
        val day = when {
            days == 0L -> "today"
            days == 1L -> "tomorrow"
            days == -1L -> "yesterday"
            days in 2L..6L -> at.dayOfWeek.getDisplayName(TextStyle.FULL, Locale.ENGLISH)
            else -> at.format(DAY_MONTH)
        }
        if (!DueDate.hasExplicitTime(atMillis, zone)) return day
        val time = at.format(if (at.minute == 0) HOUR else HOUR_MINUTE).lowercase(Locale.ENGLISH)
        return "$day $time"
    }

    /** "due tomorrow 3 pm, reminder Friday 9 am" — blank when the to-do has neither. */
    fun describeDates(
        dueDate: Long?,
        reminderAt: Long?,
        now: Long = System.currentTimeMillis(),
        zone: ZoneId = ZoneId.systemDefault()
    ): String = listOfNotNull(
        dueDate?.let { "due " + describe(it, now, zone) },
        reminderAt?.let { "reminder " + describe(it, now, zone) }
    ).joinToString(", ")

    private val DAY_MONTH = DateTimeFormatter.ofPattern("d MMMM", Locale.ENGLISH)
    private val HOUR = DateTimeFormatter.ofPattern("h a", Locale.ENGLISH)
    private val HOUR_MINUTE = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
}
