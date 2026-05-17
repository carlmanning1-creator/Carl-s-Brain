package com.carlmanning.carlsbrain.ui.screens.capture

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class CaptureType { NOTE, TODO }

data class CaptureUiState(
    val captureType: CaptureType = CaptureType.TODO,
    val title: String = "",
    val text: String = "",
    val selectedBucketId: Long? = null,
    val selectedPriority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val pendingPhotoUris: List<Uri> = emptyList(),
    val isSaving: Boolean = false
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val claude = ClaudeClient(app)
    private val drive = DriveRepository(app)
    private val tagJson = Json { ignoreUnknownKeys = true }

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun onTypeSelected(type: CaptureType) = _uiState.update { it.copy(captureType = type) }
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onTextChange(text: String) = _uiState.update { it.copy(text = text) }
    fun onBucketSelected(bucketId: Long) = _uiState.update { it.copy(selectedBucketId = bucketId) }
    fun onPrioritySelected(priority: Priority) = _uiState.update { it.copy(selectedPriority = priority) }
    fun onDueDateChange(dateMs: Long?) = _uiState.update { it.copy(dueDate = dateMs) }

    fun addPendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris + uri) }
    }

    fun removePendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris - uri) }
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        val text = state.text.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val bucketList = buckets.value
            val bucketId = state.selectedBucketId
                ?: bucketList.find { it.name == "Other" }?.id
                ?: bucketList.lastOrNull()?.id
                ?: return@launch

            if (state.captureType == CaptureType.NOTE) {
                val title = state.title.trim().ifBlank {
                    text.lines().first().take(60).ifBlank { "Note" }
                }
                val noteId = db.noteDao().insertNote(
                    NoteEntity(title = title, content = text, bucketId = bucketId)
                )
                val pendingUris = state.pendingPhotoUris
                _uiState.update { CaptureUiState() }
                onComplete()
                autoTagNote(noteId, text, bucketList)
                if (pendingUris.isNotEmpty()) uploadPendingPhotos(noteId, pendingUris)
            } else {
                val todoId = db.todoDao().insertTodo(
                    TodoEntity(
                        title = text,
                        bucketId = bucketId,
                        priority = state.selectedPriority.name,
                        dueDate = state.dueDate
                    )
                )
                _uiState.update { CaptureUiState() }
                onComplete()
                autoTagTodo(todoId, text, bucketList)
            }
        }
    }

    private fun uploadPendingPhotos(noteId: Long, uris: List<Uri>) {
        viewModelScope.launch {
            val context: android.content.Context = getApplication()
            val driveIds = mutableListOf<String>()
            for (uri in uris) {
                val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: continue
                val id = drive.uploadPhoto(noteId, bytes, "image/jpeg") ?: continue
                driveIds.add(id)
            }
            if (driveIds.isNotEmpty()) {
                db.noteDao().getNoteById(noteId)?.let { existing ->
                    val current = if (existing.attachments.isBlank()) emptyList()
                                  else existing.attachments.split(",")
                    db.noteDao().updateNote(
                        existing.copy(
                            attachments = (current + driveIds).joinToString(","),
                            updatedAt = System.currentTimeMillis(),
                            isSynced = false
                        )
                    )
                }
            }
        }
    }

    private fun autoTagTodo(todoId: Long, text: String, bucketList: List<BucketEntity>) {
        viewModelScope.launch {
            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>","priority":"<one of: URGENT|HIGH|NORMAL|SOMEDAY>"}
Classify this capture: "$text""""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You classify tasks. Return only valid JSON, nothing else."
            ).onSuccess { response ->
                runCatching {
                    val tag = tagJson.decodeFromString<AutoTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    val priority = Priority.entries.find { it.name == tag.priority.uppercase() }
                    if (bucket != null && priority != null) {
                        db.todoDao().getTodoById(todoId)?.let { existing ->
                            db.todoDao().updateTodo(
                                existing.copy(
                                    bucketId = bucket.id,
                                    priority = priority.name,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    private fun autoTagNote(noteId: Long, text: String, bucketList: List<BucketEntity>) {
        viewModelScope.launch {
            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>"}
Which bucket does this note belong to? "$text""""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You classify notes. Return only valid JSON, nothing else."
            ).onSuccess { response ->
                runCatching {
                    val tag = tagJson.decodeFromString<NoteTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    if (bucket != null) {
                        db.noteDao().getNoteById(noteId)?.let { existing ->
                            db.noteDao().updateNote(
                                existing.copy(bucketId = bucket.id, updatedAt = System.currentTimeMillis())
                            )
                        }
                    }
                }
            }
        }
    }

    @Serializable
    private data class AutoTag(val bucket: String = "", val priority: String = "NORMAL")

    @Serializable
    private data class NoteTag(val bucket: String = "")
}
