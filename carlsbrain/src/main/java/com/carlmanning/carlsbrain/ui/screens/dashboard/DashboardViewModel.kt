package com.carlmanning.carlsbrain.ui.screens.dashboard

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

data class DashboardUiState(
    val todayEvents: List<CalendarEvent> = emptyList(),
    val isLoadingCalendar: Boolean = false,
    val calendarError: String? = null
)

class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val calendarRepo = CalendarRepository(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    init { loadData() }

    fun loadData() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingCalendar = true, calendarError = null) }
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
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(calendarError = e.message, isLoadingCalendar = false)
                    }
                }
            )
        }
    }
}
