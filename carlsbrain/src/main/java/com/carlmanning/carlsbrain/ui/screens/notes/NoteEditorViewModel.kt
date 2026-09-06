package com.carlmanning.carlsbrain.ui.screens.notes

import android.Manifest
import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
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
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
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
    val tags: List<String> = emptyList(),
    val isSharing: Boolean = false,
    val sourceMeetingId: Long? = null,
    val sourceMeetingTitle: String? = null,
    /** True only once the user has actually changed something. Never set by loadNote(). */
    val isDirty: Boolean = false,
    /** Bumped by every user edit — the editor keys its debounced auto-save on this. */
    val saveVersion: Int = 0,
    /**
     * True when the note this editor was opened for no longer exists. Every save path checks
     * it, so a stale id from the loose-threads sheet or the recently-viewed strip shows an
     * explanation instead of quietly creating a new blank note.
     */
    val isMissing: Boolean = false
)

/** Marks the state dirty and bumps the save token so the auto-save effect re-triggers. */
private fun NoteEditorUiState.markDirty(): NoteEditorUiState =
    copy(isDirty = true, saveVersion = saveVersion + 1)

class NoteEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val claude = CarlsBrainApp.claudeClient
    private val prefs = CarlsBrainApp.userPreferences

    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    @OptIn(ExperimentalCoroutinesApi::class)
    val buckets: StateFlow<List<BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
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
                        _uiState.update { it.copy(content = appended, isListening = false, interimText = "").markDirty() }
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
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 12000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
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
                // Resolve the source meeting (item #10 provenance). Null when the meeting is gone.
                val sourceMeetingTitle = note.sourceMeetingId?.let { meetingId ->
                    runCatching { db.meetingDao().getMeetingById(meetingId) }.getOrNull()?.title
                }
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
                        isLoading = false,
                        sourceMeetingId = note.sourceMeetingId,
                        sourceMeetingTitle = sourceMeetingTitle
                    )
                }
                // Item #16 — record the view of an existing note. Never block loading.
                runCatching {
                    db.recentlyViewedDao().recordView(
                        RecentlyViewedEntity(
                            itemType = "NOTE",
                            itemId = note.id,
                            title = note.title.ifBlank {
                                note.content.lines().firstOrNull()?.take(60).orEmpty().ifBlank { "Note" }
                            },
                            bucketId = note.bucketId
                        )
                    )
                }
                loadCachedPhotos(getApplication(), note.toDomain().attachments)
            } else {
                // The row is gone — deleted on another screen, or on another device since the
                // id was captured (the loose-threads sheet and the recently-viewed strip both
                // hold ids that can go stale). Leaving id = 0 here meant save() fell through to
                // "create a new note" and silently wrote a blank one.
                _uiState.update { it.copy(isLoading = false, isMissing = true) }
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

    fun addFile(uri: Uri) {
        val state = _uiState.value
        if (state.isUploadingPhoto) return
        _uiState.update { it.copy(isUploadingPhoto = true) }
        viewModelScope.launch {
            val context: Context = getApplication()
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: run {
                _uiState.update { it.copy(isUploadingPhoto = false) }
                return@launch
            }
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val displayName = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                cursor.moveToFirst()
                if (idx >= 0) cursor.getString(idx) else null
            } ?: "file_${System.currentTimeMillis()}"
            val driveId = drive.uploadFile(state.id.coerceAtLeast(1), bytes, mimeType, displayName)
            if (driveId != null) {
                val entry = "file:${displayName.replace(":", "_")}:$driveId"
                val newAttachments = state.attachments + entry
                _uiState.update { it.copy(attachments = newAttachments, isUploadingPhoto = false) }
                persistAttachments(newAttachments)
            } else {
                _uiState.update { it.copy(isUploadingPhoto = false) }
            }
        }
    }

    fun removeAttachment(entry: String) {
        val driveId = if (entry.startsWith("file:")) entry.substringAfterLast(":") else entry
        val newAttachments = _uiState.value.attachments - entry
        _uiState.update { it.copy(attachments = newAttachments) }
        _cachedPhotos.value = _cachedPhotos.value - driveId
        viewModelScope.launch {
            persistAttachments(newAttachments)
            drive.deletePhoto(driveId)
        }
    }

    @Deprecated("Use removeAttachment") fun removePhoto(driveFileId: String) = removeAttachment(driveFileId)

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
                        if (current.content == rawText) current.copy(content = trimmed).markDirty() else current
                    }
                }
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title).markDirty() }
    fun onContentChange(content: String) = _uiState.update { it.copy(content = content).markDirty() }
    fun onBucketChange(bucketId: Long) = _uiState.update { it.copy(bucketId = bucketId).markDirty() }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt).markDirty() }
    fun addTag(tag: String) {
        val trimmed = tag.trim().lowercase().filter { it.isLetterOrDigit() || it == '-' }
        if (trimmed.isBlank() || trimmed in _uiState.value.tags) return
        _uiState.update { it.copy(tags = it.tags + trimmed).markDirty() }
    }
    fun removeTag(tag: String) = _uiState.update { it.copy(tags = it.tags - tag).markDirty() }

    fun shareNoteToDrive() {
        val state = _uiState.value
        if (state.id == 0L || state.isSharing) return
        _uiState.update { it.copy(isSharing = true) }
        viewModelScope.launch {
            val ctx: Context = getApplication()
            val webViewLink = drive.shareNoteFile(state.id)
            if (webViewLink != null) {
                val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("Drive link", webViewLink))
                Toast.makeText(ctx, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, "Failed to share — make sure the note is synced to Drive", Toast.LENGTH_LONG).show()
            }
            _uiState.update { it.copy(isSharing = false) }
        }
    }

    /**
     * Immediate, non-debounced save — used when the editor leaves composition so the last
     * edits (and any bucket / reminder / tag change) are not lost on back-navigation.
     * Runs on [CarlsBrainApp.appScope], NOT viewModelScope. Back-navigation IS the pop that
     * clears the ViewModel store and cancels viewModelScope, so the very save triggered by
     * leaving the screen could be cancelled at its first suspension point — losing up to the
     * 1.5s debounce window of typing, plus any bucket, reminder or tag change. The journal's
     * TemplateEntryViewModel.persist already uses the app scope for exactly this reason.
     *
     * Idempotent: a no-op when nothing is dirty.
     */
    fun flushSave() = saveQuiet()

    fun saveQuiet() {
        val state = _uiState.value
        if (state.isLoading) return
        if (state.isMissing) return
        // Bug 2 — merely viewing a note must not rewrite updatedAt / clear isSynced.
        if (!state.isDirty) return
        if (state.content.isBlank()) return
        // Clear the flag up-front so a save already in flight isn't repeated; any edit
        // that lands while writing re-sets it and schedules another save.
        _uiState.update { it.copy(isDirty = false) }
        CarlsBrainApp.appScope.launch {
            val title = state.title.trim().ifBlank {
                state.content.lines().first().take(60).ifBlank { "Note" }
            }
            val existing = db.noteDao().getNoteById(state.id)
            db.noteDao().updateNote(
                (existing ?: NoteEntity(id = state.id, title = title, content = state.content, bucketId = state.bucketId)).copy(
                    title = title,
                    content = state.content,
                    bucketId = state.bucketId,
                    reminderAt = state.reminderAt,
                    createdAt = state.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    attachments = state.attachments.joinToString(","),
                    tags = state.tags.joinToString(","),
                    isSynced = false
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
        }
    }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.isMissing) { onComplete(); return }
        if (state.content.isBlank()) { onComplete(); return }
        _uiState.update { it.copy(isDirty = false) }
        CarlsBrainApp.appScope.launch {
            val title = state.title.trim().ifBlank {
                state.content.lines().first().take(60).ifBlank { "Note" }
            }
            val existing = db.noteDao().getNoteById(state.id)
            db.noteDao().updateNote(
                (existing ?: NoteEntity(id = state.id, title = title, content = state.content, bucketId = state.bucketId)).copy(
                    title = title,
                    content = state.content,
                    bucketId = state.bucketId,
                    reminderAt = state.reminderAt,
                    createdAt = state.createdAt,
                    updatedAt = System.currentTimeMillis(),
                    attachments = state.attachments.joinToString(","),
                    tags = state.tags.joinToString(","),
                    isSynced = false
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
            val bucketName = buckets.value.find { it.id == state.bucketId }?.name ?: "Unknown"
            // Before onComplete(), which pops the back stack: this used to sit after it and,
            // on viewModelScope, was cancelled at its first suspension point — so editing a
            // note never contributed to memory.md while creating one did.
            MemoryLearner.learnFrom(
                getApplication(),
                "Note saved: \"$title\" — bucket: $bucketName, content preview: ${state.content.take(120)}",
                "note"
            )
            // Back on the main thread. onComplete() pops the back stack, and navigation
            // from a background thread is not safe — it crashed the app outright,
            // sometimes with the system's "app has stopped" dialog and sometimes by
            // killing the process so the app reopened at the fingerprint prompt. The
            // save stays on the app scope, because the pop is what cancels
            // viewModelScope; only the callback comes back.
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    fun delete(onComplete: () -> Unit) {
        val state = _uiState.value
        viewModelScope.launch {
            val note = db.noteDao().getNoteById(state.id)
            if (note != null) {
                ReminderScheduler.cancel(getApplication(), state.id + NOTE_ID_OFFSET)
                db.noteDao().softDeleteNote(note.id)
            }
            onComplete()
        }
    }

    companion object {
        private const val NOTE_ID_OFFSET = 1_000_000L
    }
}
