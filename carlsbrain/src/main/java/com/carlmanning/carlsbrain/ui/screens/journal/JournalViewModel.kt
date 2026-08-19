package com.carlmanning.carlsbrain.ui.screens.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
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
    val interimText: String = ""
)

@OptIn(ExperimentalCoroutinesApi::class)
class JournalViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val prefs = CarlsBrainApp.userPreferences
    private val claude = CarlsBrainApp.claudeClient

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
                    isPrivate = state.isPrivate
                )
            )
        }
        _uiState.update { JournalUiState(prompt = state.prompt, isClaudePrompt = state.isClaudePrompt) }
        onComplete()
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
