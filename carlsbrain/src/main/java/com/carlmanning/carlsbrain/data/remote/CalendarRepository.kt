package com.carlmanning.carlsbrain.data.remote

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.toEntity
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class AuthResolutionException(val pendingIntent: PendingIntent) : Exception("Google re-authorization required")

/** One of Carl's Google calendars, as Settings needs to show it. */
data class AvailableCalendar(
    val id: String,
    val name: String,
    val isPrimary: Boolean
)

class CalendarRepository(context: Context) {

    private val authManager = GoogleAuthManager(context)
    private val httpClient = CarlsBrainApp.httpClient
    private val json = appJson
    private val db = AppDatabase.getInstance(context)

    suspend fun getUpcomingEvents(daysAhead: Int = 14): Result<List<CalendarEvent>> = runCatching {
        val token = fetchToken()

        val timeMin = URLEncoder.encode(Instant.now().toString(), "UTF-8")
        val timeMax = URLEncoder.encode(
            Instant.now().plus(daysAhead.toLong(), ChronoUnit.DAYS).toString(), "UTF-8"
        )

        // Read the exclusions here rather than taking them as a parameter: every consumer
        // (briefing, Dashboard, widget, digest) goes through this one fetch, so filtering
        // at the source makes an excluded calendar disappear everywhere at once.
        val excluded = runCatching {
            CarlsBrainApp.userPreferences.excludedCalendarIds.first()
        }.getOrDefault(emptySet())

        val calendars = fetchCalendarList(token).filter { it.isIncluded(excluded) }
        val allEvents = mutableListOf<CalendarEvent>()
        for (cal in calendars) {
            val encodedId = URLEncoder.encode(cal.id, "UTF-8")
            val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedId/events" +
                    "?timeMin=$timeMin&timeMax=$timeMax" +
                    "&singleEvents=true&orderBy=startTime&maxResults=100"
            val body = withContext(Dispatchers.IO) {
                httpClient.newCall(
                    Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
                ).execute().use { resp ->
                    if (!resp.isSuccessful) return@withContext null
                    resp.body?.string()
                }
            } ?: continue
            json.decodeFromString<CalendarEventsResponse>(body)
                .items
                .mapNotNull { it.toDomain(cal.colorHex, cal.summary) }
                .let { allEvents.addAll(it) }
        }
        val sorted = allEvents.sortedBy { it.startMs }

        // Cache the fresh result so it's available when offline
        val now = System.currentTimeMillis()
        db.calendarEventDao().deleteAll()
        db.calendarEventDao().insertAll(sorted.map { it.toEntity(now) })

        sorted
    }

    /**
     * Call this with the Intent returned by the Google consent screen (StartIntentSenderForResult).
     * It exchanges the authorization code so the next authorize() call returns a valid token.
     */
    fun processConsentResult(data: Intent?) {
        runCatching { authManager.processConsentResult(data) }
    }

    /** Returns locally cached events (may be empty if never fetched) with the timestamp of the last cache fill. */
    suspend fun getCachedEvents(): Pair<List<CalendarEvent>, Long?> {
        val entities = db.calendarEventDao().getAllEventsOnce()
        val cachedAt = db.calendarEventDao().getLastCachedAt()
        return entities.map { it.toDomain() } to cachedAt
    }

    /**
     * The calendars Settings can offer toggles for. Uses the same token and calendar-list
     * call as [getUpcomingEvents] — no separate client or auth path.
     */
    suspend fun getAvailableCalendars(): Result<List<AvailableCalendar>> = runCatching {
        val token = fetchToken()
        fetchCalendarList(token)
            .filter { it.id.isNotBlank() }
            .map {
                AvailableCalendar(
                    id = it.id,
                    name = it.summary?.takeIf { s -> s.isNotBlank() } ?: it.id,
                    isPrimary = it.primary
                )
            }
            // Primary first, then alphabetical — Carl's own calendar is the anchor.
            .sortedWith(compareByDescending<AvailableCalendar> { it.isPrimary }.thenBy { it.name.lowercase() })
    }

    /**
     * Drops already-cached events belonging to [calendarNames] so an excluded calendar
     * stops showing in the offline/widget paths immediately, without waiting for the next
     * successful fetch to rewrite the cache. Cached rows carry `calendarName`, not the
     * calendar id, so the caller passes the display names it just listed.
     */
    suspend fun removeCachedEventsForCalendars(calendarNames: Set<String>) {
        if (calendarNames.isEmpty()) return
        val remaining = db.calendarEventDao().getAllEventsOnce()
            .filterNot { it.calendarName != null && it.calendarName in calendarNames }
        db.calendarEventDao().deleteAll()
        if (remaining.isNotEmpty()) db.calendarEventDao().insertAll(remaining)
    }

