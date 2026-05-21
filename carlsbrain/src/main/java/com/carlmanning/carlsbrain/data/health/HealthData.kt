package com.carlmanning.carlsbrain.data.health

import java.time.Instant
import java.time.LocalDate

data class DailySleepData(
    val date: LocalDate,
    val durationHours: Double,
    val deepHours: Double = 0.0,
    val remHours: Double = 0.0,
    val lightHours: Double = 0.0
)

data class DailyNutritionData(
    val date: LocalDate,
    val calories: Double,
    val proteinGrams: Double = 0.0,
    val carbsGrams: Double = 0.0,
    val fatGrams: Double = 0.0,
    val lastMealTime: Instant? = null
)

data class DailyWeightData(
    val date: LocalDate,
    val weightKg: Double
)

data class DailyStepsData(
    val date: LocalDate,
    val steps: Long
)

enum class HealthPermissionStatus {
    CHECKING, NOT_SUPPORTED, NEEDS_INSTALL, NEEDS_PERMISSION, GRANTED
}

data class HealthSnapshot(
    val sleep: List<DailySleepData> = emptyList(),
    val nutrition: List<DailyNutritionData> = emptyList(),
    val weight: List<DailyWeightData> = emptyList(),
    val steps: List<DailyStepsData> = emptyList(),
    val fetchedAt: Long = System.currentTimeMillis()
) {
    fun toContextString(): String {
        val parts = mutableListOf<String>()

        sleep.lastOrNull()?.let { last ->
            val avg7 = sleep.takeLast(8).dropLast(1).takeIf { it.isNotEmpty() }
                ?.map { it.durationHours }?.average()
            val avgStr = avg7?.let { " (7d avg: ${"%.1f".format(it)}h)" } ?: ""
            parts += "Sleep last night: ${"%.1f".format(last.durationHours)}h$avgStr"
            if (last.deepHours > 0.1) parts += "deep ${"%.1f".format(last.deepHours)}h"
        }

        nutrition.lastOrNull()?.let { last ->
            val cals = last.calories.toInt()
            if (cals > 0) parts += "Calories today: ${cals}kcal"
            last.lastMealTime?.let { t ->
                val h = ((System.currentTimeMillis() - t.toEpochMilli()) / 3_600_000).toInt()
                if (h in 1..24) parts += "last meal ${h}h ago"
            }
        }

        weight.lastOrNull()?.let { last ->
            val trend = if (weight.size >= 7) {
                val diff = last.weightKg - weight[weight.size - 7].weightKg
                when {
                    diff < -0.2 -> " (trending ↓)"
                    diff > 0.2 -> " (trending ↑)"
                    else -> " (stable)"
                }
            } else ""
            parts += "Weight: ${"%.1f".format(last.weightKg)}kg$trend"
        }

        steps.lastOrNull()?.let { last ->
            val avg = steps.takeLast(8).dropLast(1).takeIf { it.isNotEmpty() }
                ?.map { it.steps }?.average()?.toLong()
            val avgStr = avg?.let { ", 7d avg: $it" } ?: ""
            parts += "Steps yesterday: ${last.steps}$avgStr"
        }

        return if (parts.isEmpty()) "" else "Health: ${parts.joinToString(". ")}."
    }

    fun baselineMemorySection(): String? {
        if (sleep.size < 7 && weight.size < 7) return null
        val lines = mutableListOf<String>()
        if (sleep.size >= 7) {
            val avg = sleep.takeLast(7).map { it.durationHours }.average()
            val avgDeep = sleep.takeLast(7).map { it.deepHours }.average()
            lines += "- 7-day sleep average: ${"%.1f".format(avg)}h (deep ${"%.1f".format(avgDeep)}h)"
        }
        if (weight.isNotEmpty()) {
            val latest = weight.last().weightKg
            lines += "- Latest weight: ${"%.1f".format(latest)}kg"
        }
        if (steps.size >= 7) {
            val avg = steps.takeLast(7).map { it.steps }.average().toLong()
            lines += "- 7-day step average: $avg steps/day"
        }
        return if (lines.isEmpty()) null else "## Health Baselines\n${lines.joinToString("\n")}"
    }
}
