package com.carlmanning.carlsbrain.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Result-type filter applied at render time in [SearchScreen]. */
enum class SearchType { ALL, NOTES, TODOS, MEETINGS, EVENTS }

data class SearchUiState(
    val query: String = "",
    val notes: List<Note> = emptyList(),
    val todos: List<Todo> = emptyList(),
    val calendarEvents: List<CalendarEvent> = emptyList(),
    val memoryLines: List<String> = emptyList(),
    val meetings: List<MeetingEntity> = emptyList(),
    val journalEntries: List<JournalEntryEntity> = emptyList(),
    val isSearching: Boolean = false
) {
    val hasResults get() = notes.isNotEmpty() || todos.isNotEmpty() ||
            calendarEvents.isNotEmpty() || memoryLines.isNotEmpty() || meetings.isNotEmpty() ||
            journalEntries.isNotEmpty()
    val isEmpty get() = query.isNotBlank() && !isSearching && !hasResults
}

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val calendarRepo = CalendarRepository(app)
    private val driveRepo = DriveRepository(app)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _selectedType = MutableStateFlow(SearchType.ALL)
    val selectedType: StateFlow<SearchType> = _selectedType.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    /**
     * Vault visibility, mirroring the NotesViewModel `_vaultOpen` pattern.
     * Defaults to closed — search must never reach into vault content unless the
     * screen explicitly opens it.
     */
    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    // Lazy-fetched and cached for the session
    private var cachedCalendarEvents: List<CalendarEvent>? = null
    private var cachedMemoryLines: List<String>? = null

    init {
        viewModelScope.launch {
            combine(_queryFlow, _vaultOpen) { query, vaultOpen -> query to vaultOpen }
                .debounce(300)
                .collectLatest { (query, vaultOpen) ->
                    if (query.isBlank()) {
                        _uiState.update { SearchUiState() }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearching = true) }

                    // Branch on the vault the same way meetings and journal entries do below.
                    // These two used to call the vault-filtered queries unconditionally, so
                    // opening the vault and searching for a vault note found nothing at all.
                    val notes = (if (vaultOpen) db.noteDao().searchAllNotes(query)
                                 else db.noteDao().searchNotes(query)).map { it.toDomain() }
                    val todos = (if (vaultOpen) db.todoDao().searchAllTodos(query)
                                 else db.todoDao().searchTodos(query)).map { it.toDomain() }
                    val meetings = if (vaultOpen) db.meetingDao().searchMeetings(query)
                                   else db.meetingDao().searchNonVaultMeetings(query)
                    // Journal privacy is per-entry rather than per-bucket, so it needs its own
                    // pair of queries — both filter in SQL so a private entry cannot surface
                    // in search results while the vault is closed.
                    val journal = if (vaultOpen) db.journalDao().searchAll(query)
                                  else db.journalDao().searchVisible(query)

                    val calendarCache = cachedCalendarEvents
                        ?: calendarRepo.getUpcomingEvents(daysAhead = 30)
                            .getOrElse { emptyList() }
                            .also { cachedCalendarEvents = it }
                    val calendarMatches = calendarCache.filter {
                        it.title.contains(query, ignoreCase = true) ||
                        it.location?.contains(query, ignoreCase = true) == true
                    }

                    // memory.md is unstructured prose: MemoryLearner appends bullets in the
                    // form "- [YYYY-MM-DD] Fact" with no bucket or vault marker, so facts
                    // learned from vault interactions are indistinguishable from the rest.
                    // Rather than guess with heuristics, memory.md is excluded entirely
                    // while the vault is closed.
                    val memoryMatches = if (!vaultOpen) emptyList() else {
                        val memoryCache = cachedMemoryLines
                            ?: (driveRepo.getMemoryMd() ?: "")
                                .lines()
                                .filter { it.trim().isNotBlank() }
                                .also { cachedMemoryLines = it }
                        memoryCache
                            .filter { it.contains(query, ignoreCase = true) }
                            .take(10)
                    }

                    _uiState.update {
                        it.copy(
                            notes = notes,
                            todos = todos,
                            calendarEvents = calendarMatches,
                            memoryLines = memoryMatches,
                            meetings = meetings,
                            journalEntries = journal,
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

    fun selectType(type: SearchType) {
        _selectedType.value = type
    }

    fun clearQuery() {
        _uiState.update { SearchUiState() }
        _selectedType.value = SearchType.ALL
        _queryFlow.value = ""
    }
}
