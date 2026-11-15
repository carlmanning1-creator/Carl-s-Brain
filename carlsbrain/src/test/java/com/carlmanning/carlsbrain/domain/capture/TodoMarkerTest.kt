package com.carlmanning.carlsbrain.domain.capture

import com.carlmanning.carlsbrain.domain.DueDate
import com.carlmanning.carlsbrain.domain.model.Priority
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Reading a to-do out of what Claude wrote.
 *
 * The cases that matter are the ungraceful ones: markers written before the date fields
 * existed, fields left blank, and dates that are not dates. None of them may cost a capture.
 *
 * Sydney throughout, so nothing here depends on the machine running it.
 */
class TodoMarkerTest {

    private val zone: ZoneId = ZoneId.of("Australia/Sydney")
    private val monday: LocalDate = LocalDate.of(2026, 11, 16)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0): Long =
        LocalDateTime.of(day.year, day.month, day.dayOfMonth, hour, minute)
            .atZone(zone).toInstant().toEpochMilli()

    private val now = at(monday, 10)

    @Test
    fun `a three-field marker from before dates existed parses as it always did`() {
        val p = TodoMarker.parse("Book the car in | Family | HIGH", zone)!!
        assertEquals("Book the car in", p.title)
        assertEquals("Family", p.bucket)
        assertEquals(Priority.HIGH, p.priority)
        assertNull(p.dueDate)
        assertNull(p.reminderAt)
    }

    @Test
    fun `a title alone is enough`() {
        val p = TodoMarker.parse("Buy milk", zone)!!
        assertEquals("Buy milk", p.title)
        assertEquals("", p.bucket)
        assertEquals(Priority.NORMAL, p.priority)
    }

    @Test
    fun `no title is no to-do`() {
        assertNull(TodoMarker.parse(" | Family | HIGH", zone))
        assertNull(TodoMarker.parse("", zone))
    }

    @Test
    fun `a date and time is that instant, locally`() {
        val p = TodoMarker.parse("Call the dentist | Personal | NORMAL | 2026-11-17T15:00", zone)!!
        assertEquals(at(monday.plusDays(1), 15), p.dueDate)
        assertNull(p.reminderAt)
    }

    @Test
    fun `a date-only due is the end of that day, the app's convention`() {
        val p = TodoMarker.parse("Timesheet | Work | NORMAL | 2026-11-20", zone)!!
        assertEquals(DueDate.endOfDay(LocalDate.of(2026, 11, 20), zone), p.dueDate)
        // Which is what stops it showing a made-up 11:59 pm.
        assertEquals(false, DueDate.hasExplicitTime(p.dueDate!!, zone))
    }

    @Test
    fun `a date-only reminder fires at nine in the morning`() {
        val p = TodoMarker.parse("Ring Mum | Family | NORMAL | | 2026-11-20", zone)!!
        assertNull(p.dueDate)
        assertEquals(at(LocalDate.of(2026, 11, 20), 9), p.reminderAt)
    }

    @Test
    fun `due and reminder are independent`() {
        val p = TodoMarker.parse(
            "Submit form | Work | URGENT | 2026-11-20T17:00 | 2026-11-20T09:30", zone
        )!!
        assertEquals(at(LocalDate.of(2026, 11, 20), 17), p.dueDate)
        assertEquals(at(LocalDate.of(2026, 11, 20), 9, 30), p.reminderAt)
    }

    @Test
    fun `a space instead of a T, seconds, and stray quotes are all tolerated`() {
        val expected = at(monday.plusDays(1), 15)
        assertEquals(expected, TodoMarker.parseDue("2026-11-17 15:00", zone))
        assertEquals(expected, TodoMarker.parseDue("2026-11-17T15:00:00", zone))
        assertEquals(expected, TodoMarker.parseDue("\"2026-11-17T15:00\"", zone))
    }

    @Test
    fun `rubbish in a date field loses the date and keeps the to-do`() {
        for (junk in listOf("tomorrow", "none", "n/a", "3pm", "2026-13-45", "-", "null")) {
            val p = TodoMarker.parse("Still saved | Family | NORMAL | $junk | $junk", zone)
            assertNotNull("to-do lost to '$junk'", p)
            assertNull("date invented from '$junk'", p!!.dueDate)
            assertNull(p.reminderAt)
        }
    }

    @Test
    fun `an unknown priority is normal, in any case`() {
        assertEquals(Priority.NORMAL, TodoMarker.parse("x | Family | SOON", zone)!!.priority)
        assertEquals(Priority.URGENT, TodoMarker.parse("x | Family | urgent", zone)!!.priority)
    }

    @Test
    fun `the overlay's separate fields give the same answer as the marker`() {
        val fromMarker = TodoMarker.parse("Call | Family | HIGH | 2026-11-17T15:00 | 2026-11-17", zone)
        val fromJson = TodoMarker.build("Call", "Family", "HIGH", "2026-11-17T15:00", "2026-11-17", zone)
        assertEquals(fromMarker, fromJson)
        // Absent fields — an older prompt — are simply no dates.
        assertNull(TodoMarker.build("Call", "Family", "HIGH", null, null, zone)!!.dueDate)
    }

    @Test
    fun `dates are said back the way a person would say them`() {
        assertEquals("today 3 pm", TodoMarker.describe(at(monday, 15), now, zone))
        assertEquals("tomorrow 9:30 am", TodoMarker.describe(at(monday.plusDays(1), 9, 30), now, zone))
        assertEquals("Friday", TodoMarker.describe(DueDate.endOfDay(monday.plusDays(4), zone), now, zone))
        assertEquals("30 November", TodoMarker.describe(DueDate.endOfDay(LocalDate.of(2026, 11, 30), zone), now, zone))
        assertEquals("tomorrow", TodoMarker.describe(DueDate.endOfDay(monday.plusDays(1), zone), now, zone))
    }

    @Test
    fun `the confirmation names what was set and nothing else`() {
        assertEquals("", TodoMarker.describeDates(null, null, now, zone))
        assertEquals("due tomorrow 3 pm", TodoMarker.describeDates(at(monday.plusDays(1), 15), null, now, zone))
        assertEquals(
            "due Friday, reminder Friday 9 am",
            TodoMarker.describeDates(
                DueDate.endOfDay(monday.plusDays(4), zone), at(monday.plusDays(4), 9), now, zone
            )
        )
    }
}
