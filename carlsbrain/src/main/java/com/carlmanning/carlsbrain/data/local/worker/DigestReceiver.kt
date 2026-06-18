package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

/**
 * AlarmManager-based replacement for DigestNotificationWorker.
 * Fires the morning digest at the user-configured time and re-arms for the next day.
 */
class DigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra(EXTRA_HOUR, 6)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 30)

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postDigest(context)
                // Re-arm for same time tomorrow
                DigestAlarmScheduler.schedule(context, hour, minute)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun postDigest(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val db = AppDatabase.getInstance(context)
        val prefs = CarlsBrainApp.userPreferences
        val claude = CarlsBrainApp.claudeClient

        val priorityTodos = db.todoDao().getVisibleNonVaultTodos().first()
            .filter { it.priority in listOf(0, 1) && !it.isDone }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(context).getUpcomingEvents(daysAhead = 1)
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
            val prompt = "Give Carl a concise morning briefing in 2 sentences max.\nToday: $eventsStr\nPriority tasks: $todosStr\nEnd with one quick nudge. No bullet points."
            withTimeoutOrNull(10_000L) {
                claude.chat(
                    messages = listOf(ApiMessage("user", prompt)),
                    systemPrompt = "You are Carl's assistant. Carl has ADHD and works as an NSW SES Deputy. Be direct and warm.",
                    model = ClaudeClient.HAIKU
                ).getOrNull()
            }
        }.getOrNull() ?: buildFallback(todayEvents, priorityTodos)

        val tapIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Good morning, Carl")
            .setContentText(briefingText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefingText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    private fun buildFallback(events: List<CalendarEvent>, todos: List<TodoEntity>): String {
        val parts = mutableListOf<String>()
        if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} today")
        if (todos.isNotEmpty()) parts.add("${todos.size} priority task${if (todos.size > 1) "s" else ""} need attention")
        return if (parts.isEmpty()) "Tap to open Carl's Brain" else parts.joinToString(" · ")
    }

    companion object {
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val CHANNEL_ID = "morning_digest"
        const val NOTIFICATION_ID = 1001
        const val ALARM_REQUEST_CODE = 4999
    }
}

object DigestAlarmScheduler {

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, DigestReceiver::class.java).apply {
            putExtra(DigestReceiver.EXTRA_HOUR, hour)
            putExtra(DigestReceiver.EXTRA_MINUTE, minute)
        }
        val pi = PendingIntent.getBroadcast(
            context, DigestReceiver.ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val triggerAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, DigestReceiver.ALARM_REQUEST_CODE,
            Intent(context, DigestReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }
}
