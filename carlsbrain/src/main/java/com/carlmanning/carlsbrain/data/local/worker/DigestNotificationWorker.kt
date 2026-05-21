package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class DigestNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)
        val prefs = CarlsBrainApp.userPreferences
        val claude = CarlsBrainApp.claudeClient

        val priorityTodos = db.todoDao().getVisibleTodos().first()
            .filter { it.priority in listOf(0, 1) && !it.isDone }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(applicationContext).getUpcomingEvents(daysAhead = 1)
                .getOrThrow()
                .filter { Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == today }
        }.getOrElse { emptyList() }

        val briefingText = runCatching {
            val apiKey = prefs.anthropicApiKey.first()
            if (apiKey.isBlank()) return@runCatching null

            val eventsStr = if (todayEvents.isEmpty()) "no calendar events today"
                            else todayEvents.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }
            val todosStr = if (priorityTodos.isEmpty()) "no urgent or high-priority tasks"
                           else priorityTodos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }

            val prompt = """Give Carl a concise morning briefing in 2 sentences max.
Today: $eventsStr
Priority tasks: $todosStr
End with one quick nudge. No bullet points."""

            withTimeoutOrNull(10_000L) {
                claude.chat(
                    messages = listOf(ApiMessage("user", prompt)),
                    systemPrompt = "You are Carl's assistant. Carl has ADHD and works as an NSW SES Deputy. Be direct and warm.",
                    model = ClaudeClient.HAIKU
                ).getOrNull()
            }
        }.getOrNull()

        val notificationText = briefingText
            ?: buildFallbackText(todayEvents, priorityTodos)

        val tapIntent = PendingIntent.getActivity(
            applicationContext,
            0,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Good morning, Carl")
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)

        return Result.success()
    }

    private fun buildFallbackText(events: List<CalendarEvent>, todos: List<TodoEntity>): String {
        val parts = mutableListOf<String>()
        if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} today")
        if (todos.isNotEmpty()) parts.add("${todos.size} priority task${if (todos.size > 1) "s" else ""} need attention")
        return if (parts.isEmpty()) "Tap to open Carl's Brain" else parts.joinToString(" · ")
    }

    companion object {
        const val CHANNEL_ID = "morning_digest"
        private const val NOTIFICATION_ID = 1001
    }
}
