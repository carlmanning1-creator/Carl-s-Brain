package com.carlmanning.carlsbrain.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Note
import com.carlmanning.carlsbrain.domain.model.Todo
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val query: String = "",
    val notes: List<Note> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val memoryLines: List<String> = emptyList(),
    val meetings: List<MeetingEntity> = emptyList(),
    val isSearching: Boolean = false
) {
    val hasResults get() = notes.isNotEmpty() || todos.isNotEmpty() ||
            calendarEvents.isNotEmpty() || memoryLines.isNotEmpty() || meetings.isNotEmpty()
    val isEmpty get() = query.isNotBlank() && !isSearching && !hasResults
}

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val calendarRepo = CalendarRepository(app)
    private val driveRepo = DriveRepository(app)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    // Lazy-fetched and cached for the session
    private var cachedCalendarEvents: List<CalendarEvent>? = null
    private var cachedMemoryLines: List<String>? = null

    init {
        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update { SearchUiState() }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearching = true) }

                    val notes = db.noteDao().searchNotes(query).map { it.toDomain() }
                    val todos = db.todoDao().searchTodos(query).map { it.toDomain() }
                    val meetings = db.meetingDao().searchMeetings(query)

                    val calendarCache = cachedCalendarEvents
                        ?: calendarRepo.getUpcomingEvents(daysAhead = 30)
                            .getOrElse { emptyList() }
                            .also { cachedCalendarEvents = it }
                    val calendarMatches = calendarCache.filter {
                        it.title.contains(query, ignoreCase = true) ||
                        it.location?.contains(query, ignoreCase = true) == true
                    }

                    val memoryCache = cachedMemoryLines
                        ?: (driveRepo.getMemoryMd() ?: "")
                            .lines()
                            .filter { it.trim().isNotBlank() }
                            .also { cachedMemoryLines = it }
                    val memoryMatches = memoryCache
                        .filter { it.contains(query, ignoreCase = true) }
                        .take(10)

                    _uiState.update {
                        it.copy(
                            notes = notes,
                            todos = todos,
                            calendarEvents = calendarMatches,
                            memoryLines = memoryMatches,
                            meetings = meetings,
                            isSearching = false
                        )
                    }
                }
        }
    }

    fun onQueryChange(query: String) {
        _uiState.update { it.copy(query = query) }
        _queryFlow.value = query
    }

    fun clearQuery() {
        _uiState.update { SearchUiState() }
        _queryFlow.value = ""
    }
}
