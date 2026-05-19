package com.carlmanning.carlsbrain.data.remote

import android.app.PendingIntent
import android.content.Context
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import kotlinx.coroutines.Dispatchers
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

class CalendarRepository(context: Context) {

    private val authManager = GoogleAuthManager(context)
    private val httpClient = CarlsBrainApp.httpClient
    private val json = appJson

    suspend fun getUpcomingEvents(daysAhead: Int = 14): Result<List<CalendarEvent>> = runCatching {
        val token = fetchToken()

        val timeMin = URLEncoder.encode(Instant.now().toString(), "UTF-8")
        val timeMax = URLEncoder.encode(
            Instant.now().plus(daysAhead.toLong(), ChronoUnit.DAYS).toString(), "UTF-8"
        )

        val calendars = fetchCalendarList(token)
        val allEvents = mutableListOf<CalendarEvent>()
        for (cal in calendars) {
            val encodedId = URLEncoder.encode(cal.id, "UTF-8")
            val url = "https://www.googleapis.com/calendar/v3/calendars/$encodedId/events" +
                    "?timeMin=$timeMin&timeMax=$timeMax" +
                    "&singleEvents=true&orderBy=startTime&maxResults=100"
            val body = withContext(Dispatchers.IO) {
                val resp = httpClient.newCall(
                    Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
                ).execute()
                if (!resp.isSuccessful) return@withContext null
                resp.body?.string()
            } ?: continue
            json.decodeFromString<CalendarEventsResponse>(body)
                .items
                .mapNotNull { it.toDomain(cal.colorHex, cal.summary) }
                .let { allEvents.addAll(it) }
        }
        allEvents.sortedBy { it.startMs }
    }

    private suspend fun fetchCalendarList(token: String): List<CalendarListEntry> {
        val url = "https://www.googleapis.com/calendar/v3/calendarList?minAccessRole=reader"
        val body = withContext(Dispatchers.IO) {
            val resp = httpClient.newCall(
                Request.Builder().url(url).addHeader("Authorization", "Bearer $token").build()
            ).execute()
            if (!resp.isSuccessful) {
                val errBody = resp.body?.string().orEmpty().take(200)
                error("Calendar auth failed (${resp.code}): $errBody")
            }
            resp.body?.string()
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
            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) error("Calendar API ${response.code}: ${response.body?.string()}")
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
    val colorId: String? = null
) {
    val colorHex: String? get() = backgroundColor
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
