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

data class DashboardUiState(
    val todayEvents: List<CalendarEvent> = emptyList(),
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

    init { loadData() }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCalendar = true, calendarError = null) }

            val todos = db.todoDao().getVisibleTodos().first()
                .filter { it.priority in listOf("URGENT", "HIGH") && !it.isDone }

            _uiState.update { it.copy(priorityTodos = todos) }

            calendarRepo.getUpcomingEvents(daysAhead = 1).fold(
                onSuccess = { events ->
                    val today = LocalDate.now()
                    val zone = ZoneId.systemDefault()
                    val todayEvents = events.filter { event ->
                        Instant.ofEpochMilli(event.startMs).atZone(zone).toLocalDate() == today
                    }
                    _uiState.update {
                        it.copy(todayEvents = todayEvents, isLoadingCalendar = false)
                    }
                    generateBriefing(todayEvents, todos)
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(calendarError = e.message, isLoadingCalendar = false)
                    }
                    generateBriefing(emptyList(), todos)
                }
            )
        }
    }

    private fun generateBriefing(events: List<CalendarEvent>, todos: List<TodoEntity>) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBriefing = true) }

            val eventsStr = if (events.isEmpty()) "no calendar events today"
                            else events.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }

            val todosStr = if (todos.isEmpty()) "no urgent or high-priority tasks"
                           else todos.take(5).joinToString("; ") { "[${it.priority}] ${it.title}" }

            val prompt = """Give Carl a warm, direct morning briefing in 2–3 natural sentences.
Today's schedule: $eventsStr
Priority tasks: $todosStr
End with one practical nudge or suggestion. No bullet points — flowing prose only."""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You are Carl's personal assistant. Carl is a NSW SES Deputy at Dubbo Unit and manages a busy life with ADHD. Be concise, warm, and actionable.",
                model = ClaudeClient.HAIKU
            ).onSuccess { briefing ->
                _uiState.update { it.copy(briefing = briefing, isLoadingBriefing = false) }
            }.onFailure {
                _uiState.update { it.copy(isLoadingBriefing = false) }
            }
        }
    }
}
