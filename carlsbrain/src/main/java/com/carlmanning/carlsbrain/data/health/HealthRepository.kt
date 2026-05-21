package com.carlmanning.carlsbrain.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
// HealthPermission kept for requiredPermissions set used by the permission launcher in HealthScreen
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.request.AggregateGroupByPeriodRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
import java.time.Period
import java.time.ZoneId

class HealthRepository(private val context: Context) {

    private val client: HealthConnectClient? by lazy {
        runCatching {
            if (HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE)
                HealthConnectClient.getOrCreate(context)
            else null
        }.getOrNull()
    }

    fun getSdkStatus(): Int = runCatching {
        HealthConnectClient.getSdkStatus(context)
    }.getOrDefault(HealthConnectClient.SDK_UNAVAILABLE)

    val requiredPermissions = setOf(
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(NutritionRecord::class),
        HealthPermission.getReadPermission(WeightRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class),
    )

    suspend fun readHealthData(days: Int): HealthSnapshot {
        // Let SecurityException propagate — caller detects permission denial this way.
        val c = client ?: throw SecurityException("Health Connect client unavailable")
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(days.toLong()).atStartOfDay(zone).toInstant()
        val end = Instant.now()
        return HealthSnapshot(
            sleep = readSleep(c, start, end, zone),
            nutrition = readNutrition(c, start, end, zone),
            weight = readWeight(c, start, end, zone),
            steps = readSteps(c, start, end, zone)
        )
    }

    private suspend fun readSleep(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailySleepData> = try {
        c.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end))
        ).records.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .map { (date, sessions) ->
                var totalMs = 0L; var deepMs = 0L; var remMs = 0L; var lightMs = 0L
                sessions.forEach { s ->
                    totalMs += s.endTime.toEpochMilli() - s.startTime.toEpochMilli()
                    s.stages.forEach { stage ->
                        val ms = stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP -> deepMs += ms
                            SleepSessionRecord.STAGE_TYPE_REM -> remMs += ms
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> lightMs += ms
                            else -> {}
                        }
                    }
                }
                DailySleepData(date, totalMs / 3_600_000.0, deepMs / 3_600_000.0, remMs / 3_600_000.0, lightMs / 3_600_000.0)
            }.sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readNutrition(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyNutritionData> = try {
        c.readRecords(
            ReadRecordsRequest(NutritionRecord::class, TimeRangeFilter.between(start, end))
        ).records.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .map { (date, records) ->
                var cal = 0.0; var pro = 0.0; var carb = 0.0; var fat = 0.0
                var lastMeal: Instant? = null
                records.forEach { r ->
                    cal += r.energy?.inKilocalories ?: 0.0
                    pro += r.protein?.inGrams ?: 0.0
                    carb += r.totalCarbohydrate?.inGrams ?: 0.0
                    fat += r.totalFat?.inGrams ?: 0.0
                    if (lastMeal == null || r.endTime > lastMeal!!) lastMeal = r.endTime
                }
                DailyNutritionData(date, cal, pro, carb, fat, lastMeal)
            }.sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readWeight(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyWeightData> = try {
        c.readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end))
        ).records.groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, recs) -> recs.maxByOrNull { it.time }!! }
            .map { (date, r) -> DailyWeightData(date, r.weight.inKilograms) }
            .sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readSteps(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyStepsData> = try {
        c.aggregateGroupByPeriod(
            AggregateGroupByPeriodRequest(
                metrics = setOf(StepsRecord.COUNT_TOTAL),
                timeRangeFilter = TimeRangeFilter.between(start, end),
                timeRangeSlicer = Period.ofDays(1)
            )
        ).map { bucket ->
            DailyStepsData(
                date = bucket.startTime.atZone(zone).toLocalDate(),
                steps = bucket.result[StepsRecord.COUNT_TOTAL] ?: 0L
            )
        }.sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    companion object {
        @Volatile private var cachedSnapshot: HealthSnapshot? = null

        fun getCachedContextString(): String = cachedSnapshot?.toContextString() ?: ""

        fun updateCache(snapshot: HealthSnapshot) { cachedSnapshot = snapshot }

        fun isCacheStale(maxAgeMs: Long = 30 * 60 * 1000L): Boolean {
            val s = cachedSnapshot ?: return true
            return System.currentTimeMillis() - s.fetchedAt > maxAgeMs
        }
    }
}
