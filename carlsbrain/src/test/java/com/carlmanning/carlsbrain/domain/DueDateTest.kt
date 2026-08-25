package com.carlmanning.carlsbrain.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Test

/**
 * Due dates, and the timezone trap they were falling into.
 *
 * Worth pinning down because the bug was invisible and plausible: Material 3's date picker
 * reports midnight UTC, both to-do screens stored that verbatim, and in Dubbo it rendered as
 * 10 am. Carl reasonably read it as a deliberate default. Nothing in the app crashed, nothing
 * logged, and a to-do due "Monday" quietly went overdue on Monday morning.
 *
 * Sydney is used throughout rather than the default zone, so these assert real behaviour rather
 * than whatever the machine running them happens to be set to.
 */
class DueDateTest {

    private val sydney: ZoneId = ZoneId.of("Australia/Sydney")

    /** The value a Material 3 DatePicker reports for the given day: midnight UTC. */
    private fun pickerValueFor(day: LocalDate): Long =
        day.atStartOfDay(ZoneId.of("UTC")).toInstant().toEpochMilli()

    private fun localTimeOf(epochMs: Long) =
        Instant.ofEpochMilli(epochMs).atZone(sydney).toLocalTime()

    private fun localDateOf(epochMs: Long) =
        Instant.ofEpochMilli(epochMs).atZone(sydney).toLocalDate()

    @Test
    fun `the picker's day is read in UTC, not the device zone`() {
        // The whole bug in one assertion. Reading midnight UTC in Sydney gives the right date
        // here only because the offset is positive; the helper must not depend on that.
        val monday = LocalDate.of(2026, 8, 24)
        assertEquals(monday, DueDate.dayFromPicker(pickerValueFor(monday)))
    }

    @Test
    fun `a date with no time is due at the end of that day, not 10am`() {
        val monday = LocalDate.of(2026, 8, 24)
        val due = DueDate.endOfDayFromPicker(pickerValueFor(monday), sydney)

        assertEquals(monday, localDateOf(due))
        assertEquals(23, localTimeOf(due).hour)
        assertEquals(59, localTimeOf(due).minute)
    }

    @Test
    fun `a date-only to-do is not overdue during its own day`() {
        // Carl's rule: a day is a day. Due Monday is not late until Monday is over.
        val monday = LocalDate.of(2026, 8, 24)
        val due = DueDate.endOfDayFromPicker(pickerValueFor(monday), sydney)

        val mondayMorning = monday.atTime(10, 0).atZone(sydney).toInstant().toEpochMilli()
        val mondayLate = monday.atTime(23, 0).atZone(sydney).toInstant().toEpochMilli()
        val afterMidnight = monday.plusDays(1).atTime(0, 1).atZone(sydney).toInstant().toEpochMilli()

        // `dueDate < now` is the overdue test used across the app.
        assertFalse("10am Monday must not be overdue", due < mondayMorning)
        assertFalse("11pm Monday must not be overdue", due < mondayLate)
        assertTrue("after midnight it is overdue", due < afterMidnight)
    }

    @Test
    fun `a chosen time lands on the right local day`() {
        val monday = LocalDate.of(2026, 8, 24)
        val due = DueDate.atTimeFromPicker(pickerValueFor(monday), 16, 30, sydney)

        assertEquals(monday, localDateOf(due))
        assertEquals(16, localTimeOf(due).hour)
        assertEquals(30, localTimeOf(due).minute)
    }

    @Test
    fun `a negative UTC offset still gets the day Carl picked`() {
        // The reason dayFromPicker reads UTC rather than the device zone. In New York, midnight
        // UTC on Monday is 8pm on SUNDAY — so the old code would have dated the to-do a day early.
        val newYork = ZoneId.of("America/New_York")
        val monday = LocalDate.of(2026, 8, 24)
        val due = DueDate.endOfDayFromPicker(pickerValueFor(monday), newYork)

        assertEquals(monday, Instant.ofEpochMilli(due).atZone(newYork).toLocalDate())
    }

    @Test
    fun `hasExplicitTime tells a chosen time from a date-only one`() {
        val monday = LocalDate.of(2026, 8, 24)
        val dateOnly = DueDate.endOfDayFromPicker(pickerValueFor(monday), sydney)
        val timed = DueDate.atTimeFromPicker(pickerValueFor(monday), 16, 0, sydney)

        assertFalse(DueDate.hasExplicitTime(dateOnly, sydney))
        assertTrue(DueDate.hasExplicitTime(timed, sydney))
    }

    @Test
    fun `the time picker opens at 9am for a date-only to-do`() {
        // Not 23:59, which is where the sentinel sits and would be nonsense to show.
        val monday = LocalDate.of(2026, 8, 24)
        val dateOnly = DueDate.endOfDayFromPicker(pickerValueFor(monday), sydney)

        assertEquals(9 to 0, DueDate.pickerTime(dateOnly, sydney))
        assertEquals(9 to 0, DueDate.pickerTime(null, sydney))
        assertEquals(
            16 to 30,
            DueDate.pickerTime(DueDate.atTimeFromPicker(pickerValueFor(monday), 16, 30, sydney), sydney)
        )
    }
}
