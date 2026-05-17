package com.carlmanning.carlsbrain.ui.screens.search

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.domain.model.Note
import com.carlmanning.carlsbrain.domain.model.Priority
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
    val isSearching: Boolean = false
) {
    val hasResults get() = notes.isNotEmpty() || todos.isNotEmpty()
    val isEmpty get() = query.isNotBlank() && !isSearching && !hasResults
}

@OptIn(FlowPreview::class)
class SearchViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private val _queryFlow = MutableStateFlow("")

    init {
        viewModelScope.launch {
            _queryFlow
                .debounce(300)
                .collectLatest { query ->
                    if (query.isBlank()) {
                        _uiState.update { it.copy(notes = emptyList(), todos = emptyList(), isSearching = false) }
                        return@collectLatest
                    }
                    _uiState.update { it.copy(isSearching = true) }
                    val notes = db.noteDao().searchNotes(query).map { it.toDomain() }
                    val todos = db.todoDao().searchTodos(query).map { it.toDomain() }
                    _uiState.update { it.copy(notes = notes, todos = todos, isSearching = false) }
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
