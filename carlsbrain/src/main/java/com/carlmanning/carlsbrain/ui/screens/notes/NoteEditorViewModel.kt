package com.carlmanning.carlsbrain.ui.screens.notes

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

data class NoteEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val bucketId: Long = 0,
    val reminderAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val isLoading: Boolean = true,
    val isSaved: Boolean = false,
    val attachments: List<String> = emptyList(),
    val isUploadingPhoto: Boolean = false,
    val isListening: Boolean = false,
    val interimText: String = "",
    val tags: List<String> = emptyList()
)

class NoteEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val claude = CarlsBrainApp.claudeClient
    private val prefs = CarlsBrainApp.userPreferences

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(NoteEditorUiState())
    val uiState: StateFlow<NoteEditorUiState> = _uiState.asStateFlow()

    private val _cachedPhotos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val cachedPhotos: StateFlow<Map<String, Bitmap>> = _cachedPhotos.asStateFlow()

    private var speechRecognizer: SpeechRecognizer? = null

    fun startListening() {
        viewModelScope.launch(Dispatchers.Main) {
            val ctx: Context = getApplication()
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return@launch
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return@launch
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) { _uiState.update { it.copy(isListening = true, interimText = "") } }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() { _uiState.update { it.copy(isListening = false) } }
                    override fun onError(e: Int) { _uiState.update { it.copy(isListening = false, interimText = "") } }
                    override fun onResults(results: Bundle?) {
                        val recognised = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        val existing = _uiState.value.content
                        val appended = if (existing.isBlank()) recognised else "$existing $recognised"
                        _uiState.update { it.copy(content = appended, isListening = false, interimText = "") }
                        queueClaudeCleanup(appended)
                    }
                    override fun onPartialResults(partial: Bundle?) {
                        val text = partial?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull() ?: return
                        _uiState.update { it.copy(interimText = text) }
                    }
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                })
            }
        }
    }

    fun stopListening() {
        viewModelScope.launch(Dispatchers.Main) {
            speechRecognizer?.stopListening()
            _uiState.update { it.copy(isListening = false) }
        }
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
    }

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
                        reminderAt = note.reminderAt,
                        createdAt = note.createdAt,
                        attachments = note.toDomain().attachments,
                        tags = note.tags.split(",").map { t -> t.trim() }.filter { t -> t.isNotBlank() },
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
            val mimeType = context.contentResolver.getType(uri) ?: "image/jpeg"
            val driveId = drive.uploadPhoto(state.id.coerceAtLeast(1), bytes, mimeType)
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

    private fun queueClaudeCleanup(rawText: String) {
        viewModelScope.launch {
            if (prefs.anthropicApiKey.first().isBlank()) return@launch
            claude.chat(
                messages = listOf(ApiMessage("user", "Clean up this voice transcription — fix punctuation, capitalisation, and obvious errors. Return ONLY the cleaned text.\n\n\"$rawText\"")),
                systemPrompt = "You clean up voice transcriptions. Return only the cleaned text, nothing else."
            ).onSuccess { cleaned ->
                val trimmed = cleaned.trim().removeSurrounding("\"")
                if (trimmed.isNotBlank() && trimmed != rawText) {
                    _uiState.update { current ->
                        if (current.content == rawText) current.copy(content = trimmed) else current
                    }
                }
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onContentChange(content: String) = _uiState.update { it.copy(content = content) }
    fun onBucketChange(bucketId: Long) = _uiState.update { it.copy(bucketId = bucketId) }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt) }
    fun addTag(tag: String) {
        val trimmed = tag.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
        if (trimmed.isBlank() || trimmed in _uiState.value.tags) return
        _uiState.update { it.copy(tags = it.tags + trimmed) }
    }
    fun removeTag(tag: String) = _uiState.update { it.copy(tags = it.tags - tag) }

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
                    reminderAt = state.reminderAt,
                    createdAt = state.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    attachments = state.attachments.joinToString(","),
                    tags = state.tags.joinToString(",")
                )
            )
            val reminderAt = state.reminderAt
            if (reminderAt != null && reminderAt > System.currentTimeMillis()) {
                ReminderScheduler.schedule(
                    getApplication(), state.id + NOTE_ID_OFFSET, title, reminderAt
                )
            } else {
                ReminderScheduler.cancel(getApplication(), state.id + NOTE_ID_OFFSET)
            }
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val note = db.noteDao().getNoteById(state.id)
            if (note != null) {
                ReminderScheduler.cancel(getApplication(), state.id + NOTE_ID_OFFSET)
                for (id in state.attachments) drive.deletePhoto(id)
                db.noteDao().deleteNote(note)
            }
            onComplete()
        }
    }

    companion object {
        private const val NOTE_ID_OFFSET = 1_000_000L
    }
}
