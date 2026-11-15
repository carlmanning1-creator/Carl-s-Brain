package com.carlmanning.carlsbrain.data.today

import android.content.Context
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.domain.today.NextKind
import com.carlmanning.carlsbrain.domain.today.NextThingRanker
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** One ranked to-do, ready to draw. */
data class TodayItem(val id: Long, val title: String, val kind: NextKind, val meta: String)

/**
 * Everything a Today surface draws, read once.
 *
 * The widget, the pinned notification and the check-ins all render from this, so they cannot
 * disagree about what is next, how many are overdue, or how many were done.
 */
data class TodaySnapshot(
    /** Ranked; the first is "the next thing". Empty means all clear. */
    val items: List<TodayItem>,
    val overdueCount: Int,
    val doneToday: Int,
    /** "Team check-in at 2:30 pm", or null when nothing is left on today's calendar. */
    val nextEvent: String?,
    /** One sentence of *today's* briefing, or null. */
    val briefingLine: String?,
    val undo: TodayState.Undo?,
    val busy: Boolean,
    /** Open to-dos with no date and no urgency — things these surfaces cannot otherwise show. */
    val toSort: Int = 0
)

object TodayRepository {

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)

    /**
     * Vault-safe by construction: the to-dos come from a non-vault SQL query, the calendar has
     * no vault, and the cached briefing is only ever written with the vault closed. Never
     * calls Claude and never touches the network — it runs inside widget renders and
     * broadcast receivers.
     */
    suspend fun load(context: Context, now: Long = System.currentTimeMillis()): TodaySnapshot {
        val db = AppDatabase.getInstance(context)
        val prefs = CarlsBrainApp.userPreferences
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

        val ranked = NextThingRanker.rank(
            todos = db.todoDao().getTodayCandidatesNonVault(dayEnd),
            now = now,
            dayEnd = dayEnd,
            snoozedUntil = TodayState.snoozes(context, now)
        )
        val items = ranked.map {
            TodayItem(it.todo.id, it.todo.title, it.kind, NextThingRanker.meta(it, now, zone))
        }

        val nextEvent = runCatching {
            db.calendarEventDao().getEventsForDay(dayStart, dayEnd)
                .filter { !it.isAllDay && it.endMs > now }
                .minByOrNull { it.startMs }
                ?.let { event ->
                    if (event.startMs <= now) {
                        "${event.title}, now"
                    } else {
                        val at = TIME.format(Instant.ofEpochMilli(event.startMs).atZone(zone))
                            .lowercase(Locale.getDefault())
                        "${event.title} at $at"
                    }
                }
        }.getOrNull()

        // Only today's. The old widget stamped the briefing with its age; this layout has no
        // room for that, and yesterday's "light day, two overdue" shown as though it were
        // current is the app asserting something it has not checked.
        val briefingLine = runCatching {
            val text = prefs.cachedBriefing.first()
            val at = prefs.cachedBriefingAt.first()
            if (text.isBlank() || at < dayStart) null else firstSentence(text)
        }.getOrNull()

        return TodaySnapshot(
            items = items,
            overdueCount = items.count { it.kind == NextKind.OVERDUE },
            doneToday = runCatching { db.todoDao().countCompletedSinceNonVault(dayStart) }
                .getOrDefault(0),
            nextEvent = nextEvent,
            briefingLine = briefingLine,
            undo = TodayState.undo(context, now),
            busy = runCatching { prefs.busyModeActive.first() }.getOrDefault(false),
            toSort = runCatching { db.todoDao().countToSortNonVault() }.getOrDefault(0)
        )
    }

    /** Up to and including the first full stop, question mark or exclamation mark. */
    internal fun firstSentence(text: String): String {
        val flat = text.trim().replace('\n', ' ')
        for (i in flat.indices) {
            val c = flat[i]
            val endsSentence = c == '.' || c == '!' || c == '?'
            // "3.30 pm" and "e.g." are not sentence ends: require a space or the end after it.
            if (endsSentence && (i == flat.lastIndex || flat[i + 1] == ' ')) {
                return flat.substring(0, i + 1)
            }
        }
        return flat
    }
}
