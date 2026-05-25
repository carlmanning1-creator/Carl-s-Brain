package com.carlmanning.carlsbrain.ui.screens.calendar

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.remote.AuthResolutionException
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
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
    val date: LocalDate,
    val label: String,
    val events: List<CalendarEvent>
)

data class CreateEventDialogState(
    val isVisible: Boolean = false,
    val title: String = "",
    val dateMs: Long = System.currentTimeMillis(),
    val startHour: Int = 9,
    val startMinute: Int = 0,
    val endHour: Int = 10,
    val endMinute: Int = 0,
    val location: String = "",
    val isCreating: Boolean = false,
    val error: String? = null,
    val showDatePicker: Boolean = false,
    val showStartTimePicker: Boolean = false,
    val showEndTimePicker: Boolean = false
)

data class CalendarUiState(
    val days: List<EventDay> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val authResolutionIntent: PendingIntent? = null,
    val isFromCache: Boolean = false,
    val cachedAt: Long? = null,
    val createDialog: CreateEventDialogState = CreateEventDialogState()
)

class CalendarViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = CalendarRepository(app)

    private val _uiState = MutableStateFlow(CalendarUiState())
    val uiState: StateFlow<CalendarUiState> = _uiState.asStateFlow()

    init { loadEvents() }

    fun loadEvents() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null, authResolutionIntent = null) }
            repo.getUpcomingEvents(14).fold(
                onSuccess = { events ->
                    _uiState.update {
                        it.copy(days = events.groupIntodays(), isLoading = false, isFromCache = false, cachedAt = null)
                    }
                },
                onFailure = { e ->
                    when (e) {
                        is AuthResolutionException ->
                            _uiState.update { it.copy(isLoading = false, authResolutionIntent = e.pendingIntent) }
                        else -> {
                            val (cached, cachedAt) = repo.getCachedEvents()
                            if (cached.isNotEmpty()) {
                                _uiState.update {
                                    it.copy(days = cached.groupIntodays(), isLoading = false, isFromCache = true, cachedAt = cachedAt)
                                }
                            } else {
                                _uiState.update { it.copy(error = e.message, isLoading = false) }
                            }
                        }
                    }
                }
            )
        }
    }

    fun clearAuthResolution() {
        _uiState.update { it.copy(authResolutionIntent = null) }
    }

    /**
     * Call after the Google consent screen returns. Processes the authorization code so Play
     * Services caches the new token, then reloads events.
     */
    fun handleAuthResult(data: Intent?) {
        viewModelScope.launch {
            repo.processConsentResult(data)
            loadEvents()
        }
    }

    // ── Create event dialog ───────────────────────────────────────────────────

    fun showCreateDialog() {
        _uiState.update { it.copy(createDialog = CreateEventDialogState(isVisible = true)) }
    }

    fun dismissCreateDialog() {
        _uiState.update { it.copy(createDialog = CreateEventDialogState()) }
    }

    fun onCreateTitleChange(title: String) {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(title = title)) }
    }

    fun onCreateLocationChange(location: String) {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(location = location)) }
    }

    fun onShowDatePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showDatePicker = true)) }
    }

    fun onDateSelected(ms: Long) {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(dateMs = ms, showDatePicker = false)) }
    }

    fun onDismissDatePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showDatePicker = false)) }
    }

    fun onShowStartTimePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showStartTimePicker = true)) }
    }

    fun onStartTimeSelected(hour: Int, minute: Int) {
        val endHour = if (hour >= 23) 23 else hour + 1
        _uiState.update {
            it.copy(createDialog = it.createDialog.copy(
                startHour = hour, startMinute = minute,
                endHour = endHour, showStartTimePicker = false
            ))
        }
    }

    fun onDismissStartTimePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showStartTimePicker = false)) }
    }

    fun onShowEndTimePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showEndTimePicker = true)) }
    }

    fun onEndTimeSelected(hour: Int, minute: Int) {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(endHour = hour, endMinute = minute, showEndTimePicker = false)) }
    }

    fun onDismissEndTimePicker() {
        _uiState.update { it.copy(createDialog = it.createDialog.copy(showEndTimePicker = false)) }
    }

    fun submitCreateEvent() {
        val d = _uiState.value.createDialog
        if (d.title.isBlank()) return
        viewModelScope.launch {
            _uiState.update { it.copy(createDialog = it.createDialog.copy(isCreating = true, error = null)) }
            val zone = ZoneId.systemDefault()
            val date = Instant.ofEpochMilli(d.dateMs).atZone(zone).toLocalDate()
            val startMs = date.atTime(d.startHour, d.startMinute).atZone(zone).toInstant().toEpochMilli()
            val endMs = date.atTime(d.endHour, d.endMinute).atZone(zone).toInstant().toEpochMilli()
                .coerceAtLeast(startMs + 30 * 60 * 1000L)
            repo.createEvent(
                title = d.title,
                startMs = startMs,
                endMs = endMs,
                location = d.location.ifBlank { null }
            ).fold(
                onSuccess = {
                    _uiState.update { it.copy(createDialog = CreateEventDialogState()) }
                    loadEvents()
                    val locationPart = if (d.location.isNotBlank()) ", location: ${d.location}" else ""
                    MemoryLearner.learnFrom(
                        getApplication(),
                        "Calendar event created: \"${d.title}\" — start: $startMs$locationPart",
                        "calendar"
                    )
                },
                onFailure = { e ->
                    _uiState.update { it.copy(createDialog = it.createDialog.copy(isCreating = false, error = e.message ?: "Failed to create event")) }
                }
            )
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

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
                EventDay(date = date, label = label, events = dayEvents)
            }
    }
}
