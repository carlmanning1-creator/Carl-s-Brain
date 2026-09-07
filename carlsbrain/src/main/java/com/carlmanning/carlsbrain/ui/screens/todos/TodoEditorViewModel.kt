package com.carlmanning.carlsbrain.ui.screens.todos

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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.io.File

data class TodoEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val priority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
    val recurrence: Recurrence = Recurrence.None,
    val selectedBucketId: Long? = null,
    val isLoading: Boolean = true,
    val isSaved: Boolean = false,
    val isListening: Boolean = false,
    val interimText: String = "",
    val attachments: List<String> = emptyList(),
    val isUploadingAttachment: Boolean = false,
    val calendarResult: String? = null,
    val leadDays: Int = 0,
    /** Rough time this takes, in minutes. Null = no estimate; the Dashboard never guesses one. */
    val estimateMinutes: Int? = null,
    val sourceMeetingId: Long? = null,
    val sourceMeetingTitle: String? = null,
    val isDecomposing: Boolean = false,
    val decomposeMessage: String? = null,
    /**
     * Hidden-state flags for a todo that opens fine but does not appear in the Todos list.
     *
     * Both the archived and the Recently Deleted queries still return the row by id, so the
     * editor happily opens it and even saves edits to it — save() uses existing.copy(), which
     * preserves both flags — while the list keeps excluding it. Without surfacing this there is
     * nothing anywhere in the UI to explain the discrepancy, and it is indistinguishable from
     * the item having failed to save at all.
     */
    val isArchived: Boolean = false,
    val isDeleted: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class TodoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val calendarRepo = CalendarRepository(app)
    private val claude = CarlsBrainApp.claudeClient
    private val prefs = CarlsBrainApp.userPreferences

    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    val buckets: StateFlow<List<BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(TodoEditorUiState())
    val uiState: StateFlow<TodoEditorUiState> = _uiState.asStateFlow()

    private val _cachedPhotos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val cachedPhotos: StateFlow<Map<String, Bitmap>> = _cachedPhotos.asStateFlow()

    val subtasks: StateFlow<List<SubtaskEntity>> = _uiState
        .flatMapLatest { state ->
            if (state.id == 0L) flowOf(emptyList())
            else db.subtaskDao().getSubtasksForTodo(state.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadTodo(todoId: Long) {
        viewModelScope.launch {
            val todo = db.todoDao().getTodoById(todoId)
            if (todo != null) {
                val attachmentIds = todo.attachments
                    .split(",")
                    .map { it.trim() }
                    .filter { it.isNotBlank() }
                // Resolve the source meeting (item #10 provenance). Null when the meeting is gone.
                val sourceMeetingTitle = todo.sourceMeetingId?.let { meetingId ->
                    runCatching { db.meetingDao().getMeetingById(meetingId) }.getOrNull()?.title
                }
                _uiState.update {
                    it.copy(
                        id = todo.id,
                        title = todo.title,
                        priority = Priority.fromRank(todo.priority),
                        dueDate = todo.dueDate,
                        reminderAt = todo.reminderAt,
                        recurrence = Recurrence.fromStorageString(todo.recurrence),
                        selectedBucketId = todo.bucketId,
                        attachments = attachmentIds,
                        isLoading = false,
                        leadDays = todo.leadDays,
                        estimateMinutes = todo.estimateMinutes,
                        sourceMeetingId = todo.sourceMeetingId,
                        sourceMeetingTitle = sourceMeetingTitle,
                        isArchived = todo.isArchived,
                        isDeleted = todo.deletedAt != null
                    )
                }
                loadCachedPhotos(getApplication(), attachmentIds)
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
            // `file:<name>:<id>` entries are documents, not images. Passing one here would
            // fetch a PDF and try to decode it as a bitmap, so they are skipped outright.
            for (id in fileIds.filterNot { it.startsWith("file:") }) {
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

    /**
     * Attaches a non-image file, keeping its real name and extension.
     *
     * The "File" button used to call [addAttachment], which is the *photo* path: it uploads
     * through `uploadPhoto`, which names every upload `.jpg`, and stores a bare Drive id. A PDF
     * attached to a to-do was therefore uploaded as a JPEG, lost its filename permanently, and
     * came back as a tile that could not be identified or opened. The note editor has had the
     * `file:<name>:<id>` encoding for this since attachments were built; the to-do editor was
     * simply never given it.
     */
    fun addFile(uri: Uri) {
        val state = _uiState.value
        if (state.isUploadingAttachment || state.id == 0L) return
        _uiState.update { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            val context: Context = getApplication()
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: run {
                _uiState.update { it.copy(isUploadingAttachment = false) }
                return@launch
            }
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val displayName = runCatching {
                context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
                    val idx = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0 && cursor.moveToFirst()) cursor.getString(idx) else null
                }
            }.getOrNull() ?: "file_${System.currentTimeMillis()}"
            val driveId = drive.uploadFile(state.id, bytes, mimeType, displayName)
            if (driveId != null) {
                // Colons are the field separator, so one in a filename would split the entry
                // into the wrong three parts on the way back out.
                val entry = "file:${displayName.replace(":", "_")}:$driveId"
                val newAttachments = state.attachments + entry
                _uiState.update { it.copy(attachments = newAttachments, isUploadingAttachment = false) }
                persistAttachments(newAttachments)
            } else {
                _uiState.update { it.copy(isUploadingAttachment = false) }
            }
        }
    }

    fun addAttachment(uri: Uri) {
        val state = _uiState.value
        if (state.isUploadingAttachment || state.id == 0L) return
        _uiState.update { it.copy(isUploadingAttachment = true) }
        viewModelScope.launch {
            val context: Context = getApplication()
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: run {
                _uiState.update { it.copy(isUploadingAttachment = false) }
                return@launch
            }
            val mimeType = context.contentResolver.getType(uri) ?: "application/octet-stream"
            val driveId = drive.uploadPhoto(state.id, bytes, mimeType)
            if (driveId != null) {
                val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                if (bitmap != null) {
                    _cachedPhotos.value = _cachedPhotos.value + (driveId to bitmap)
                }
                val newAttachments = state.attachments + driveId
                _uiState.update { it.copy(attachments = newAttachments, isUploadingAttachment = false) }
                persistAttachments(newAttachments)
            } else {
                _uiState.update { it.copy(isUploadingAttachment = false) }
            }
        }
    }

    /**
     * Removes an attachment, which may be a bare photo id or a `file:<name>:<id>` entry.
     *
     * The Drive id is the last colon-separated field, never the whole entry — deleting the
     * literal string would miss the file and leave it orphaned in Drive forever.
     */
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

    private suspend fun persistAttachments(attachments: List<String>) {
        val state = _uiState.value
        if (state.id == 0L) return
        val current = db.todoDao().getTodoById(state.id) ?: return
        db.todoDao().updateTodo(
            current.copy(
                attachments = attachments.joinToString(","),
                updatedAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
    }

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
                        val existing = _uiState.value.title
                        val appended = if (existing.isBlank()) recognised else "$existing $recognised"
                        _uiState.update { it.copy(title = appended, isListening = false, interimText = "") }
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
                        if (current.title == rawText) current.copy(title = trimmed) else current
                    }
                }
            }
        }
    }

    fun addToCalendar(startMs: Long, endMs: Long) {
        val title = _uiState.value.title.trim().ifBlank { return }
        viewModelScope.launch {
            calendarRepo.createEvent(title = title, startMs = startMs, endMs = endMs)
                .onSuccess { _uiState.update { it.copy(calendarResult = "Added to Google Calendar") } }
                .onFailure { e -> _uiState.update { it.copy(calendarResult = "Failed: ${e.message}") } }
        }
    }

    fun clearCalendarResult() = _uiState.update { it.copy(calendarResult = null) }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onPriorityChange(priority: Priority) = _uiState.update { it.copy(priority = priority) }
    fun onDueDateChange(dateMs: Long?) = _uiState.update { it.copy(dueDate = dateMs) }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt) }
    fun onRecurrenceChange(recurrence: Recurrence) = _uiState.update { it.copy(recurrence = recurrence) }
    fun onBucketChange(bucketId: Long) = _uiState.update { it.copy(selectedBucketId = bucketId) }
    fun onLeadDaysChange(days: Int) = _uiState.update { it.copy(leadDays = days) }
    /** Quick-pick estimate; null clears it back to "no estimate". */
    fun onEstimateChange(minutes: Int?) = _uiState.update { it.copy(estimateMinutes = minutes) }

    // ── Subtask CRUD ────────────────────────────────────────────────

    fun addSubtask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val nextOrder = subtasks.value.size
            db.subtaskDao().insertSubtask(
                SubtaskEntity(todoId = _uiState.value.id, title = trimmed, sortOrder = nextOrder)
            )
            touchParent()
        }
    }

    @Serializable
    private data class StepList(val steps: List<String> = emptyList())

    /**
     * Asks Claude for 3–6 concrete first steps for this to-do and appends them as subtasks.
     * Task initiation is the blocker, so existing steps are never replaced — only added to.
     * Always clears [TodoEditorUiState.isDecomposing], on every path.
     */
    fun decomposeWithClaude() {
        val state = _uiState.value
        if (state.isDecomposing) return
        if (state.id == 0L) {
            _uiState.update { it.copy(decomposeMessage = "Save the to-do first.") }
            return
        }
        val title = state.title.trim()
        if (title.isBlank()) {
            _uiState.update { it.copy(decomposeMessage = "Give the to-do a title first.") }
            return
        }
        viewModelScope.launch {
            if (prefs.anthropicApiKey.first().isBlank()) {
                _uiState.update { it.copy(decomposeMessage = "Add your Anthropic API key in Settings first.") }
                return@launch
            }
            _uiState.update { it.copy(isDecomposing = true, decomposeMessage = null) }
            val existing = db.subtaskDao().getSubtasksForTodo(state.id).first()
            val existingLine = if (existing.isEmpty()) "" else
                "\nSteps already listed (suggest different ones): ${existing.joinToString("; ") { it.title }}"
            val prompt = """Return JSON only: {"steps":["<step>","<step>"]}
Break this task into 3-6 concrete first steps. Each step is a short action starting with a verb.
Task: "$title"$existingLine"""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You break tasks into small concrete steps for Carl, who has ADHD. Return only valid JSON, nothing else.",
                model = ClaudeClient.HAIKU
            ).onSuccess { response ->
                val steps = runCatching {
                    appJson.decodeFromString<StepList>(response.trim()).steps
                }.getOrNull()
                    ?.map { it.trim() }
                    ?.filter { it.isNotBlank() }
                    ?.take(6)
                    ?: emptyList()
                if (steps.isEmpty()) {
                    _uiState.update {
                        it.copy(isDecomposing = false, decomposeMessage = "Claude didn't return any steps — try again.")
                    }
                } else {
                    var order = existing.size
                    steps.forEach { step ->
                        db.subtaskDao().insertSubtask(
                            SubtaskEntity(todoId = state.id, title = step, sortOrder = order++)
                        )
                    }
                    touchParent()
                    _uiState.update {
                        it.copy(
                            isDecomposing = false,
                            decomposeMessage = "Added ${steps.size} step${if (steps.size == 1) "" else "s"}"
                        )
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(isDecomposing = false, decomposeMessage = "Could not break this down: ${e.message}")
                }
            }
        }
    }

    fun clearDecomposeMessage() = _uiState.update { it.copy(decomposeMessage = null) }

    fun toggleSubtask(subtask: SubtaskEntity) {
        viewModelScope.launch {
            db.subtaskDao().updateSubtask(subtask.copy(isDone = !subtask.isDone))
            touchParent()
        }
    }

    fun deleteSubtask(subtask: SubtaskEntity) {
        viewModelScope.launch {
            db.subtaskDao().deleteSubtask(subtask)
            touchParent()
        }
    }

    /**
     * Marks the parent to-do changed, because a subtask was.
     *
     * Subtasks travel to Drive inside the to-do row and the pull compares `updatedAt`, so
     * without this a subtask added, ticked, deleted or reordered here was never applied on the
     * web or on a second device — the write happened, the sync discarded it as stale.
     */
    private suspend fun touchParent() {
        val id = _uiState.value.id
        if (id == 0L) return
        val parent = db.todoDao().getTodoById(id) ?: return
        db.todoDao().updateTodo(
            parent.copy(updatedAt = System.currentTimeMillis(), isSynced = false)
        )
    }

    fun reorderSubtask(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = subtasks.value.toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
            val moved = current.removeAt(fromIndex)
            current.add(toIndex, moved)
            current.forEachIndexed { index, subtask ->
                db.subtaskDao().updateSubtask(subtask.copy(sortOrder = index))
            }
            touchParent()
        }
    }

    // ── Save / Delete ───────────────────────────────────────────────

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.title.isBlank()) { onComplete(); return }
        // App scope, not viewModelScope: onComplete() pops the back stack, which clears the
        // ViewModel store and cancels viewModelScope — potentially mid-write. Same reasoning as
        // NoteEditorViewModel and the journal's persist().
        CarlsBrainApp.appScope.launch {
            val existing = db.todoDao().getTodoById(state.id) ?: return@launch
            db.todoDao().updateTodo(
                existing.copy(
                    title = state.title.trim(),
                    priority = state.priority.rank,
                    dueDate = state.dueDate,
                    reminderAt = state.reminderAt,
                    recurrence = state.recurrence.toStorageString(),
                    bucketId = state.selectedBucketId ?: existing.bucketId,
                    attachments = state.attachments.joinToString(","),
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false,
                    leadDays = state.leadDays,
                    estimateMinutes = state.estimateMinutes
                )
            )
            val reminderAt = state.reminderAt
            if (reminderAt != null && reminderAt > System.currentTimeMillis()) {
                ReminderScheduler.schedule(getApplication(), state.id, state.title.trim(), reminderAt)
            } else {
                ReminderScheduler.cancel(getApplication(), state.id)
            }
            // Deliberately no MemoryLearner call for a to-do.
            //
            // memory.md is for durable facts about Carl's life — people, routines, standing
            // commitments — and is prepended to every Claude call. A to-do title is none of
            // those: it is a row in a table that already syncs, and writing one here produced
            // a shadow copy of the to-do list inside memory.md that immediately went stale.
            //
            // Chat then recited that shadow list as though it were real: items Carl had long
            // since ticked off came back as outstanding, and anything created outside an editor
            // — every meeting action item — was missing from it entirely, because those paths
            // never called this. The prompt now carries the actual list instead.
            // Back on the main thread. onComplete() pops the back stack, and navigation
            // from a background thread is not safe — it crashed the app outright,
            // sometimes with the system's "app has stopped" dialog and sometimes by
            // killing the process so the app reopened at the fingerprint prompt. The
            // save stays on the app scope, because the pop is what cancels
            // viewModelScope; only the callback comes back.
            withContext(Dispatchers.Main) { onComplete() }
        }
    }

    /**
     * Brings a hidden todo back into the Todos list.
     *
     * Handles both hidden states in one action because from Carl's side they are the same
     * problem — "this exists but I cannot see it". Restores from Recently Deleted first, since a
     * row can be both deleted and archived and clearing only one would leave it still hidden,
     * which would read as the button not working.
     *
     * [unarchiveTodo] is used rather than restoreTodo: it preserves the done state, so restoring
     * cannot silently mark a completed todo as outstanding again.
     */
    fun restoreToList() {
        viewModelScope.launch {
            val state = _uiState.value
            val existing = db.todoDao().getTodoById(state.id) ?: return@launch
            if (existing.deletedAt != null) db.todoDao().restoreTodoFromBin(existing.id)
            if (existing.isArchived) db.todoDao().unarchiveTodo(existing.id)
            _uiState.update { it.copy(isArchived = false, isDeleted = false) }
        }
    }

    fun delete(onComplete: () -> Unit) {
        viewModelScope.launch {
            val state = _uiState.value
            val existing = db.todoDao().getTodoById(state.id) ?: return@launch
            ReminderScheduler.cancel(getApplication(), existing.id)
            db.todoDao().softDeleteTodo(existing.id)
            onComplete()
        }
    }
}
