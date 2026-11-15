package com.carlmanning.carlsbrain.domain.today

import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.DueDate
import com.carlmanning.carlsbrain.domain.model.Priority
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Why a to-do is on the Today surfaces. Also decides the colour of its meta line. */
enum class NextKind { OVERDUE, URGENT, DUE_TODAY, DUE_TOMORROW }

data class RankedTodo(val todo: TodoEntity, val kind: NextKind)

/**
 * The one answer to "what is the next thing?", shared by the widget, the pinned notification
 * and the check-ins.
 *
 * It exists as a single pure function because three surfaces each deciding for themselves is
 * how they end up disagreeing — the widget naming one to-do and the notification another is
 * worse than either being slightly wrong. "Name one thing" is the point of all three.
 *
 * Order:
 *  1. Overdue — Urgent and High first, then the longest overdue. Not simply oldest-first:
 *     a months-old Someday item would otherwise own the top slot forever and become wallpaper
 *     (Carl's call, November 2026).
 *  2. Urgent and High that are not overdue.
 *  3. Due later today, earliest first.
 *
 * Anything else is not on these surfaces at all. An empty result is a real answer — "all
 * clear" — and callers must render it as one rather than reaching for something to show.
 *
 * Vault filtering is **not** done here. The caller passes rows from a non-vault SQL query;
 * a pure function handed a vault row could only filter it by trusting a flag someone else set.
 */
object NextThingRanker {

    /**
     * @param todos open to-dos from a non-vault query. Done, archived and deleted rows are
     *   dropped defensively, but the vault rule is the query's job.
     * @param dayEnd the first instant of tomorrow, local time.
     * @param snoozedUntil to-do id → the time it may reappear. Device-local; see TodayState.
     */
    fun rank(
        todos: List<TodoEntity>,
        now: Long,
        dayEnd: Long,
        snoozedUntil: Map<Long, Long> = emptyMap()
    ): List<RankedTodo> {
        val open = todos.filter { todo ->
            !todo.isDone && !todo.isArchived && todo.deletedAt == null &&
                (snoozedUntil[todo.id] ?: 0L) <= now
        }

        val overdue = open
            .filter { it.dueDate != null && it.dueDate < now }
            .sortedWith(
                compareBy<TodoEntity> { if (it.priority <= Priority.HIGH.rank) it.priority else Priority.NORMAL.rank }
                    .thenBy { it.dueDate }
                    .thenBy { it.id }
            )
        val overdueIds = overdue.map { it.id }.toSet()

        val important = open
            .filter { it.id !in overdueIds && it.priority <= Priority.HIGH.rank }
            .sortedWith(
                compareBy<TodoEntity> { it.priority }
                    // Undated sorts after dated: a date is a commitment, no date is a wish.
                    .thenBy { it.dueDate ?: Long.MAX_VALUE }
                    .thenBy { it.id }
            )
        val importantIds = important.map { it.id }.toSet()

        val dueToday = open
            .filter {
                it.id !in overdueIds && it.id !in importantIds &&
                    it.dueDate != null && it.dueDate >= now && it.dueDate < dayEnd
            }
            .sortedWith(compareBy<TodoEntity> { it.dueDate }.thenBy { it.id })

        return overdue.map { RankedTodo(it, NextKind.OVERDUE) } +
            important.map { RankedTodo(it, NextKind.URGENT) } +
            dueToday.map { RankedTodo(it, NextKind.DUE_TODAY) }
    }

    /** "Overdue 2 days", "Urgent", "Due today, 5:00 pm". */
    fun meta(item: RankedTodo, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
        val due = item.todo.dueDate
        return when (item.kind) {
            NextKind.OVERDUE -> {
                // Calendar days, not 24-hour blocks: due yesterday evening is "1 day" this
                // morning, which is how anyone would say it.
                val days = if (due == null) 0L else ChronoUnit.DAYS.between(
                    Instant.ofEpochMilli(due).atZone(zone).toLocalDate(),
                    Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
                )
                when {
                    days <= 0L -> "Overdue today"
                    days == 1L -> "Overdue 1 day"
                    else -> "Overdue $days days"
                }
            }
            NextKind.URGENT ->
                if (item.todo.priority == Priority.URGENT.rank) "Urgent" else "High priority"
            NextKind.DUE_TODAY ->
                // A date-only due is stored at end of day; saying "11:59 pm" would invent a
                // deadline Carl never set.
                if (due != null && DueDate.hasExplicitTime(due, zone)) {
                    "Due today, " + TIME.format(Instant.ofEpochMilli(due).atZone(zone))
                        .lowercase(Locale.getDefault())
                } else {
                    "Due today"
                }
            NextKind.DUE_TOMORROW ->
                if (due != null && DueDate.hasExplicitTime(due, zone)) {
                    "Tomorrow, " + TIME.format(Instant.ofEpochMilli(due).atZone(zone))
                        .lowercase(Locale.getDefault())
                } else {
                    "Tomorrow"
                }
        }
    }

    /**
     * Tomorrow's to-dos, earliest first — shown on the widget after everything for today.
     *
     * Kept apart from [rank] on purpose: the notifications and check-ins answer "what about
     * today", and an item due tomorrow must never become their "next thing". Urgent and High
     * are left out because [rank] already lists them, whatever their date.
     */
    fun tomorrow(
        todos: List<TodoEntity>,
        now: Long,
        dayEnd: Long,
        tomorrowEnd: Long,
        snoozedUntil: Map<Long, Long> = emptyMap()
    ): List<RankedTodo> = todos
        .filter { todo ->
            !todo.isDone && !todo.isArchived && todo.deletedAt == null &&
                (snoozedUntil[todo.id] ?: 0L) <= now &&
                todo.priority > Priority.HIGH.rank &&
                todo.dueDate != null && todo.dueDate >= dayEnd && todo.dueDate < tomorrowEnd
        }
        .sortedWith(compareBy<TodoEntity> { it.dueDate }.thenBy { it.id })
        .map { RankedTodo(it, NextKind.DUE_TOMORROW) }

    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH)
}
