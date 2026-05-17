package com.carlmanning.carlsbrain.ui.screens.calendar

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class EventDay(
    val label: String,
    val events: List<CalendarEvent>
)

data class CalendarUiState(
    val days: List<EventDay> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CalendarRepository(app)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init { loadEvents() }

    fun loadEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            repo.getUpcomingEvents(14).fold(
                onSuccess = { events ->
                    _uiState.update { it.copy(days = events.groupIntodays(), isLoading = false) }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message, isLoading = false) }
                }
            )
        }
    }

    private fun List<CalendarEvent>.groupIntodays(): List<EventDay> {
        val today = LocalDate.now()
        val tomorrow = today.plusDays(1)
        val dayFmt = DateTimeFormatter.ofPattern("EEE d MMM")
        val zone = ZoneId.systemDefault()

        return groupBy { event ->
            Instant.ofEpochMilli(event.startMs).atZone(zone).toLocalDate()
        }
            .entries
            .sortedBy { it.key }
            .map { (date, dayEvents) ->
                val label = when (date) {
                    today -> "Today"
                    tomorrow -> "Tomorrow"
                    else -> date.format(dayFmt)
                }
                EventDay(label = label, events = dayEvents)
            }
    }
}
