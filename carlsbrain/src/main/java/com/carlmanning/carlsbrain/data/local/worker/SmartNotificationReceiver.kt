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
 * BroadcastReceiver for smart notification slots. Uses AlarmManager.setExactAndAllowWhileIdle()
 * so notifications fire at the correct time even through Doze mode. On each fire the receiver
 * posts the notification and re-arms the alarm for the same time tomorrow.
 */
class SmartNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slotName = intent.getStringExtra(EXTRA_SLOT) ?: return
        val slot = runCatching { SmartNotificationWorker.Slot.valueOf(slotName) }.getOrNull() ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                postNotification(context, slot)
                // Re-arm for tomorrow at the same hour/minute stored in the intent
                val hour = intent.getIntExtra(EXTRA_HOUR, slot.defaultHour)
                val minute = intent.getIntExtra(EXTRA_MINUTE, slot.defaultMinute)
                SmartNotificationAlarmScheduler.scheduleSlot(context, slot, enabled = true, hour = hour, minute = minute)
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun postNotification(context: Context, slot: SmartNotificationWorker.Slot) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val db = AppDatabase.getInstance(context)
        val prefs = CarlsBrainApp.userPreferences
        val claude = CarlsBrainApp.claudeClient

        val priorityTodos = db.todoDao().getVisibleNonVaultTodos().first()
            .filter { !it.isDone }
            .let { todos ->
                when (slot) {
                    SmartNotificationWorker.Slot.MORNING -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.MIDDAY -> todos.filter { it.priority == 0 }
                    SmartNotificationWorker.Slot.AFTERNOON -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.EVENING -> todos
                }
            }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(context).getUpcomingEvents(daysAhead = 2)
                .getOrThrow()
                .filter {
                    val eventDate = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                    when (slot) {
                        SmartNotificationWorker.Slot.MORNING,
                        SmartNotificationWorker.Slot.MIDDAY,
                        SmartNotificationWorker.Slot.AFTERNOON -> eventDate == today
                        SmartNotificationWorker.Slot.EVENING -> eventDate == today.plusDays(1)
                    }
                }
        }.getOrElse { emptyList() }

        val aiEnabled = prefs.notifAiEnabled.first()
        val apiKey = prefs.anthropicApiKey.first()

        val notificationText: String = if (aiEnabled && apiKey.isNotBlank()) {
            runCatching {
                val prompt = buildAiPrompt(slot, todayEvents, priorityTodos)
                withTimeoutOrNull(10_000L) {
                    claude.chat(
                        messages = listOf(ApiMessage("user", prompt)),
                        systemPrompt = "You are Carl's assistant. Carl has ADHD and works as an NSW SES Deputy. Be direct and warm. One sentence max.",
                        model = ClaudeClient.HAIKU
                    ).getOrNull()
                }
            }.getOrNull() ?: buildFallbackText(slot, todayEvents, priorityTodos)
        } else {
            buildFallbackText(slot, todayEvents, priorityTodos)
        }

        val tapIntent = PendingIntent.getActivity(
            context, slot.notificationId,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, slot.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(slot.title)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (slot == SmartNotificationWorker.Slot.AFTERNOON) {
            priorityTodos.filter { it.priority == 0 }.take(2).forEachIndexed { index, todo ->
                val doneIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                    action = ReminderActionReceiver.ACTION_DONE
                    putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderActionReceiver.EXTRA_TODO_TITLE, todo.title)
                }
                val donePendingIntent = PendingIntent.getBroadcast(
                    context, AFTERNOON_ACTION_BASE_ID + index, doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val shortTitle = if (todo.title.length > 24) todo.title.take(21) + "…" else todo.title
                builder.addAction(0, "Done: $shortTitle", donePendingIntent)
            }
        }

        NotificationManagerCompat.from(context).notify(slot.notificationId, builder.build())
    }

    private fun buildAiPrompt(slot: SmartNotificationWorker.Slot, events: List<CalendarEvent>, todos: List<TodoEntity>): String {
        val eventsStr = if (events.isEmpty()) "no calendar events" else events.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }
        val todosStr = if (todos.isEmpty()) "no pending tasks" else todos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }
        return when (slot) {
            SmartNotificationWorker.Slot.MORNING -> "Give Carl a concise morning briefing in 1 sentence. Today: $eventsStr. Priority tasks: $todosStr. End with one quick nudge. No bullet points."
            SmartNotificationWorker.Slot.MIDDAY -> "Quick midday check-in for Carl in 1 sentence. Urgent tasks right now: $todosStr. Events: $eventsStr. Be direct."
            SmartNotificationWorker.Slot.AFTERNOON -> "Afternoon nudge for Carl in 1 sentence. Top urgent tasks: $todosStr. Events remaining today: $eventsStr. Encourage action."
            SmartNotificationWorker.Slot.EVENING -> "Evening prep for Carl in 1 sentence. Tomorrow: $eventsStr. Still incomplete today: $todosStr. Suggest wrapping up or planning ahead."
        }
    }

    private fun buildFallbackText(slot: SmartNotificationWorker.Slot, events: List<CalendarEvent>, todos: List<TodoEntity>): String {
        val parts = mutableListOf<String>()
        when (slot) {
            SmartNotificationWorker.Slot.MORNING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} today")
                if (todos.isNotEmpty()) parts.add("${todos.size} priority task${if (todos.size > 1) "s" else ""}")
            }
            SmartNotificationWorker.Slot.MIDDAY -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} urgent item${if (todos.size > 1) "s" else ""} need attention")
                else parts.add("All clear — no urgent tasks")
            }
            SmartNotificationWorker.Slot.AFTERNOON -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} still pending")
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} remaining")
            }
            SmartNotificationWorker.Slot.EVENING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} tomorrow")
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} incomplete")
            }
        }
        return if (parts.isEmpty()) "Tap to open Carl's Brain" else parts.joinToString(" · ")
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        private const val AFTERNOON_ACTION_BASE_ID = 3000
    }
}

// Default times for each slot (used as fallback on re-arm)
private val SmartNotificationWorker.Slot.defaultHour: Int get() = when (this) {
    SmartNotificationWorker.Slot.MORNING -> 7
    SmartNotificationWorker.Slot.MIDDAY -> 12
    SmartNotificationWorker.Slot.AFTERNOON -> 15
    SmartNotificationWorker.Slot.EVENING -> 20
}
private val SmartNotificationWorker.Slot.defaultMinute: Int get() = 0
