package com.carlmanning.carlsbrain.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * Boundary tests for the smart date formatters.
 *
 * These are plain JVM tests: DateFormat.kt only touches `java.time` in the format functions.
 * The one Android dependency, `initDateFormatting(Context)`, is never called here, so the
 * module-level clock flag keeps its default of **24-hour** — assertions below assume "HH:mm".
 *
 * All fixtures are built in the system default zone (the same zone the formatters use), so
 * the day-boundary arithmetic under test is exercised exactly as it is at runtime.
 */
class DateFormatTest {

    private val zone: ZoneId = ZoneId.systemDefault()

    /** 09:30 on [date], as epoch millis in the system zone. */
    private fun at(date: LocalDate, hour: Int = 9, minute: Int = 30): Long =
        LocalDateTime.of(date, java.time.LocalTime.of(hour, minute))
            .atZone(zone).toInstant().toEpochMilli()

    /** A fixed "now": midday, so ±hours never slip across a day boundary. */
    private val today: LocalDate = LocalDate.of(2025, 6, 15)
    private val now: Long = at(today, hour = 12, minute = 0)

    private fun dateTime(daysApart: Long) = formatSmartDateTime(at(today.plusDays(daysApart)), now)
    private fun dueDateTime(daysApart: Long) = formatSmartDueDateTime(at(today.plusDays(daysApart)), now)
    private fun date(daysApart: Long) = formatSmartDate(at(today.plusDays(daysApart)), now)

    /** True if the string contains an HH:mm style time (24-hour default). */
    private fun hasTime(s: String) = Regex("""\d{1,2}:\d{2}""").containsMatchIn(s)

    // ---- epochMillis guard ----

    @Test
    fun zeroAndNegativeEpoch_returnEmpty() {
        assertEquals("", formatSmartDateTime(0L, now))
        assertEquals("", formatSmartDateTime(-1L, now))
        assertEquals("", formatSmartDueDateTime(0L, now))
        assertEquals("", formatSmartDueDateTime(-1000L, now))
        assertEquals("", formatSmartDate(0L, now))
        assertEquals("", formatSmartDate(-1L, now))
    }

    // ---- formatSmartDateTime branch boundaries ----

    @Test
    fun dateTime_today_isTimeOnly() {
        val s = dateTime(0)
        assertEquals("09:30", s)
        assertTrue(hasTime(s))
    }

    @Test
    fun dateTime_tomorrowAndYesterday() {
        assertTrue(dateTime(1).startsWith("Tomorrow"))
        assertTrue(hasTime(dateTime(1)))
        assertTrue(dateTime(-1).startsWith("Yesterday"))
        assertTrue(hasTime(dateTime(-1)))
    }

    @Test
    fun dateTime_withinWeekAhead_isWeekdayPlusTime() {
        for (d in 2L..6L) {
            val s = dateTime(d)
            assertFalse("day +$d should not say Last: $s", s.contains("Last"))
            assertTrue("day +$d should keep a time: $s", hasTime(s))
            // Weekday abbreviation plus time, e.g. "Tue 09:30" — assert shape, not the name.
            assertTrue("day +$d should end with the time: $s", s.endsWith("09:30"))
        }
    }

    @Test
    fun dateTime_withinWeekBehind_isLastWeekdayPlusTime() {
        for (d in -6L..-2L) {
            val s = dateTime(d)
            assertTrue("day $d should say Last: $s", s.startsWith("Last "))
            assertTrue("day $d should keep a time: $s", hasTime(s))
        }
    }

    @Test
    fun dateTime_atAndBeyondSevenDays_dropsTheTime() {
        for (d in listOf(7L, 8L, -7L, -8L)) {
            val s = dateTime(d)
            assertFalse("day $d should drop the time: $s", hasTime(s))
            assertFalse("day $d should not say Last: $s", s.contains("Last"))
        }
    }

    @Test
    fun dateTime_sameYearOmitsYear_differentYearIncludesIt() {
        // +30 days: still 2025.
        val sameYear = dateTime(30)
        assertFalse(sameYear.contains("2025"))
        // +300 days: 2026.
        val nextYear = dateTime(300)
        assertTrue(nextYear.contains("2026"))
        // -300 days: 2024.
        val lastYear = dateTime(-300)
        assertTrue(lastYear.contains("2024"))
    }

    // ---- formatSmartDueDateTime always keeps a time ----

    @Test
    fun dueDateTime_retainsTimeAtEveryDistance() {
        for (d in listOf(0L, 1L, -1L, 2L, -2L, 6L, -6L, 7L, -7L, 8L, -8L, 30L, 300L, -300L)) {
            val s = dueDateTime(d)
            assertTrue("due date at day $d must keep a time: $s", hasTime(s))
        }
    }

    @Test
    fun dueDateTime_matchesDateTimeWithinTheWeek() {
        for (d in listOf(0L, 1L, -1L, 2L, -2L, 6L, -6L)) {
            assertEquals("day $d", dateTime(d), dueDateTime(d))
        }
    }

    @Test
    fun dueDateTime_beyondWeek_appendsTimeToDate() {
        val s = dueDateTime(8)
        assertTrue(s.contains(","))
        assertTrue(s.endsWith("09:30"))
        assertFalse(s.contains("2025"))

        val nextYear = dueDateTime(300)
        assertTrue(nextYear.contains("2026"))
        assertTrue(nextYear.endsWith("09:30"))
    }

    // ---- formatSmartDate (no time at any distance) ----

    @Test
    fun date_neverHasTime() {
        for (d in listOf(0L, 1L, -1L, 2L, -2L, 6L, -6L, 7L, -7L, 8L, -8L, 300L, -300L)) {
            val s = date(d)
            assertFalse("day $d must not show a time: $s", hasTime(s))
        }
    }

    @Test
    fun date_namedDayBoundaries() {
        assertEquals("Today", date(0))
        assertEquals("Tomorrow", date(1))
        assertEquals("Yesterday", date(-1))
        assertFalse(date(2).contains("Last"))
        assertTrue(date(-2).startsWith("Last "))
        assertFalse(date(6).contains("Last"))
        assertTrue(date(-6).startsWith("Last "))
        // 7 days out in either direction falls through to an absolute date.
        assertFalse(date(7).contains("Last"))
        assertFalse(date(7).contains("Today"))
        assertFalse(date(-7).contains("Last"))
        assertFalse(date(-8).contains("Last"))
    }

    @Test
    fun date_sameYearVsDifferentYear() {
        assertFalse(date(30).contains("2025"))
        assertTrue(date(300).contains("2026"))
        assertTrue(date(-300).contains("2024"))
    }

    // ---- LocalDate overload agrees with the millis overload ----

    @Test
    fun localDateOverload_matchesMillisOverload() {
        for (d in listOf(0L, 1L, -1L, 3L, -3L, 10L)) {
            val target = today.plusDays(d)
            assertEquals(
                formatSmartDate(target.atStartOfDay(zone).toInstant().toEpochMilli(), now),
                formatSmartDate(target, now)
            )
        }
    }

    // ---- overdue vs upcoming must never be confused ----

    @Test
    fun pastAndFutureNeverProduceTheSameLabel() {
        for (d in 1L..6L) {
            assertFalse(
                "day +$d and day -$d collide",
                formatSmartDate(at(today.plusDays(d)), now) ==
                    formatSmartDate(at(today.minusDays(d)), now)
            )
        }
    }
}
