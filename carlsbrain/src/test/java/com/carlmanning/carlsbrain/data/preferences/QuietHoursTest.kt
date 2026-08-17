package com.carlmanning.carlsbrain.data.preferences

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Boundary tests for [UserPreferences.isWithinQuietHours].
 *
 * Worth testing rather than eyeballing because every failure mode here is silent: the wake word
 * simply keeps listening (or stops listening) at the wrong times, with nothing in the UI or logs
 * to say the window was computed wrongly. The overnight wrap is the specific trap — the obvious
 * `minute in start..end` implementation yields an empty range for every overnight setting, so
 * quiet hours would never apply at all and would look like a preference that does nothing.
 */
class QuietHoursTest {

    private fun at(hour: Int, minute: Int = 0) = hour * 60 + minute

    // ── Overnight window (the default, 22:00 → 06:00) ─────────────────────────

    @Test
    fun `overnight window includes late evening`() {
        assertTrue(UserPreferences.isWithinQuietHours(at(23), at(22), at(6)))
    }

    @Test
    fun `overnight window includes early morning after midnight`() {
        assertTrue(UserPreferences.isWithinQuietHours(at(2), at(22), at(6)))
    }

    @Test
    fun `overnight window excludes the evening before it starts`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(21, 59), at(22), at(6)))
    }

    /** Start is inclusive: at exactly 22:00 the app should already have gone quiet. */
    @Test
    fun `overnight window includes its start minute`() {
        assertTrue(UserPreferences.isWithinQuietHours(at(22), at(22), at(6)))
    }

    /** End is exclusive, so listening resumes at 06:00 rather than 06:01. */
    @Test
    fun `overnight window excludes its end minute`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(6), at(22), at(6)))
    }

    @Test
    fun `overnight window excludes the middle of the day`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(13), at(22), at(6)))
    }

    // ── Same-day window (start before end) ────────────────────────────────────

    @Test
    fun `same day window includes a time inside it`() {
        assertTrue(UserPreferences.isWithinQuietHours(at(12), at(9), at(17)))
    }

    @Test
    fun `same day window excludes a time before it`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(8), at(9), at(17)))
    }

    @Test
    fun `same day window excludes a time after it`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(18), at(9), at(17)))
    }

    // ── Degenerate and edge windows ───────────────────────────────────────────

    /**
     * Equal start and end is an EMPTY window, not an all-day one. Switching quiet hours on with
     * both pickers untouched must not silently deafen the app permanently — that would look
     * exactly like the wake word being broken.
     */
    @Test
    fun `equal start and end never matches`() {
        assertFalse(UserPreferences.isWithinQuietHours(at(22), at(22), at(22)))
        assertFalse(UserPreferences.isWithinQuietHours(at(3), at(22), at(22)))
        assertFalse(UserPreferences.isWithinQuietHours(at(0), at(22), at(22)))
    }

    @Test
    fun `window starting at midnight includes midnight`() {
        assertTrue(UserPreferences.isWithinQuietHours(at(0), at(0), at(6)))
    }

    /** A window that crosses midnight by only half an hour still behaves correctly. */
    @Test
    fun `short window crossing midnight`() {
        val start = at(23)
        val end = at(0, 30)
        assertTrue(UserPreferences.isWithinQuietHours(at(23, 59), start, end))
        assertTrue(UserPreferences.isWithinQuietHours(at(0, 29), start, end))
        assertFalse(UserPreferences.isWithinQuietHours(at(0, 31), start, end))
        assertFalse(UserPreferences.isWithinQuietHours(at(22, 59), start, end))
    }
}
