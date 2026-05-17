package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class MemoryEditorUiState(
    val content: String = "",
    val isLoading: Boolean = true,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val savedOk: Boolean = false
)

class MemoryEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val drive = DriveRepository(app)

    private val _uiState = MutableStateFlow(MemoryEditorUiState())
    val uiState: StateFlow<MemoryEditorUiState> = _uiState.asStateFlow()

    fun load() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, errorMessage = null)
            val content = runCatching { drive.getMemoryMd() }.getOrNull()
            _uiState.value = _uiState.value.copy(
                content = content ?: DriveRepository.INITIAL_MEMORY,
                isLoading = false
            )
        }
    }

    fun onContentChange(text: String) {
        _uiState.value = _uiState.value.copy(content = text, savedOk = false)
    }

    fun save() {
        val content = _uiState.value.content
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSaving = true, errorMessage = null)
            val ok = runCatching { drive.updateMemoryMd(content) }.getOrElse { false }
            _uiState.value = _uiState.value.copy(
                isSaving = false,
                savedOk = ok,
                errorMessage = if (ok) null else "Failed to save — check Drive connection"
            )
        }
    }
}
