package com.carlmanning.carlsbrain.ui.screens.journal

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.UserContext
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/**
 * @param prompt the prompt currently offered. Either Carl's fixed one from Settings or a
 *   Claude-generated alternative he asked for; whichever is showing is saved with the entry.
 * @param isClaudePrompt true when [prompt] came from Claude, so the UI can offer to go back.
 */
data class JournalUiState(
    val text: String = "",
    val prompt: String = "",
    val isClaudePrompt: Boolean = false,
    val isGeneratingPrompt: Boolean = false,
    val promptError: String? = null,
    val isSaving: Boolean = false,
    val isPrivate: Boolean = false,
    val isListening: Boolean = false,
    val interimText: String = "",
    /** Drive ids for the entry being composed, in the same encoding notes and todos use. */
    val attachments: List<String> = emptyList(),
    val isUploadingAttachment: Boolean = false
)

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val prefs = CarlsBrainApp.userPreferences
    private val claude = CarlsBrainApp.claudeClient
    private val drive = DriveRepository(app)

    /** Decoded thumbnails, keyed by Drive id, so the composer and list can show photos. */
    private val _cachedPhotos = MutableStateFlow<Map<String, Bitmap>>(emptyMap())
    val cachedPhotos: StateFlow<Map<String, Bitmap>> = _cachedPhotos.asStateFlow()

    private val _uiState = MutableStateFlow(JournalUiState())
    val uiState: StateFlow<JournalUiState> = _uiState.asStateFlow()

    private val _vaultOpen = MutableStateFlow(false)

    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    /**
     * Entries for the list. The vault-closed query excludes private entries in SQL, so a screen
     * that forgets to filter cannot leak one.
     */
    val entries: StateFlow<List<JournalEntryEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.journalDao().getAllEntries() else db.journalDao().getVisibleEntries()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Carl's templates, for the chip row. Built by him in the manager, not hardcoded. */
    val templates: StateFlow<List<com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity>> =
        db.journalTemplateDao().getTemplates()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** How many entries the vault is hiding — shown as a count, never as content. */
    val hiddenPrivateCount: StateFlow<Int> = db.journalDao().getPrivateCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    init {
        // Seed the editor with Carl's saved prompt. Re-read each time the screen is opened
        // rather than cached, so editing it in Settings takes effect immediately.
        viewModelScope.launch {
            _uiState.update { it.copy(prompt = prefs.journalPrompt.first()) }
        }
    }

    fun onTextChange(text: String) = _uiState.update { it.copy(text = text) }

    fun onPrivateChange(isPrivate: Boolean) = _uiState.update { it.copy(isPrivate = isPrivate) }

    fun clearPromptError() = _uiState.update { it.copy(promptError = null) }

    /** Returns to Carl's own prompt after a Claude-generated one. */
    fun useOwnPrompt() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(prompt = prefs.journalPrompt.first(), isClaudePrompt = false)
            }
        }
    }

    /**
     * Asks Claude for a prompt informed by the last few days.
     *
     * Only non-private entries are sent — [getEntriesForClaude] enforces that in SQL rather
     * than trusting this call site. Content is truncated: the point is enough context for a
     * relevant question, not to ship the journal to an API.
     */
    fun generateClaudePrompt() {
        if (_uiState.value.isGeneratingPrompt) return
        _uiState.update { it.copy(isGeneratingPrompt = true, promptError = null) }
        viewModelScope.launch {
            val apiKey = prefs.anthropicApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(
                        isGeneratingPrompt = false,
                        promptError = "Add your Anthropic API key in Settings first."
                    )
                }
                return@launch
            }

            val since = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
            val recent = db.journalDao().getEntriesForClaude(since, limit = 5)
                .joinToString("\n\n") { it.content.take(400) }

            val prompt = buildString {
                appendLine("Write ONE short journalling prompt for Carl, for right now.")
                appendLine()
                appendLine("Rules:")
                appendLine("- One sentence, a question, no preamble and no quotation marks.")
                appendLine("- Answerable in a few sentences when tired. Not a essay question.")
                appendLine("- Draw on his week where it helps, but do not interrogate him.")
                appendLine("- Never imply he has failed at anything or should have done more.")
                if (recent.isNotBlank()) {
                    appendLine()
                    appendLine("His recent entries, for context only — do not quote them back:")
                    appendLine(recent)
                }
            }

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You write short, kind journalling prompts. " +
                    UserContext.PERSONA_SHORT + " Reply with the prompt only.",
                model = ClaudeClient.HAIKU
            ).onSuccess { generated ->
                val cleaned = generated.trim().trim('"').lines().firstOrNull()?.trim().orEmpty()
                if (cleaned.isBlank()) {
                    // Blank would silently leave the old prompt showing with no explanation.
                    _uiState.update {
                        it.copy(isGeneratingPrompt = false, promptError = "Claude sent nothing back.")
                    }
                } else {
                    _uiState.update {
                        it.copy(prompt = cleaned, isClaudePrompt = true, isGeneratingPrompt = false)
                    }
                }
            }.onFailure { e ->
                _uiState.update {
                    it.copy(
                        isGeneratingPrompt = false,
                        promptError = e.message?.take(120) ?: "Could not reach Claude."
                    )
                }
            }
        }
    }

    /**
     * Saves the entry. The prompt in view is stored with it, so the entry still reads correctly
     * after the prompt is later changed in Settings.
     */
    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.text.isBlank()) { onComplete(); return }
        _uiState.update { it.copy(isSaving = true) }
        // appScope, not viewModelScope: onComplete navigates away, which clears the ViewModel
        // scope and would cancel the write mid-flight.
        CarlsBrainApp.appScope.launch {
            db.journalDao().insertEntry(
                JournalEntryEntity(
                    content = state.text.trim(),
                    prompt = state.prompt,
                    isPrivate = state.isPrivate,
                    attachments = state.attachments.joinToString(",")
                )
            )
        }
        // A fresh draft, but the prompt survives — clearing it would make the next entry look
        // like the screen had reset itself.
        _uiState.update { JournalUiState(prompt = state.prompt, isClaudePrompt = state.isClaudePrompt) }
        onComplete()
    }

    // ── Attachments ───────────────────────────────────────────────────────────

    /**
     * Uploads a photo and attaches it to the entry being composed.
     *
     * Mirrors the note and todo editors, including the encoding: a photo is a bare Drive id and
     * anything else is `file:<name>:<id>`. Attachments on a private entry are uploaded like any
     * other — Carl's explicit decision. The privacy flag governs what the app shows, and does
     * not pretend to encrypt anything in his own Drive.
     *
     * Uploaded immediately rather than on save, so the id exists before the entry does; the ids
     * are held in ui state and written with the entry.
     */
    fun addPhoto(uri: Uri) = attach(uri, asFile = false)

    fun addFile(uri: Uri) = attach(uri, asFile = true)

    private fun attach(uri: Uri, asFile: Boolean) {
        if (_uiState.value.isUploadingAttachment) return
        _uiState.update { it.copy(isUploadingAttachment = true) }
        // appScope: an upload must not die because Carl backed out of the composer.
        CarlsBrainApp.appScope.launch {
            val context: Context = getApplication()
            val bytes = runCatching {
                context.contentResolver.openInputStream(uri)?.readBytes()
            }.getOrNull()
            if (bytes == null) {
                _uiState.update { it.copy(isUploadingAttachment = false) }
                return@launch
            }
            // Drive groups attachments by owning item id. A journal entry has no id until it is
            // saved, so they are filed under a stable journal bucket instead of a real id.
            val ownerId = JOURNAL_ATTACHMENT_OWNER
            val entry = if (asFile) {
                val mime = context.contentResolver.getType(uri) ?: "application/octet-stream"
                val displayName = runCatching {
                    context.contentResolver.query(uri, null, null, null, null)?.use { c ->
                        val idx = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                        c.moveToFirst()
                        if (idx >= 0) c.getString(idx) else null
                    }
                }.getOrNull() ?: "file_${System.currentTimeMillis()}"
                val id = drive.uploadFile(ownerId, bytes, mime, displayName)
                    ?: return@launch _uiState.update { it.copy(isUploadingAttachment = false) }
                "file:${displayName.replace(":", "_")}:$id"
            } else {
                val mime = context.contentResolver.getType(uri) ?: "image/jpeg"
                val id = drive.uploadPhoto(ownerId, bytes, mime)
                    ?: return@launch _uiState.update { it.copy(isUploadingAttachment = false) }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size)?.let { bmp ->
                    _cachedPhotos.value = _cachedPhotos.value + (id to bmp)
                }
                id
            }
            _uiState.update {
                it.copy(attachments = it.attachments + entry, isUploadingAttachment = false)
            }
        }
    }

    /** Removes an attachment from the draft and deletes it from Drive. */
    fun removeAttachment(entry: String) {
        val driveId = if (entry.startsWith("file:")) entry.substringAfterLast(":") else entry
        _uiState.update { it.copy(attachments = it.attachments - entry) }
        _cachedPhotos.value = _cachedPhotos.value - driveId
        CarlsBrainApp.appScope.launch { drive.deletePhoto(driveId) }
    }

    /** Fetches and caches thumbnails for saved entries, so the list can render them. */
    fun loadPhotosFor(entries: List<JournalEntryEntity>) {
        val ids = entries
            .flatMap { it.attachments.split(",") }
            .map { it.trim() }
            .filter { it.isNotBlank() && !it.startsWith("file:") }
            .filterNot { _cachedPhotos.value.containsKey(it) }
            .distinct()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            val context: Context = getApplication()
            val cacheDir = File(context.cacheDir, "attachments").also { it.mkdirs() }
            val loaded = mutableMapOf<String, Bitmap>()
            for (id in ids) {
                val cached = File(cacheDir, "$id.jpg")
                val bitmap = if (cached.exists()) {
                    BitmapFactory.decodeFile(cached.absolutePath)
                } else {
                    val bytes = drive.downloadPhotoBytes(id) ?: continue
                    runCatching { cached.writeBytes(bytes) }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                }
                if (bitmap != null) loaded[id] = bitmap
            }
            if (loaded.isNotEmpty()) _cachedPhotos.value = _cachedPhotos.value + loaded
        }
    }

    fun togglePrivate(entry: JournalEntryEntity) {
        viewModelScope.launch {
            db.journalDao().updateEntry(
                entry.copy(
                    isPrivate = !entry.isPrivate,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )
            )
        }
    }

    fun deleteEntry(id: Long) {
        viewModelScope.launch { db.journalDao().softDeleteEntry(id) }
    }

    fun restoreEntry(id: Long) {
        viewModelScope.launch { db.journalDao().restoreEntry(id) }
    }

    fun updateEntryContent(entry: JournalEntryEntity, content: String) {
        viewModelScope.launch {
            db.journalDao().updateEntry(
                entry.copy(
                    content = content.trim(),
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )
            )
        }
    }
}

/**
 * Owner id under which journal attachments are filed in Drive.
 *
 * Drive groups attachments by the id of the item owning them, and a journal entry has no id
 * until it is saved — so they go under one fixed journal bucket instead. Negative because
 * nothing else in the app produces a negative owner id, making these unambiguous in Drive.
 *
 * `internal`, not `private`: top-level private in Kotlin is file-scoped, and the template entry
 * screen needs the same constant.
 */
internal const val JOURNAL_ATTACHMENT_OWNER = -100L
