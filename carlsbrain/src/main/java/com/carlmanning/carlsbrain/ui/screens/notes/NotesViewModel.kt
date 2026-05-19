package com.carlmanning.carlsbrain.ui.screens.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class NotesSortMode(val label: String) {
    UPDATED("Updated"),
    CREATED("Created"),
    ALPHABETICAL("A–Z"),
    MANUAL("Manual")
}

class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val prefs = UserPreferences(app)

    private val allNotes: StateFlow<List<NoteEntity>> = db.noteDao()
        .getNonVaultNotes()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedBucketId = MutableStateFlow<Long?>(null)
    val selectedBucketId: StateFlow<Long?> = _selectedBucketId.asStateFlow()

    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _selectedTag = MutableStateFlow<String?>(null)
    val selectedTag: StateFlow<String?> = _selectedTag.asStateFlow()

    val sortMode: StateFlow<NotesSortMode> = prefs.notesSortMode
        .map { s -> NotesSortMode.entries.find { it.name == s } ?: NotesSortMode.UPDATED }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), NotesSortMode.UPDATED)

    val notes: StateFlow<List<NoteEntity>> = combine(
        allNotes, _selectedBucketId, _searchQuery, sortMode, _selectedTag
    ) { notes, bucketId, query, mode, tag ->
        val filtered = notes.filter { note ->
            (bucketId == null || note.bucketId == bucketId) &&
            (tag == null || note.tags.split(",").map { it.trim() }.contains(tag)) &&
            (query.isBlank() ||
                note.title.contains(query, ignoreCase = true) ||
                note.content.contains(query, ignoreCase = true))
        }
        val sorted = when (mode) {
            NotesSortMode.UPDATED -> filtered.sortedByDescending { it.updatedAt }
            NotesSortMode.CREATED -> filtered.sortedByDescending { it.createdAt }
            NotesSortMode.ALPHABETICAL -> filtered.sortedBy { it.title.lowercase() }
            NotesSortMode.MANUAL -> filtered.sortedBy { it.sortOrder }
        }
        sorted.sortedByDescending { it.isPinned }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // All unique tags across non-vault notes
    val allTags: StateFlow<List<String>> = allNotes
        .map { notes ->
            notes.flatMap { n -> n.tags.split(",").map { it.trim() }.filter { it.isNotBlank() } }
                .distinct().sorted()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectBucket(bucketId: Long?) { _selectedBucketId.value = bucketId }
    fun selectTag(tag: String?) { _selectedTag.value = tag }
    fun onSearchChange(query: String) { _searchQuery.value = query }

    fun pinNote(noteId: Long, isPinned: Boolean) {
        viewModelScope.launch { db.noteDao().updateIsPinned(noteId, isPinned) }
    }

    fun setSortMode(mode: NotesSortMode) {
        viewModelScope.launch { prefs.setNotesSortMode(mode.name) }
    }

    fun reorderNote(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = notes.value.toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
            val moved = current.removeAt(fromIndex)
            current.add(toIndex, moved)
            current.forEachIndexed { index, note ->
                db.noteDao().updateSortOrder(note.id, index)
            }
        }
    }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch { db.noteDao().softDeleteNote(note.id) }
    }
}
