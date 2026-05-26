package com.carlmanning.carlsbrain.data.health

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.NutritionRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.StepsRecord
import androidx.health.connect.client.records.WeightRecord
import androidx.health.connect.client.records.metadata.DataOrigin
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import java.time.LocalDate
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

    // ── Trusted data source packages ─────────────────────────────────────────
    // Nutrition and weight write directly to Health Connect — no bridge needed.
    // Verify Withings package: Settings → Apps → Health Mate → App info.
    private val MFP_PKG      = "com.myfitnesspal.android"
    private val WITHINGS_PKG = "com.withings.wiscale2"

    // Garmin does NOT write to Health Connect natively.
    // Install Health Sync (~$5) → source: Google Fit → dest: Health Connect,
    // enable Steps + Sleep + Heart Rate, then set useGarminBridge = true.
    private val HEALTH_SYNC_PKG  = "nl.appyhapps.healthsync"
    private val useGarminBridge  = false  // flip to true once Health Sync is confirmed syncing

    suspend fun readHealthData(days: Int): HealthSnapshot {
        val c = client ?: throw SecurityException("Health Connect client unavailable")
        val zone = ZoneId.systemDefault()
        val start = LocalDate.now(zone).minusDays(days.toLong()).atStartOfDay(zone).toInstant()
        val end = Instant.now()
        return HealthSnapshot(
            sleep     = readSleep(c, start, end, zone),
            nutrition = readNutrition(c, start, end, zone),
            weight    = readWeight(c, start, end, zone),
            steps     = readSteps(c, start, end, zone)
        )
    }

    private suspend fun readSleep(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailySleepData> = try {
        // Filter to Garmin bridge when enabled; otherwise read all sources.
        // Group by END date so a session starting 11 pm shows on the correct morning.
        val origins = if (useGarminBridge) setOf(DataOrigin(HEALTH_SYNC_PKG)) else emptySet()
        c.readRecords(
            ReadRecordsRequest(SleepSessionRecord::class, TimeRangeFilter.between(start, end),
                dataOriginFilter = origins)
        ).records
            .groupBy { it.endTime.atZone(zone).toLocalDate() }
            .map { (date, sessions) ->
                var totalMs = 0L; var deepMs = 0L; var remMs = 0L; var lightMs = 0L
                sessions.forEach { s ->
                    totalMs += s.endTime.toEpochMilli() - s.startTime.toEpochMilli()
                    s.stages.forEach { stage ->
                        val ms = stage.endTime.toEpochMilli() - stage.startTime.toEpochMilli()
                        when (stage.stage) {
                            SleepSessionRecord.STAGE_TYPE_DEEP     -> deepMs  += ms
                            SleepSessionRecord.STAGE_TYPE_REM      -> remMs   += ms
                            SleepSessionRecord.STAGE_TYPE_LIGHT,
                            SleepSessionRecord.STAGE_TYPE_SLEEPING -> lightMs += ms
                            else -> {}
                        }
                    }
                }
                DailySleepData(date, totalMs / 3_600_000.0, deepMs / 3_600_000.0,
                    remMs / 3_600_000.0, lightMs / 3_600_000.0)
            }.sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readNutrition(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyNutritionData> = try {
        // MFP only — prevents any other food-tracking app from inflating calories.
        // If nutrition shows empty, verify MFP is writing to Health Connect and
        // that MFP_PKG matches the installed package name.
        c.readRecords(
            ReadRecordsRequest(NutritionRecord::class, TimeRangeFilter.between(start, end),
                dataOriginFilter = setOf(DataOrigin(MFP_PKG)))
        ).records.groupBy { it.startTime.atZone(zone).toLocalDate() }
            .map { (date, records) ->
                var cal = 0.0; var pro = 0.0; var carb = 0.0; var fat = 0.0
                var lastMeal: Instant? = null
                records.forEach { r ->
                    cal  += r.energy?.inKilocalories ?: 0.0
                    pro  += r.protein?.inGrams ?: 0.0
                    carb += r.totalCarbohydrate?.inGrams ?: 0.0
                    fat  += r.totalFat?.inGrams ?: 0.0
                    if (lastMeal == null || r.endTime > lastMeal!!) lastMeal = r.endTime
                }
                DailyNutritionData(date, cal, pro, carb, fat, lastMeal)
            }.sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readWeight(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyWeightData> = try {
        // Withings only — prevents manual entries or other scales from stacking.
        // If weight shows empty, verify WITHINGS_PKG matches Health Mate's package
        // under Settings → Apps on your phone.
        c.readRecords(
            ReadRecordsRequest(WeightRecord::class, TimeRangeFilter.between(start, end),
                dataOriginFilter = setOf(DataOrigin(WITHINGS_PKG)))
        ).records.groupBy { it.time.atZone(zone).toLocalDate() }
            .mapValues { (_, recs) -> recs.maxByOrNull { it.time }!! }
            .map { (date, r) -> DailyWeightData(date, r.weight.inKilograms) }
            .sortedBy { it.date }
    } catch (e: SecurityException) { throw e } catch (e: Exception) { emptyList() }

    private suspend fun readSteps(
        c: HealthConnectClient, start: Instant, end: Instant, zone: ZoneId
    ): List<DailyStepsData> = try {
        // Filter to Garmin bridge when enabled; otherwise read all sources.
        // emptySet() = no filter (all sources) — avoids depending on a specific
        // fallback package (e.g. Google Fit) that may not be installed.
        val origins = if (useGarminBridge) setOf(DataOrigin(HEALTH_SYNC_PKG)) else emptySet()
        val byDate = c.readRecords(
            ReadRecordsRequest(StepsRecord::class, TimeRangeFilter.between(start, end),
                dataOriginFilter = origins)
        ).records
            .groupBy { it.startTime.atZone(zone).toLocalDate() }
            .mapValues { (_, recs) -> recs.sumOf { it.count } }
        // Fill gaps so every day in the window has an entry (0 for days with no data).
        val startDate = start.atZone(zone).toLocalDate()
        val endDate   = minOf(end.atZone(zone).toLocalDate(), LocalDate.now(zone))
        generateSequence(startDate) { it.plusDays(1) }
            .takeWhile { !it.isAfter(endDate) }
            .map { date -> DailyStepsData(date = date, steps = byDate[date] ?: 0L) }
            .toList()
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
