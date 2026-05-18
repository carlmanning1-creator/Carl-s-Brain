package com.carlmanning.carlsbrain.ui.screens.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.WeatherInfo
import com.carlmanning.carlsbrain.data.remote.WeatherRepository
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

sealed class ScheduleItem {
    abstract val timeMs: Long
    abstract val title: String

    data class Event(val event: CalendarEvent) : ScheduleItem() {
        override val timeMs get() = event.startMs
        override val title get() = event.title
    }
    data class TodoDue(val todo: TodoEntity) : ScheduleItem() {
        override val timeMs get() = todo.reminderAt ?: todo.dueDate!!
        override val title get() = todo.title
    }
    data class NoteReminder(val note: NoteEntity) : ScheduleItem() {
        override val timeMs get() = note.reminderAt!!
        override val title get() = note.title.ifBlank { note.content.lines().first().take(60) }
    }
}

data class DashboardUiState(
    val todaySchedule: List<ScheduleItem> = emptyList(),
    val tomorrowSchedule: List<ScheduleItem> = emptyList(),
    val weekSchedule: List<ScheduleItem> = emptyList(),
    val priorityTodos: List<TodoEntity> = emptyList(),
    val overdueTodos: List<TodoEntity> = emptyList(),
    val briefing: String = "",
    val isLoadingCalendar: Boolean = false,
    val isLoadingBriefing: Boolean = false,
    val calendarError: String? = null,
    val weatherInfo: WeatherInfo? = null
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
            // Load weather in parallel
            launch {
                val weather = WeatherRepository().getWeather()
                if (weather != null) _uiState.update { it.copy(weatherInfo = weather) }
            }

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEnd = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

            val allActiveTodos = db.todoDao().getVisibleTodos().first().filter { !it.isDone }
            val priorityTodos = allActiveTodos.filter { it.priority in listOf("URGENT", "HIGH") }
            val overdueTodos = allActiveTodos.filter { it.dueDate != null && it.dueDate < now }
            val remindersToday = allActiveTodos.filter {
                it.reminderAt != null && it.reminderAt in todayStart until todayEnd
            }
            val floatingCount = allActiveTodos.count { it.dueDate == null }

            // Todos with a specific due time or reminder in next 7 days (skip calendar-imported ones)
            val scheduledTodos = allActiveTodos.filter { todo ->
                todo.calendarEventId == null && (
                    (todo.dueDate != null && todo.dueDate >= todayStart && todo.dueDate < weekEnd) ||
                    (todo.reminderAt != null && todo.reminderAt >= todayStart && todo.reminderAt < weekEnd)
                )
            }

            val scheduledNotes = db.noteDao().getNotesWithReminders(todayStart, weekEnd)

            _uiState.update { it.copy(priorityTodos = priorityTodos, overdueTodos = overdueTodos) }

            fun itemDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            fun buildSchedule(
                events: List<CalendarEvent>,
                includeCalendar: Boolean
            ): Triple<List<ScheduleItem>, List<ScheduleItem>, List<ScheduleItem>> {
                val tomorrow = today.plusDays(1)
                val all = buildList {
                    if (includeCalendar) events.forEach { add(ScheduleItem.Event(it)) }
                    scheduledTodos.forEach { add(ScheduleItem.TodoDue(it)) }
                    scheduledNotes.forEach { add(ScheduleItem.NoteReminder(it)) }
                }
                val todayItems = all.filter { itemDate(it.timeMs) == today }.sortedBy { it.timeMs }
                val tomorrowItems = all.filter { itemDate(it.timeMs) == tomorrow }.sortedBy { it.timeMs }
                val weekItems = all.filter {
                    val d = itemDate(it.timeMs)
                    d.isAfter(tomorrow) && !d.isAfter(today.plusDays(6))
                }.sortedBy { it.timeMs }
                return Triple(todayItems, tomorrowItems, weekItems)
            }

            calendarRepo.getUpcomingEvents(daysAhead = 7).fold(
                onSuccess = { events ->
                    val tomorrow = today.plusDays(1)
                    val todayCalEvents = events.filter { itemDate(it.startMs) == today }
                    val tomorrowCalEvents = events.filter { itemDate(it.startMs) == tomorrow }

                    val (todayItems, tomorrowItems, weekItems) = buildSchedule(events, includeCalendar = true)
                    _uiState.update {
                        it.copy(
                            todaySchedule = todayItems,
                            tomorrowSchedule = tomorrowItems,
                            weekSchedule = weekItems,
                            isLoadingCalendar = false
                        )
                    }
                    importCalendarEventsTodos(todayCalEvents + tomorrowCalEvents)
                    generateBriefing(todayCalEvents, priorityTodos, overdueTodos, remindersToday, floatingCount)
                },
                onFailure = { e ->
                    val (todayItems, tomorrowItems, weekItems) = buildSchedule(emptyList(), includeCalendar = false)
                    _uiState.update {
                        it.copy(
                            todaySchedule = todayItems,
                            tomorrowSchedule = tomorrowItems,
                            weekSchedule = weekItems,
                            calendarError = e.message,
                            isLoadingCalendar = false
                        )
                    }
                    generateBriefing(emptyList(), priorityTodos, overdueTodos, remindersToday, floatingCount)
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
