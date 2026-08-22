package com.carlmanning.carlsbrain.domain.journal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import java.util.Calendar
import org.junit.Test

/**
 * Parsing of the per-template reminder rule (`DOW:HH:MM`).
 *
 * Every failure mode here is silent. A rule that fails to parse simply never fires, and a rule
 * that parses wrongly fires at the wrong time — neither shows up anywhere in the app, and Carl
 * only notices the Sunday training nudge is missing weeks later, if at all. Blank must mean
 * "no reminder" rather than "midnight on Sunday", because blank is the default on every
 * template he has not configured.
 */
class JournalReminderRuleTest {

    @Test
    fun `a well-formed rule parses to the right day and time`() {
        val rule = JournalReminderScheduler.parse("SUN:10:00")
        assertEquals(Calendar.SUNDAY, rule?.dayOfWeek)
        assertEquals(10, rule?.hour)
        assertEquals(0, rule?.minute)
    }

    @Test
    fun `case and surrounding spaces do not matter`() {
        // Carl types these into a text field; requiring exact case would be a trap.
        assertEquals(
            JournalReminderScheduler.parse("SUN:10:00"),
            JournalReminderScheduler.parse("  sun:10:00  ")
        )
    }

    @Test
    fun `every weekday is recognised`() {
        val expected = mapOf(
            "SUN" to Calendar.SUNDAY,
            "MON" to Calendar.MONDAY,
            "TUE" to Calendar.TUESDAY,
            "WED" to Calendar.WEDNESDAY,
            "THU" to Calendar.THURSDAY,
            "FRI" to Calendar.FRIDAY,
            "SAT" to Calendar.SATURDAY
        )
        for ((token, day) in expected) {
            val parsed = JournalReminderScheduler.parse("$token:07:30")?.dayOfWeek ?: -1
            assertEquals(day, parsed)
        }
    }

    @Test
    fun `blank means no reminder, which is the default on every template`() {
        assertNull(JournalReminderScheduler.parse(""))
        assertNull(JournalReminderScheduler.parse("   "))
    }

    @Test
    fun `malformed rules are refused rather than half-interpreted`() {
        assertNull(JournalReminderScheduler.parse("SUN"))
        assertNull(JournalReminderScheduler.parse("SUN:10"))
        assertNull(JournalReminderScheduler.parse("FUNDAY:10:00"))
        assertNull(JournalReminderScheduler.parse("SUN:10:00:00"))
        assertNull(JournalReminderScheduler.parse("SUN:ten:00"))
    }

    @Test
    fun `out-of-range times are refused`() {
        // 24:00 and 10:60 would otherwise roll over into a different day or hour.
        assertNull(JournalReminderScheduler.parse("SUN:24:00"))
        assertNull(JournalReminderScheduler.parse("SUN:10:60"))
        assertNull(JournalReminderScheduler.parse("SUN:-1:00"))
    }

    @Test
    fun `describe is readable, and says so plainly when there is no reminder`() {
        assertEquals("Sun 10:00", JournalReminderScheduler.describe("SUN:10:00"))
        assertEquals("Tue 19:05", JournalReminderScheduler.describe("TUE:19:05"))
        assertEquals("No reminder", JournalReminderScheduler.describe(""))
        assertEquals("No reminder", JournalReminderScheduler.describe("nonsense"))
    }
}
