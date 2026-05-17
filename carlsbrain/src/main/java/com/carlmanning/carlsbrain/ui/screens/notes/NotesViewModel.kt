package com.carlmanning.carlsbrain.ui.screens.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class NotesViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

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

    val notes: StateFlow<List<NoteEntity>> = combine(
        allNotes, _selectedBucketId, _searchQuery
    ) { notes, bucketId, query ->
        notes.filter { note ->
            (bucketId == null || note.bucketId == bucketId) &&
            (query.isBlank() ||
                note.title.contains(query, ignoreCase = true) ||
                note.content.contains(query, ignoreCase = true))
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun selectBucket(bucketId: Long?) { _selectedBucketId.value = bucketId }

    fun onSearchChange(query: String) { _searchQuery.value = query }

    fun deleteNote(note: NoteEntity) {
        viewModelScope.launch { db.noteDao().deleteNote(note) }
    }
}
