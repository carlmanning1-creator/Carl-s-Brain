package com.carlmanning.carlsbrain.domain.today

import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * The order of "the next thing".
 *
 * Three surfaces render this one list, so a mistake here is wrong everywhere at once — and
 * wrong in the one place Carl has been told he can trust without thinking.
 *
 * Sydney throughout, so the assertions do not depend on the machine running them.
 */
class NextThingRankerTest {

    private val zone: ZoneId = ZoneId.of("Australia/Sydney")
    private val today: LocalDate = LocalDate.of(2026, 11, 16)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day, java.time.LocalTime.of(hour, minute)).atZone(zone).toInstant().toEpochMilli()

    private val now = at(today, 10)
    private val dayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

    private fun todo(
        id: Long,
        priority: Priority = Priority.NORMAL,
        due: Long? = null,
        done: Boolean = false,
        archived: Boolean = false,
        deleted: Boolean = false
    ) = TodoEntity(
        id = id,
        title = "todo $id",
        bucketId = 1,
        priority = priority.rank,
        dueDate = due,
        isDone = done,
        isArchived = archived,
        deletedAt = if (deleted) now else null
    )

    private fun ids(list: List<RankedTodo>) = list.map { it.todo.id }

    @Test
    fun `overdue comes before urgent, which comes before due today`() {
        val ranked = NextThingRanker.rank(
            listOf(
                todo(1, due = at(today, 17)),                          // due today
                todo(2, priority = Priority.URGENT),                    // urgent, undated
                todo(3, due = at(today.minusDays(2), 9))                // overdue
            ),
            now, dayEnd
        )
        assertEquals(listOf(3L, 2L, 1L), ids(ranked))
        assertEquals(
            listOf(NextKind.OVERDUE, NextKind.URGENT, NextKind.DUE_TODAY),
            ranked.map { it.kind }
        )
    }

    @Test
    fun `among overdue, urgent and high lead, then the longest overdue`() {
        // The reason this is not simply oldest-first: the Someday item from last month must
        // not own the top slot ahead of something urgent that slipped yesterday.
        val ranked = NextThingRanker.rank(
            listOf(
                todo(1, priority = Priority.SOMEDAY, due = at(today.minusDays(30), 9)),
                todo(2, priority = Priority.NORMAL, due = at(today.minusDays(3), 9)),
                todo(3, priority = Priority.HIGH, due = at(today.minusDays(1), 9)),
                todo(4, priority = Priority.URGENT, due = at(today.minusDays(1), 12))
            ),
            now, dayEnd
        )
        assertEquals(listOf(4L, 3L, 1L, 2L), ids(ranked))
    }

    @Test
    fun `normal and someday overdue are ordered by age alone`() {
        val ranked = NextThingRanker.rank(
            listOf(
                todo(1, priority = Priority.NORMAL, due = at(today.minusDays(2), 9)),
                todo(2, priority = Priority.SOMEDAY, due = at(today.minusDays(5), 9))
            ),
            now, dayEnd
        )
        assertEquals(listOf(2L, 1L), ids(ranked))
    }

    @Test
    fun `due today is earliest first, and an item appears once`() {
        val ranked = NextThingRanker.rank(
            listOf(
                todo(1, due = at(today, 17)),
                todo(2, due = at(today, 12)),
                // Urgent and due today: listed once, as urgent.
                todo(3, priority = Priority.URGENT, due = at(today, 15))
            ),
            now, dayEnd
        )
        assertEquals(listOf(3L, 2L, 1L), ids(ranked))
        assertEquals(3, ranked.size)
    }

    @Test
    fun `tomorrow, undated normal work, and finished work are not on today`() {
        val ranked = NextThingRanker.rank(
            listOf(
                todo(1, due = at(today.plusDays(1), 9)),
                todo(2),
                todo(3, priority = Priority.URGENT, done = true),
                todo(4, due = at(today.minusDays(1), 9), archived = true),
                todo(5, due = at(today.minusDays(1), 9), deleted = true)
            ),
            now, dayEnd
        )
        assertTrue(ranked.isEmpty())
    }

    @Test
    fun `a snoozed to-do is hidden until its time, then returns`() {
        val todos = listOf(todo(1, due = at(today.minusDays(1), 9)), todo(2, due = at(today, 17)))
        val snoozed = mapOf(1L to now + 3_600_000L)
        assertEquals(listOf(2L), ids(NextThingRanker.rank(todos, now, dayEnd, snoozed)))
        assertEquals(
            listOf(1L, 2L),
            ids(NextThingRanker.rank(todos, now + 3_600_000L, dayEnd, snoozed))
        )
    }

    @Test
    fun `tomorrow is its own list and never leaks into today's`() {
        val tomorrowEnd = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val todos = listOf(
            todo(1, due = at(today.plusDays(1), 15)),
            todo(2, due = DueDateEnd(today.plusDays(1))),
            todo(3, priority = Priority.HIGH, due = at(today.plusDays(1), 9)),   // listed by rank()
            todo(4, due = at(today.plusDays(2), 9)),                             // the day after
            todo(5, due = at(today, 17))                                         // today
        )
        assertEquals(listOf(5L, 3L).sorted(), ids(NextThingRanker.rank(todos, now, dayEnd)).sorted())
        val tomorrow = NextThingRanker.tomorrow(todos, now, dayEnd, tomorrowEnd)
        assertEquals(listOf(1L, 2L), ids(tomorrow))
        assertEquals("Tomorrow, 3:00 pm", NextThingRanker.meta(tomorrow[0], now, zone))
        assertEquals("Tomorrow", NextThingRanker.meta(tomorrow[1], now, zone))
    }

    private fun DueDateEnd(day: LocalDate) =
        com.carlmanning.carlsbrain.domain.DueDate.endOfDay(day, zone)

    @Test
    fun `nothing to do is an empty list, not a guess`() {
        assertTrue(NextThingRanker.rank(emptyList(), now, dayEnd).isEmpty())
    }

    @Test
    fun `meta lines read the way the design says`() {
        fun meta(t: TodoEntity) =
            NextThingRanker.meta(NextThingRanker.rank(listOf(t), now, dayEnd).single(), now, zone)

        assertEquals("Overdue 2 days", meta(todo(1, due = at(today.minusDays(2), 9))))
        // Calendar days: yesterday evening is one day this morning, not zero.
        assertEquals("Overdue 1 day", meta(todo(1, due = at(today.minusDays(1), 22))))
        assertEquals("Overdue today", meta(todo(1, due = at(today, 8))))
        assertEquals("Urgent", meta(todo(1, priority = Priority.URGENT)))
        assertEquals("High priority", meta(todo(1, priority = Priority.HIGH)))
        assertEquals("Due today, 5:00 pm", meta(todo(1, due = at(today, 17))))
    }
}
