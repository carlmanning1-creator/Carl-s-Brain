package com.carlmanning.carlsbrain.domain.today

import kotlin.math.floor

/**
 * Decides what the Today widget shows at the height it has actually been given.
 *
 * Pure and deterministic so it can be tested at every size and toggle combination without a
 * launcher. The numbers are the mock-up's (November 2026), with two corrections it could not
 * know about: a real 4x2 on One UI is often shorter than the 200dp it assumed, and text grows
 * with the system font size while padding does not — so every height is split into a fixed
 * part and a part that scales.
 *
 * Order of sacrifice, from the approved design: briefing first, then the calendar line, then
 * extra items. The next thing and the capture bar always stay. Before dropping either of
 * those this shrinks them — icon-only capture buttons, then a one-line title — and only a
 * widget too small for both (an old 2x2 Quick Capture placement) keeps the capture bar alone,
 * because that is what that placement was for.
 */
object WidgetFit {

    data class Options(
        val extraItems: Int = 3,
        val briefing: Boolean = true,
        val calendar: Boolean = true,
        val capture: Boolean = true,
        val weather: Boolean = true
    )

    data class Result(
        val showNext: Boolean,
        /** Title may wrap to two lines. False when the widget is too short for that. */
        val nextTwoLines: Boolean,
        val showCapture: Boolean,
        /** Icons only, no labels. */
        val captureCompact: Boolean,
        val showCalendar: Boolean,
        val showBriefing: Boolean,
        val extraRows: Int,
        val showWeather: Boolean = false
    )

    private const val PADDING = 32f
    private const val GAP = 10f

    private fun nextBlock(fs: Float, twoLines: Boolean) =
        // label + gap + (title lines + meta), never shorter than the 48dp tick target.
        15f * fs + 4f + maxOf(48f, (if (twoLines) 48f else 24f) * fs + 4f + 16f * fs)

    private fun captureBar(fs: Float, compact: Boolean) = if (compact) 44f else 36f + 18f * fs
    private fun calendarLine(fs: Float) = 16f + 18f * fs
    private fun briefingLine(fs: Float) = 19f * fs
    private fun extraRow(fs: Float) = maxOf(48f, 12f + 34f * fs)
    private fun weatherLine(fs: Float) = 17f * fs
    private const val WEATHER_GAP = 6f

    /**
     * @param heightDp the widget's real height from the host, never a stored size label.
     * @param fontScale the system font scale (1.0 is default).
     * @param hasCalendar whether there is a next event to show at all.
     * @param hasBriefing whether there is a briefing from today to show.
     * @param available extra items the ranker has beyond the next thing.
     */
    fun compute(
        heightDp: Float,
        fontScale: Float,
        options: Options,
        hasCalendar: Boolean,
        hasBriefing: Boolean,
        available: Int,
        hasWeather: Boolean = false
    ): Result {
        val fs = fontScale.coerceIn(0.85f, 2f)
        val inner = heightDp - PADDING

        // The two things that always stay, shrunk before anything is given up.
        var twoLines = true
        var compact = false
        fun fixed() = nextBlock(fs, twoLines) +
            (if (options.capture) GAP + captureBar(fs, compact) else 0f)

        if (fixed() > inner && options.capture) compact = true
        if (fixed() > inner) twoLines = false
        if (fixed() > inner) {
            // Too small for both even shrunk. An old Quick Capture placement lands here.
            return if (options.capture) {
                Result(false, false, true, true, false, false, 0)
            } else {
                Result(true, false, false, false, false, false, 0)
            }
        }

        var left = inner - fixed()
        val calendarWanted = options.calendar && hasCalendar
        var showCalendar = false
        if (calendarWanted && left >= GAP + calendarLine(fs)) {
            showCalendar = true
            left -= GAP + calendarLine(fs)
        }
        var showBriefing = false
        // The briefing is the first thing to go, so it may not take space the calendar line
        // was just refused. It is the shorter of the two: without this guard there is a band
        // of heights where the briefing squeezes in and the calendar does not — the drop
        // order backwards.
        val briefingAllowed = !calendarWanted || showCalendar
        if (briefingAllowed && options.briefing && hasBriefing && left >= GAP + briefingLine(fs)) {
            showBriefing = true
            left -= GAP + briefingLine(fs)
        }
        // The weather line is the thinnest thing here and goes first after the briefing —
        // it is decoration; the calendar line is a commitment.
        var showWeather = false
        val weatherAllowed = !(options.briefing && hasBriefing) || showBriefing
        if (weatherAllowed && options.weather && hasWeather && left >= WEATHER_GAP + weatherLine(fs)) {
            showWeather = true
            left -= WEATHER_GAP + weatherLine(fs)
        }
        val wanted = minOf(options.extraItems.coerceIn(0, 6), available.coerceAtLeast(0))
        // The first row carries the section gap; rows after it sit flush under a divider.
        val fits = if (left < GAP + extraRow(fs)) 0
        else 1 + floor((left - GAP - extraRow(fs)) / extraRow(fs)).toInt()

        return Result(
            showNext = true,
            nextTwoLines = twoLines,
            showCapture = options.capture,
            captureCompact = compact,
            showCalendar = showCalendar,
            showBriefing = showBriefing,
            extraRows = minOf(wanted, fits),
            showWeather = showWeather
        )
    }
}
