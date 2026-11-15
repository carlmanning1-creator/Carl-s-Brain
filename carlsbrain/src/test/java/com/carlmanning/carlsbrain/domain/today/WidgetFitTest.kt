package com.carlmanning.carlsbrain.domain.today

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the Today widget shows at each size.
 *
 * The heights are the mock-up's nominal ones — 200, 300, 400 and 500dp for 4x2 to 4x5. Real
 * launchers hand out slightly different numbers, which is exactly why the rules are tested as
 * rules (what must always stay, what goes first) and not only as a table of expected layouts.
 */
class WidgetFitTest {

    private val heights = listOf(200f, 300f, 400f, 500f)

    private fun fit(
        height: Float,
        options: WidgetFit.Options = WidgetFit.Options(),
        fontScale: Float = 1f,
        hasCalendar: Boolean = true,
        hasBriefing: Boolean = true,
        available: Int = 6
    ) = WidgetFit.compute(height, fontScale, options, hasCalendar, hasBriefing, available)

    private fun everyCombination(): List<WidgetFit.Options> {
        val out = ArrayList<WidgetFit.Options>()
        for (extra in 0..6) for (b in listOf(true, false)) for (c in listOf(true, false))
            for (cap in listOf(true, false)) out.add(WidgetFit.Options(extra, b, c, cap))
        return out
    }

    @Test
    fun `4x2 is the next thing and the capture bar, nothing else`() {
        val r = fit(200f)
        assertTrue(r.showNext)
        assertTrue(r.showCapture)
        assertFalse(r.showCalendar)
        assertFalse(r.showBriefing)
        assertEquals(0, r.extraRows)
    }

    @Test
    fun `4x3 adds the calendar and briefing lines before any extra item`() {
        val r = fit(300f)
        assertTrue(r.showCalendar)
        assertTrue(r.showBriefing)
        assertEquals(0, r.extraRows)
    }

    @Test
    fun `4x4 and 4x5 fill with extra items up to the chosen count`() {
        assertEquals(2, fit(400f).extraRows)
        assertEquals(3, fit(500f).extraRows)                                   // capped at 3
        assertEquals(4, fit(500f, WidgetFit.Options(extraItems = 6)).extraRows)
    }

    @Test
    fun `the next thing and the capture bar survive every size and setting`() {
        for (h in heights) for (o in everyCombination()) {
            val r = fit(h, o)
            assertTrue("next thing at $h with $o", r.showNext)
            assertEquals("capture bar at $h with $o", o.capture, r.showCapture)
        }
    }

    @Test
    fun `a switched-off part never appears, and extras never exceed the choice`() {
        for (h in heights) for (o in everyCombination()) {
            val r = fit(h, o)
            if (!o.briefing) assertFalse(r.showBriefing)
            if (!o.calendar) assertFalse(r.showCalendar)
            assertTrue(r.extraRows <= o.extraItems)
        }
    }

    @Test
    fun `a taller widget never shows less`() {
        for (o in everyCombination()) {
            var previous = fit(heights.first(), o)
            for (h in heights.drop(1)) {
                val r = fit(h, o)
                assertTrue(r.extraRows >= previous.extraRows)
                if (previous.showCalendar) assertTrue(r.showCalendar)
                if (previous.showBriefing) assertTrue(r.showBriefing)
                previous = r
            }
        }
    }

    @Test
    fun `the briefing goes before the calendar line`() {
        // Find a height with room for exactly one of them: it must be the calendar.
        var seen = false
        var h = 190f
        while (h <= 320f) {
            val r = fit(h)
            if (r.showCalendar != r.showBriefing) {
                assertTrue("at $h the calendar line should be the survivor", r.showCalendar)
                seen = true
            }
            h += 2f
        }
        assertTrue("expected some height that fits only one of the two", seen)
    }

    @Test
    fun `nothing to show means nothing shown, whatever the room`() {
        val r = fit(500f, hasCalendar = false, hasBriefing = false, available = 0)
        assertFalse(r.showCalendar)
        assertFalse(r.showBriefing)
        assertEquals(0, r.extraRows)
    }

    @Test
    fun `a short real-world 4x2 shrinks the capture bar before giving anything up`() {
        // One UI hands out less than the nominal 200dp.
        val r = fit(176f)
        assertTrue(r.showNext)
        assertTrue(r.showCapture)
        assertTrue(r.captureCompact)
    }

    @Test
    fun `a large system font costs rows, never the next thing`() {
        val normal = fit(500f, WidgetFit.Options(extraItems = 6))
        val large = fit(500f, WidgetFit.Options(extraItems = 6), fontScale = 1.5f)
        assertTrue(large.showNext)
        assertTrue(large.extraRows <= normal.extraRows)
    }

    @Test
    fun `the weather line goes before the calendar, and needs a reason to show`() {
        val withWeather = { h: Float -> WidgetFit.compute(h, 1f, WidgetFit.Options(), true, true, 6, hasWeather = true) }
        assertTrue(withWeather(500f).showWeather)
        assertFalse(withWeather(200f).showWeather)
        assertFalse(fit(500f).showWeather)  // no weather cached
        for (h in heights) {
            val r = withWeather(h)
            if (r.showWeather) assertTrue("weather without calendar at $h", r.showCalendar)
            assertTrue(r.showCapture)
        }
    }

    @Test
    fun `an old 2x2 quick capture placement keeps its capture buttons`() {
        val r = fit(90f)
        assertFalse(r.showNext)
        assertTrue(r.showCapture)
        assertTrue(r.captureCompact)
    }
}
