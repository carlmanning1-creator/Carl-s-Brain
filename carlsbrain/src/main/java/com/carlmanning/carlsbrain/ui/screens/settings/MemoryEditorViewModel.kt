package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MemoryEditorUiState(
    val content: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedOk: Boolean = false,
    /**
     * True when the file could not be read. Saving is blocked while it is set — see the class
     * comment. The screen shows a Retry rather than an editable box.
     */
    val loadFailed: Boolean = false,
    /** Set when Drive moved on while the editor was open; the text is not saved. */
    val conflict: Boolean = false
)

/**
 * Settings → Memory. The one screen where Carl edits `memory.md` by hand.
 *
 * ## Two ways this used to destroy the file
 *
 * **A failed read looked like an empty file.** `getMemoryMd()` returns null both when Drive is
 * unreachable and when the file genuinely does not exist, and this screen mapped null to
 * `INITIAL_MEMORY`. So opening it offline showed the seed text as though that were his memory,
 * and one tap of Save wrote the seed over everything he had accumulated. That is the single
 * most destructive path in the app, and it took no unusual conditions to reach — just a bad
 * signal in Dubbo.
 *
 * **A stale copy overwrote newer facts.** `MemoryLearner` appends to this file in the
 * background, so text loaded ten minutes ago and saved now silently deletes whatever was
 * learned in between.
 *
 * Both are closed the same way the web app closes them: `readMemoryMd` returns a `Result` so an
 * unreachable Drive is an error rather than blank content, and the save carries Drive's own
 * `modifiedTime` back so a changed file is refused rather than clobbered. Carl reloads and
 * decides; nothing is ever thrown away on his behalf.
 */
class MemoryEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app)

    /**
     * Drive's stamp for the copy currently on screen, sent back with the save.
     *
     * Blank legitimately means "there was no file", which is a real state on a fresh install —
     * and one that conflicts with anything that exists by the time we save.
     */
    private var loadedModifiedTime: String = ""

    private val _uiState = MutableStateFlow(MemoryEditorUiState())
    val uiState: StateFlow<MemoryEditorUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true, errorMessage = null, loadFailed = false, conflict = false
            )
            drive.readMemoryMd()
                .onSuccess { doc ->
                    loadedModifiedTime = doc.modifiedTime
                    _uiState.value = _uiState.value.copy(
                        // Genuinely absent — a fresh install — is the only case that gets the
                        // seed, and it is safe there because there is nothing to overwrite.
                        content = doc.content.ifBlank { DriveRepository.INITIAL_MEMORY },
                        isLoading = false,
                        loadFailed = false
                    )
                }
                .onFailure { e ->
                    // Deliberately does NOT fall back to the seed. Showing something editable
                    // here is what made the overwrite possible.
                    _uiState.value = _uiState.value.copy(
                        isLoading = false,
                        loadFailed = true,
                        errorMessage = "Couldn't read your memory file — ${e.message ?: "check your connection"}"
                    )
                }
        }
    }

    fun onContentChange(text: String) {
        _uiState.value = _uiState.value.copy(content = text, savedOk = false, conflict = false)
    }

    fun save() {
        val state = _uiState.value
        // Nothing was successfully loaded, so there is nothing safe to write.
        if (state.loadFailed) {
            _uiState.value = state.copy(
                errorMessage = "Not saving — your memory file couldn't be read, so this would overwrite it."
            )
            return
        }
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSaving = true, errorMessage = null, conflict = false
            )
            when (val result =
                drive.updateMemoryMdIfUnchanged(_uiState.value.content, loadedModifiedTime)
            ) {
                is DriveRepository.MemoryWriteResult.Saved -> {
                    // The cached copy MemoryLearner builds prompts from is now stale.
                    MemoryLearner.invalidateCache()
                    // Re-read so a later save in the same session carries the new stamp rather
                    // than the one it has just superseded.
                    drive.readMemoryMd().onSuccess { loadedModifiedTime = it.modifiedTime }
                    _uiState.value = _uiState.value.copy(isSaving = false, savedOk = true)
                }
                is DriveRepository.MemoryWriteResult.Conflict -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        savedOk = false,
                        conflict = true,
                        errorMessage = "Your brain learned something since you opened this. " +
                            "Nothing was saved — reload to see it, then re-apply your edit."
                    )
                }
                is DriveRepository.MemoryWriteResult.Failed -> {
                    _uiState.value = _uiState.value.copy(
                        isSaving = false,
                        savedOk = false,
                        errorMessage = "Failed to save — check Drive connection"
                    )
                }
            }
        }
    }
}
