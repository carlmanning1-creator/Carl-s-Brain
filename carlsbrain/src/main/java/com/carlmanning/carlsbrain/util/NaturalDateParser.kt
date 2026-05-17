package com.carlmanning.carlsbrain.util

import java.util.Calendar

object NaturalDateParser {

    private val DAY_NAMES = mapOf(
        "monday" to Calendar.MONDAY,    "mon" to Calendar.MONDAY,
        "tuesday" to Calendar.TUESDAY,  "tue" to Calendar.TUESDAY,
        "wednesday" to Calendar.WEDNESDAY, "wed" to Calendar.WEDNESDAY,
        "thursday" to Calendar.THURSDAY,"thu" to Calendar.THURSDAY,
        "friday" to Calendar.FRIDAY,    "fri" to Calendar.FRIDAY,
        "saturday" to Calendar.SATURDAY,"sat" to Calendar.SATURDAY,
        "sunday" to Calendar.SUNDAY,    "sun" to Calendar.SUNDAY
    )

    private val MONTH_NAMES = mapOf(
        "jan" to 0, "january" to 0,
        "feb" to 1, "february" to 1,
        "mar" to 2, "march" to 2,
        "apr" to 3, "april" to 3,
        "may" to 4,
        "jun" to 5, "june" to 5,
        "jul" to 6, "july" to 6,
        "aug" to 7, "august" to 7,
        "sep" to 8, "september" to 8,
        "oct" to 9, "october" to 9,
        "nov" to 10, "november" to 10,
        "dec" to 11, "december" to 11
    )

    fun parse(input: String): Long? {
        val s = input.trim().lowercase()
        if (s.length < 2) return null
        val cal = midnight()
        return when {
            s == "today" -> cal.timeInMillis
            s == "tomorrow" -> { cal.add(Calendar.DAY_OF_YEAR, 1); cal.timeInMillis }
            s == "next week" -> { cal.add(Calendar.WEEK_OF_YEAR, 1); cal.timeInMillis }
            s == "next month" -> { cal.add(Calendar.MONTH, 1); cal.timeInMillis }
            s == "end of week" || s == "eow" -> nextDayOfWeek(cal, Calendar.FRIDAY)
            s == "end of month" || s == "eom" -> endOfMonth(cal)

            s.matches(Regex("""in (\d+) days?""")) -> {
                val d = Regex("""in (\d+) days?""").find(s)!!.groupValues[1].toInt()
                cal.add(Calendar.DAY_OF_YEAR, d); cal.timeInMillis
            }
            s.matches(Regex("""in (\d+) weeks?""")) -> {
                val w = Regex("""in (\d+) weeks?""").find(s)!!.groupValues[1].toInt()
                cal.add(Calendar.WEEK_OF_YEAR, w); cal.timeInMillis
            }
            s.matches(Regex("""in (\d+) months?""")) -> {
                val m = Regex("""in (\d+) months?""").find(s)!!.groupValues[1].toInt()
                cal.add(Calendar.MONTH, m); cal.timeInMillis
            }

            s.startsWith("next ") -> {
                val rest = s.removePrefix("next ")
                val dow = DAY_NAMES[rest] ?: return null
                cal.add(Calendar.DAY_OF_YEAR, 7)
                nextDayOfWeek(cal, dow)
            }

            DAY_NAMES.containsKey(s) -> {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                nextDayOfWeek(cal, DAY_NAMES[s]!!)
            }

            else -> parseExplicitDate(s)
        }
    }

    private fun midnight(): Calendar = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }

    private fun nextDayOfWeek(cal: Calendar, dow: Int): Long {
        while (cal.get(Calendar.DAY_OF_WEEK) != dow) cal.add(Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    private fun endOfMonth(cal: Calendar): Long {
        cal.set(Calendar.DAY_OF_MONTH, cal.getActualMaximum(Calendar.DAY_OF_MONTH))
        return cal.timeInMillis
    }

    private fun parseExplicitDate(s: String): Long? {
        // DD/MM or DD/MM/YY or DD/MM/YYYY
        val slashMatch = Regex("""^(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?$""").find(s)
        if (slashMatch != null) {
            val day = slashMatch.groupValues[1].toIntOrNull() ?: return null
            val month = (slashMatch.groupValues[2].toIntOrNull() ?: return null) - 1
            val yearRaw = slashMatch.groupValues[3].toIntOrNull()
            val year = when {
                yearRaw == null -> null
                yearRaw < 100 -> 2000 + yearRaw
                else -> yearRaw
            }
            val cal = midnight()
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.MONTH, month)
            if (year != null) cal.set(Calendar.YEAR, year)
            else if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.YEAR, 1)
            return cal.timeInMillis
        }

        // "27 june" or "june 27" or "27th june"
        val parts = s.replace(Regex("""(\d+)(st|nd|rd|th)"""), "$1").trim().split(Regex("""\s+"""))
        if (parts.size == 2) {
            val (day, month) = tryDayMonth(parts[0], parts[1])
                ?: tryDayMonth(parts[1], parts[0])
                ?: return null
            val cal = midnight()
            cal.set(Calendar.DAY_OF_MONTH, day)
            cal.set(Calendar.MONTH, month)
            if (cal.timeInMillis < System.currentTimeMillis()) cal.add(Calendar.YEAR, 1)
            return cal.timeInMillis
        }

        return null
    }

    private fun tryDayMonth(dayStr: String, monthStr: String): Pair<Int, Int>? {
        val day = dayStr.toIntOrNull() ?: return null
        val month = MONTH_NAMES[monthStr] ?: return null
        if (day < 1 || day > 31) return null
        return day to month
    }
}
