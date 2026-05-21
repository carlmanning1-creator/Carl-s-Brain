package com.carlmanning.carlsbrain.ui.screens.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.domain.model.Bucket
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

data class CaptureUiState(
    val text: String = "",
    val selectedBucketId: Long? = null,
    val selectedPriority: Priority = Priority.NORMAL,
    val isSaving: Boolean = false,
    val isSaved: Boolean = false
)

class CaptureViewModel(application: Application) : AndroidViewModel(application) {

    private val bucketDao = (application as CarlsBrainApp).database.bucketDao()

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    /**
     * Real bucket list loaded from Room — including any user-created buckets. The previous
     * implementation hard-coded a five-string list that was missing the Kink (vault) bucket
     * AND every user-created bucket, AND used name-strings instead of stable bucket IDs.
     */
    val buckets: StateFlow<List<Bucket>> = bucketDao.getAllBuckets()
        .map { entities -> entities.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIPTION_TIMEOUT_MS), emptyList())

    fun onTextChange(text: String) {
        _uiState.update { it.copy(text = text) }
    }

    fun onBucketSelected(bucketId: Long) {
        _uiState.update { it.copy(selectedBucketId = bucketId) }
    }

    fun onPrioritySelected(priority: Priority) {
        _uiState.update { it.copy(selectedPriority = priority) }
    }

    fun save(onComplete: () -> Unit) {
        // Persistence via repository will be wired up in a follow-up.
        _uiState.update { it.copy(isSaved = true) }
        onComplete()
    }

    private companion object {
        const val SUBSCRIPTION_TIMEOUT_MS = 5_000L
    }
}