    private suspend fun fetchCalendarList(token: String): List<CalendarListEntry> {
        val url = "https://www.googleapis.com/calendar/v3/users/me/calendarList?minAccessRole=reader"
        val body = withContext(Dispatchers.IO) {
            httpClient.newCall(
                Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            ).execute().use { resp ->
                if (!resp.isSuccessful) {
                    val errBody = resp.body?.string().orEmpty().take(200)
                    error("Calendar auth failed (${resp.code}): $errBody")
                }
                resp.body?.string()
            }
        } ?: error("Empty response from calendar list")
        return json.decodeFromString<CalendarListResponse>(body).items
    }

    suspend fun createEvent(
        title: String,
        startMs: Long,
        endMs: Long,
        location: String? = null
    ): Result<Unit> = runCatching {
        val token = fetchToken()

        val fmt = DateTimeFormatter.ISO_OFFSET_DATE_TIME
        val zone = ZoneId.systemDefault()
        val startStr = Instant.ofEpochMilli(startMs).atZone(zone).format(fmt)
        val endStr = Instant.ofEpochMilli(endMs).atZone(zone).format(fmt)

        val bodyJson = buildJsonObject {
            put("summary", title)
            putJsonObject("start") { put("dateTime", startStr) }
            putJsonObject("end") { put("dateTime", endStr) }
            if (!location.isNullOrBlank()) put("location", location)
        }.toString()

        val requestBody = bodyJson.toByteArray().toRequestBody("application/json".toMediaType())
        val request = Request.Builder()
            .url("https://www.googleapis.com/calendar/v3/calendars/primary/events")
            .addHeader("Authorization", "Bearer $token")
            .post(requestBody)
            .build()

        withContext(Dispatchers.IO) {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("Calendar API ${response.code}: ${response.body?.string()}")
            }
        }
    }

    private suspend fun fetchToken(): String = suspendCancellableCoroutine { cont ->
        authManager.authorize(
            onSuccess = { token ->
                if (cont.isActive) cont.resume(token)
            },
            onResolutionRequired = { pi ->
                if (cont.isActive) cont.resumeWithException(AuthResolutionException(pi))
            },
            onError = { e ->
                if (cont.isActive) cont.resumeWithException(e)
            }
        )
    }
}

// ── DTO models ────────────────────────────────────────────────────────────────

@Serializable
private data class CalendarListResponse(
    val items: List<CalendarListEntry> = emptyList()
)

@Serializable
private data class CalendarListEntry(
    val id: String = "",
    val summary: String? = null,
    val backgroundColor: String? = null,
    val foregroundColor: String? = null,
    val colorId: String? = null,
    val primary: Boolean = false
) {
    val colorHex: String? get() = backgroundColor

    /**
     * The primary calendar is never excludable: an exclusion that somehow names it
     * (hand-edited prefs, an id that later became primary) is ignored so Carl cannot
     * lock himself out of his own events.
     */
    fun isIncluded(excludedIds: Set<String>): Boolean =
        primary || id == "primary" || id !in excludedIds
}

@Serializable
private data class CalendarEventsResponse(
    val items: List<CalendarEventDto> = emptyList()
)

@Serializable
private data class CalendarEventDto(
    val id: String = "",
    val summary: String? = null,
    val start: EventDateTimeDto = EventDateTimeDto(),
    val end: EventDateTimeDto = EventDateTimeDto(),
    val location: String? = null,
    val colorId: String? = null
) {
    fun toDomain(calendarColor: String? = null, calendarName: String? = null): CalendarEvent? {
        val isAllDay = start.date != null
        val startMs = start.toMillis() ?: return null
        val endMs = end.toMillis() ?: return null
        return CalendarEvent(
            id = id,
            title = summary ?: "(No title)",
            startMs = startMs,
            endMs = endMs,
            isAllDay = isAllDay,
            location = location,
            colorHex = calendarColor,
            calendarName = calendarName
        )
    }
}

@Serializable
private data class EventDateTimeDto(
    val dateTime: String? = null,
    val date: String? = null
) {
    fun toMillis(): Long? = runCatching {
        if (dateTime != null) {
            OffsetDateTime.parse(dateTime).toInstant().toEpochMilli()
        } else {
            LocalDate.parse(date!!).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        }
    }.getOrNull()
}
