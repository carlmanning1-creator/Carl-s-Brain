package com.carlmanning.carlsbrain.ui.screens.notes

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class NoteEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val bucketId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val isSaved: Boolean = false
)

class NoteEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(NoteEditorUiState())
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    fun loadNote(noteId: Long) {
        viewModelScope.launch {
            val note = db.noteDao().getNoteById(noteId)
            if (note != null) {
                _uiState.update {
                    it.copy(
                        id = note.id,
                        title = note.title,
                        content = note.content,
                        bucketId = note.bucketId,
                        createdAt = note.createdAt,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }

    fun onContentChange(content: String) = _uiState.update { it.copy(content = content) }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.content.isBlank()) return
        viewModelScope.launch {
            val title = state.title.trim().ifBlank {
                state.content.lines().first().take(60).ifBlank { "Note" }
            }
            db.noteDao().updateNote(
                NoteEntity(
                    id = state.id,
                    title = title,
                    content = state.content,
                    bucketId = state.bucketId,
                    createdAt = state.createdAt,
                    updatedAt = System.currentTimeMillis()
                )
            )
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val note = db.noteDao().getNoteById(state.id)
            if (note != null) db.noteDao().deleteNote(note)
            onComplete()
        }
    }
}
