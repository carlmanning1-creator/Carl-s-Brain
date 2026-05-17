package com.carlmanning.carlsbrain.ui.screens.notes

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class NoteEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val bucketId: Long = 0,
    val createdAt: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val isSaved: Boolean = false,
    val attachments: List<String> = emptyList(),
    val isUploadingPhoto: Boolean = false
)

class NoteEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)

    private val _uiState = MutableStateFlow(NoteEditorUiState())
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    private val _cachedPhotos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val cachedPhotos: StateFlow<Map<String, Bitmap>> = _cachedPhotos.asStateFlow()

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
                        attachments = note.toDomain().attachments,
                        isLoading = false
                    )
                }
                loadCachedPhotos(getApplication(), note.toDomain().attachments)
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    private fun loadCachedPhotos(context: Context, fileIds: List<String>) {
        if (fileIds.isEmpty()) return
        viewModelScope.launch {
            val cacheDir = File(context.cacheDir, "attachments").also { it.mkdirs() }
            val map = mutableMapOf<String, Bitmap>()
            for (id in fileIds) {
                val cached = File(cacheDir, "$id.jpg")
                val bitmap = if (cached.exists()) {
                    BitmapFactory.decodeFile(cached.absolutePath)
                } else {
                    val bytes = drive.downloadPhotoBytes(id) ?: continue
                    cached.writeBytes(bytes)
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bitmap != null) map[id] = bitmap
            }
            _cachedPhotos.value = map
        }
    }

    fun addPhoto(uri: Uri) {
        val state = _uiState.value
        if (state.isUploadingPhoto) return
        _uiState.update { it.copy(isUploadingPhoto = true) }
        viewModelScope.launch {
            val context: Context = getApplication()
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: run {
                _uiState.update { it.copy(isUploadingPhoto = false) }
                return@launch
            }
            val driveId = drive.uploadPhoto(state.id.coerceAtLeast(1), bytes, "image/jpeg")
            if (driveId != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    _cachedPhotos.value = _cachedPhotos.value + (driveId to bitmap)
                }
                val newAttachments = state.attachments + driveId
                _uiState.update { it.copy(attachments = newAttachments, isUploadingPhoto = false) }
                persistAttachments(newAttachments)
            } else {
                _uiState.update { it.copy(isUploadingPhoto = false) }
            }
        }
    }

    fun removePhoto(driveFileId: String) {
        val newAttachments = _uiState.value.attachments - driveFileId
        _uiState.update { it.copy(attachments = newAttachments) }
        _cachedPhotos.value = _cachedPhotos.value - driveFileId
        viewModelScope.launch {
            persistAttachments(newAttachments)
            drive.deletePhoto(driveFileId)
        }
    }

    private suspend fun persistAttachments(attachments: List<String>) {
        val state = _uiState.value
        if (state.id == 0L) return
        val current = db.noteDao().getNoteById(state.id) ?: return
        db.noteDao().updateNote(
            current.copy(
                attachments = attachments.joinToString(","),
                updatedAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
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
                    updatedAt = System.currentTimeMillis(),
                    attachments = state.attachments.joinToString(",")
                )
            )
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val note = db.noteDao().getNoteById(state.id)
            if (note != null) {
                for (id in state.attachments) drive.deletePhoto(id)
                db.noteDao().deleteNote(note)
            }
            onComplete()
        }
    }
}
