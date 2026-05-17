package com.carlmanning.carlsbrain.ui.screens.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

data class DashboardUiState(
    val todayEvents: List<CalendarEvent> = emptyList(),
    val tomorrowEvents: List<CalendarEvent> = emptyList(),
    val weekEvents: List<CalendarEvent> = emptyList(),
    val priorityTodos: List<TodoEntity> = emptyList(),
    val briefing: String = "",
    val isLoadingCalendar: Boolean = false,
    val isLoadingBriefing: Boolean = false,
    val calendarError: String? = null
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val calendarRepo = CalendarRepository(app)
    private val claude = ClaudeClient(app)
    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private var lastLoadMs = 0L

    init { loadData() }

    fun refreshIfStale() {
        val elapsed = System.currentTimeMillis() - lastLoadMs
        if (elapsed > STALE_THRESHOLD_MS) loadData()
    }

    fun loadData() {
        viewModelScope.launch {
            lastLoadMs = System.currentTimeMillis()
            _uiState.update { it.copy(isLoadingCalendar = true, calendarError = null) }

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val allActiveTodos = db.todoDao().getVisibleTodos().first().filter { !it.isDone }
            val priorityTodos = allActiveTodos.filter { it.priority in listOf("URGENT", "HIGH") }
            val overdueTodos = allActiveTodos.filter { it.dueDate != null && it.dueDate < now }
            val remindersToday = allActiveTodos.filter {
                it.reminderAt != null && it.reminderAt in todayStart until todayEnd
            }
            val floatingCount = allActiveTodos.count { it.dueDate == null }

            _uiState.update { it.copy(priorityTodos = priorityTodos) }

            calendarRepo.getUpcomingEvents(daysAhead = 7).fold(
                onSuccess = { events ->
                    val tomorrow = today.plusDays(1)
                    val todayEvents = events.filter {
                        Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == today
                    }
                    val tomorrowEvents = events.filter {
                        Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate() == tomorrow
                    }
                    val weekEvents = events.filter {
                        val date = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                        date.isAfter(tomorrow) && !date.isAfter(today.plusDays(6))
                    }
                    _uiState.update {
                        it.copy(
                            todayEvents = todayEvents,
                            tomorrowEvents = tomorrowEvents,
                            weekEvents = weekEvents,
                            isLoadingCalendar = false
                        )
                    }
                    importCalendarEventsTodos(todayEvents + tomorrowEvents)
                    generateBriefing(
                        todayEvents, priorityTodos, overdueTodos, remindersToday, floatingCount
                    )
                },
                onFailure = { e ->
                    _uiState.update { it.copy(calendarError = e.message, isLoadingCalendar = false) }
                    generateBriefing(
                        emptyList(), priorityTodos, overdueTodos, remindersToday, floatingCount
                    )
                }
            )
        }
    }

    private suspend fun importCalendarEventsTodos(events: List<CalendarEvent>) {
        val nonAllDay = events.filter { !it.isAllDay }
        if (nonAllDay.isEmpty()) return
        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
            ?: return
        for (event in nonAllDay) {
            if (db.todoDao().findByCalendarEventId(event.id) == null) {
                db.todoDao().insertTodo(
                    TodoEntity(
                        title = event.title,
                        bucketId = defaultBucket.id,
                        dueDate = event.startMs,
                        calendarEventId = event.id
                    )
                )
            }
        }
    }

    private fun generateBriefing(
        todayEvents: List<CalendarEvent>,
        priorityTodos: List<TodoEntity>,
        overdueTodos: List<TodoEntity>,
        remindersToday: List<TodoEntity>,
        floatingCount: Int
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBriefing = true) }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when (hour) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..23 -> "evening"
                else -> "late night"
            }

            val eventsStr = if (todayEvents.isEmpty()) "no calendar events today"
                else todayEvents.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }

            val priorityStr = if (priorityTodos.isEmpty()) "none"
                else priorityTodos.take(5).joinToString("; ") { "[${it.priority}] ${it.title}" }

            val overdueStr = if (overdueTodos.isEmpty()) "none"
                else overdueTodos.take(3).joinToString("; ") { it.title } +
                    if (overdueTodos.size > 3) " (+ ${overdueTodos.size - 3} more)" else ""

            val remindersStr = if (remindersToday.isEmpty()) "none"
                else remindersToday.joinToString("; ") { it.title }

            val prompt = """It is ${timeOfDay}. Write Carl a thorough but concise briefing in 3–4 natural sentences.
Be warm and direct. Help him not miss anything important. If there are overdue tasks, flag them clearly.
If reminders are due today, mention them. If he has floating tasks with no due date, give a gentle nudge.
End with one clear, practical next action.

Today's calendar: $eventsStr
Urgent/High priority tasks: $priorityStr
Overdue tasks: $overdueStr
Reminders due today: $remindersStr
Tasks with no due date: $floatingCount

No bullet points — flowing prose only. Don't start with "Good morning/afternoon" — jump straight into the content."""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You are Carl's personal assistant. Carl is a NSW SES Deputy at Dubbo Unit with ADHD. Be thorough, warm, and actionable. Help him stay on top of everything without feeling overwhelmed.",
                model = ClaudeClient.HAIKU
            ).onSuccess { briefing ->
                _uiState.update { it.copy(briefing = briefing, isLoadingBriefing = false) }
            }.onFailure {
                _uiState.update { it.copy(isLoadingBriefing = false) }
            }
        }
    }

    companion object {
        private const val STALE_THRESHOLD_MS = 15 * 60 * 1000L // 15 minutes
    }
}
