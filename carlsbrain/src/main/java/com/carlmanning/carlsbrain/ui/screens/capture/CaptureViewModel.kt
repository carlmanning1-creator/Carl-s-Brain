package com.carlmanning.carlsbrain.ui.screens.capture

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val reminderAt: Long? = null,
    val recurrence: com.carlmanning.carlsbrain.domain.model.Recurrence = com.carlmanning.carlsbrain.domain.model.Recurrence.None,
    val pendingPhotoUris: List<Uri> = emptyList(),
    val isSaving: Boolean = false,
    val isListening: Boolean = false,
    val interimText: String = "",
    val suggestedBucket: BucketEntity? = null
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val claude = ClaudeClient(app)
    private val drive = DriveRepository(app)
    private val prefs = UserPreferences(app)
    private val tagJson = Json { ignoreUnknownKeys = true }

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    // SpeechRecognizer must be created/destroyed on the main thread
    private var speechRecognizer: SpeechRecognizer? = null
    private var suggestionJob: Job? = null

    fun onTypeSelected(type: CaptureType) = _uiState.update { it.copy(captureType = type) }
    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text, suggestedBucket = null) }
        enqueueBucketSuggestion(text)
    }
    fun onBucketSelected(bucketId: Long) {
        _uiState.update { it.copy(selectedBucketId = bucketId, suggestedBucket = null) }
        suggestionJob?.cancel()
    }

    fun acceptSuggestedBucket() {
        val suggested = _uiState.value.suggestedBucket ?: return
        _uiState.update { it.copy(selectedBucketId = suggested.id, suggestedBucket = null) }
    }

    private fun enqueueBucketSuggestion(text: String) {
        if (text.length < 30 || _uiState.value.selectedBucketId != null) return
        suggestionJob?.cancel()
        suggestionJob = viewModelScope.launch {
            delay(1500)
            val bucketList = buckets.value
            if (bucketList.isEmpty()) return@launch
            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>"}
Suggest the best bucket for: "$text""""
            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You suggest buckets for notes and tasks. Return only valid JSON."
            ).onSuccess { response ->
                runCatching {
                    val tag = tagJson.decodeFromString<NoteTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    if (bucket != null && _uiState.value.selectedBucketId == null) {
                        _uiState.update { it.copy(suggestedBucket = bucket) }
                    }
                }
            }
        }
    }
    fun onPrioritySelected(priority: Priority) = _uiState.update { it.copy(selectedPriority = priority) }
    fun onDueDateChange(dateMs: Long?) = _uiState.update { it.copy(dueDate = dateMs) }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt) }
    fun onRecurrenceChange(recurrence: Recurrence) = _uiState.update { it.copy(recurrence = recurrence) }

    fun addPendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris + uri) }
    }

    fun removePendingPhoto(uri: Uri) {
        _uiState.update { it.copy(pendingPhotoUris = it.pendingPhotoUris - uri) }
    }

    // ---------- Voice capture ----------

    fun startListening() {
        viewModelScope.launch(Dispatchers.Main) {
            val ctx: Context = getApplication()
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return@launch
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return@launch
            destroySpeechRecognizer()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(params: Bundle?) {
                        _uiState.update { it.copy(isListening = true, interimText = "") }
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(rmsdB: Float) {}
                    override fun onBufferReceived(buffer: ByteArray?) {}
                    override fun onEndOfSpeech() {
                        _uiState.update { it.copy(isListening = false) }
                    }
                    override fun onError(error: Int) {
                        _uiState.update { it.copy(isListening = false, interimText = "") }
                    }
                    override fun onResults(results: Bundle?) {
                        val matches = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        val recognised = matches?.firstOrNull() ?: return
                        val existing = _uiState.value.text
                        val newText = if (existing.isBlank()) recognised
                                      else "$existing $recognised"
                        _uiState.update {
                            it.copy(
                                text = newText,
                                isListening = false,
                                interimText = ""
                            )
                        }
                        queueClaudeCleanup(newText)
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        _uiState.update { it.copy(interimText = partial) }
                    }
                    override fun onEvent(eventType: Int, params: Bundle?) {}
                })
                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                }
                startListening(intent)
            }
        }
    }

    fun stopListening() {
        viewModelScope.launch(Dispatchers.Main) {
            speechRecognizer?.stopListening()
            _uiState.update { it.copy(isListening = false) }
        }
    }

    private fun destroySpeechRecognizer() {
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun queueClaudeCleanup(rawText: String) {
        viewModelScope.launch {
            val apiKey = prefs.anthropicApiKey.first()
            if (apiKey.isBlank()) return@launch
            val prompt = """Clean up the following voice transcription: fix punctuation, capitalisation, and obvious transcription errors. Return ONLY the cleaned text, nothing else.

"$rawText""""
            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You clean up voice transcriptions. Return only the cleaned text."
            ).onSuccess { cleaned ->
                val trimmed = cleaned.trim().removeSurrounding("\"")
                if (trimmed.isNotBlank() && trimmed != rawText) {
                    // Only apply if the text hasn't changed since we fired the request
                    _uiState.update { current ->
                        if (current.text == rawText) current.copy(text = trimmed) else current
                    }
                }
            }
        }
    }

    override fun onCleared() {
        viewModelScope.launch(Dispatchers.Main) {
            destroySpeechRecognizer()
        }
        super.onCleared()
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
                        dueDate = state.dueDate,
                        reminderAt = state.reminderAt,
                        recurrence = state.recurrence.toStorageString()
                    )
                )
                val reminderAt = state.reminderAt
                if (reminderAt != null && reminderAt > System.currentTimeMillis()) {
                    ReminderScheduler.schedule(getApplication(), todoId, text, reminderAt)
                }
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
