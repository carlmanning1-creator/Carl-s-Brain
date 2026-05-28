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

/**
 * Worker that fires for a specific notification slot (MORNING/MIDDAY/AFTERNOON/EVENING).
 * The slot is passed as an input data string via key [KEY_SLOT].
 *
 * For the AFTERNOON slot only, inline "Done" action buttons are added for the top 2 urgent todos.
 * Vault bucket todos are never included in any notification.
 */
class SmartNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val slotName = inputData.getString(KEY_SLOT) ?: Slot.MORNING.name
        val slot = runCatching { Slot.valueOf(slotName) }.getOrDefault(Slot.MORNING)

        val db = AppDatabase.getInstance(applicationContext)
        val prefs = CarlsBrainApp.userPreferences
        val claude = CarlsBrainApp.claudeClient

        // Always exclude vault items — notifications can appear on the lock screen
        val priorityTodos = db.todoDao().getVisibleNonVaultTodos().first()
            .filter { !it.isDone }
            .let { todos ->
                when (slot) {
                    Slot.MORNING -> todos.filter { it.priority in listOf(0, 1) }
                    Slot.MIDDAY -> todos.filter { it.priority == 0 } // urgent only for midday check-in
                    Slot.AFTERNOON -> todos.filter { it.priority in listOf(0, 1) }
                    Slot.EVENING -> todos // all incomplete todos for evening prep
                }
            }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(applicationContext).getUpcomingEvents(daysAhead = 2)
                .getOrThrow()
                .filter {
                    val eventDate = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                    when (slot) {
                        Slot.MORNING, Slot.MIDDAY, Slot.AFTERNOON -> eventDate == today
                        Slot.EVENING -> eventDate == today.plusDays(1) // tomorrow for evening prep
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
            applicationContext,
            slot.notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, slot.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(slot.title)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // AFTERNOON slot: inline "Done" action buttons for top 2 urgent todos
        if (slot == Slot.AFTERNOON) {
            priorityTodos.filter { it.priority == 0 }.take(2).forEachIndexed { index, todo ->
                val doneIntent = Intent(applicationContext, ReminderActionReceiver::class.java).apply {
                    action = ReminderActionReceiver.ACTION_DONE
                    putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderActionReceiver.EXTRA_TODO_TITLE, todo.title)
                }
                val donePendingIntent = PendingIntent.getBroadcast(
                    applicationContext,
                    AFTERNOON_ACTION_BASE_ID + index,
                    doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val shortTitle = if (todo.title.length > 24) todo.title.take(21) + "…" else todo.title
                builder.addAction(0, "Done: $shortTitle", donePendingIntent)
            }
        }

        NotificationManagerCompat.from(applicationContext)
            .notify(slot.notificationId, builder.build())

        return Result.success()
    }

    private fun buildAiPrompt(
        slot: Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
        val eventsStr = if (events.isEmpty()) "no calendar events"
        else events.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }
        val todosStr = if (todos.isEmpty()) "no pending tasks"
        else todos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }

        return when (slot) {
            Slot.MORNING ->
                "Give Carl a concise morning briefing in 1 sentence. Today: $eventsStr. Priority tasks: $todosStr. End with one quick nudge. No bullet points."
            Slot.MIDDAY ->
                "Quick midday check-in for Carl in 1 sentence. Urgent tasks right now: $todosStr. Events: $eventsStr. Be direct."
            Slot.AFTERNOON ->
                "Afternoon nudge for Carl in 1 sentence. Top urgent tasks: $todosStr. Events remaining today: $eventsStr. Encourage action."
            Slot.EVENING ->
                "Evening prep for Carl in 1 sentence. Tomorrow: $eventsStr. Still incomplete today: $todosStr. Suggest wrapping up or planning ahead."
        }
    }

    private fun buildFallbackText(
        slot: Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
        val parts = mutableListOf<String>()
        when (slot) {
            Slot.MORNING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} today")
                if (todos.isNotEmpty()) parts.add("${todos.size} priority task${if (todos.size > 1) "s" else ""}")
            }
            Slot.MIDDAY -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} urgent item${if (todos.size > 1) "s" else ""} need attention")
                else parts.add("All clear — no urgent tasks")
            }
            Slot.AFTERNOON -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} still pending")
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} remaining")
            }
            Slot.EVENING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} tomorrow")
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} incomplete")
            }
        }
        return if (parts.isEmpty()) "Tap to open Carl's Brain" else parts.joinToString(" · ")
    }

    enum class Slot(
        val channelId: String,
        val title: String,
        val notificationId: Int
    ) {
        MORNING("smart_morning", "Good morning, Carl", 2001),
        MIDDAY("smart_midday", "Midday check-in", 2002),
        AFTERNOON("smart_afternoon", "Afternoon check-in", 2003),
        EVENING("smart_evening", "Evening prep", 2004)
    }

    companion object {
        const val KEY_SLOT = "slot"
        private const val AFTERNOON_ACTION_BASE_ID = 3000
    }
}
