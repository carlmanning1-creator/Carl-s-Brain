package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
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
 * Single source of truth for the smart-notification digest text.
 *
 * Both [SmartNotificationWorker] (which posts the real notification) and the
 * Settings "digest preview" call into here, so the preview is identical to the
 * notification Carl actually receives by construction.
 *
 * SECURITY: the todo query is [com.carlmanning.carlsbrain.data.local.dao.TodoDao.getVisibleNonVaultTodos]
 * and must never be widened. Notifications appear on the lock screen, which sits
 * outside the app's biometric protection, so vault items must never reach here.
 */
object DigestGenerator {

    private const val SYSTEM_PROMPT =
        "You are Carl's assistant. Carl has ADHD and works as an NSW SES Deputy. Be direct and warm. One sentence max."

    private const val CLAUDE_TIMEOUT_MS = 10_000L

    /** Digest text plus the (vault-safe) todos it was built from. */
    data class Digest(
        val text: String,
        val todos: List<TodoEntity>
    )

    /** Digest text only. */
    suspend fun generate(context: Context, slot: SmartNotificationWorker.Slot): String =
        generateWithData(context, slot).text

    /**
     * Builds the digest and also returns the filtered todo list, so callers that
     * need the underlying items (e.g. the AFTERNOON inline "Done" actions) do not
     * have to re-query or re-filter.
     */
    suspend fun generateWithData(
        context: Context,
        slot: SmartNotificationWorker.Slot
    ): Digest {
        val appContext = context.applicationContext
        val db = AppDatabase.getInstance(appContext)
        val prefs = CarlsBrainApp.userPreferences
        val claude = CarlsBrainApp.claudeClient

        // Always exclude vault items — notifications can appear on the lock screen
        val priorityTodos = db.todoDao().getVisibleNonVaultTodos().first()
            .filter { !it.isDone }
            .let { todos ->
                when (slot) {
                    SmartNotificationWorker.Slot.MORNING -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.MIDDAY -> todos.filter { it.priority == 0 } // urgent only for midday check-in
                    SmartNotificationWorker.Slot.AFTERNOON -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.EVENING -> todos // all incomplete todos for evening prep
                }
            }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(appContext).getUpcomingEvents(daysAhead = 2)
                .getOrThrow()
                .filter {
                    val eventDate = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                    when (slot) {
                        SmartNotificationWorker.Slot.MORNING,
                        SmartNotificationWorker.Slot.MIDDAY,
                        SmartNotificationWorker.Slot.AFTERNOON -> eventDate == today
                        SmartNotificationWorker.Slot.EVENING -> eventDate == today.plusDays(1) // tomorrow for evening prep
                    }
                }
        }.getOrElse { emptyList() }

        val aiEnabled = prefs.notifAiEnabled.first()
        val apiKey = prefs.anthropicApiKey.first()

        val text: String = if (aiEnabled && apiKey.isNotBlank()) {
            runCatching {
                val prompt = buildAiPrompt(slot, todayEvents, priorityTodos)
                withTimeoutOrNull(CLAUDE_TIMEOUT_MS) {
                    claude.chat(
                        messages = listOf(ApiMessage("user", prompt)),
                        systemPrompt = SYSTEM_PROMPT,
                        model = ClaudeClient.HAIKU
                    ).getOrNull()
                }
            }.getOrNull() ?: buildFallbackText(slot, todayEvents, priorityTodos)
        } else {
            buildFallbackText(slot, todayEvents, priorityTodos)
        }

        return Digest(text = text, todos = priorityTodos)
    }

    private fun buildAiPrompt(
        slot: SmartNotificationWorker.Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
        val eventsStr = if (events.isEmpty()) "no calendar events"
        else events.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }
        val todosStr = if (todos.isEmpty()) "no pending tasks"
        else todos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }

        return when (slot) {
            SmartNotificationWorker.Slot.MORNING ->
                "Give Carl a concise morning briefing in 1 sentence. Today: $eventsStr. Priority tasks: $todosStr. End with one quick nudge. No bullet points."
            SmartNotificationWorker.Slot.MIDDAY ->
                "Quick midday check-in for Carl in 1 sentence. Urgent tasks right now: $todosStr. Events: $eventsStr. Be direct."
            SmartNotificationWorker.Slot.AFTERNOON ->
                "Afternoon nudge for Carl in 1 sentence. Top urgent tasks: $todosStr. Events remaining today: $eventsStr. Encourage action."
            SmartNotificationWorker.Slot.EVENING ->
                "Evening prep for Carl in 1 sentence. Tomorrow: $eventsStr. Still incomplete today: $todosStr. Suggest wrapping up or planning ahead."
        }
    }

    private fun buildFallbackText(
        slot: SmartNotificationWorker.Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
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
}
