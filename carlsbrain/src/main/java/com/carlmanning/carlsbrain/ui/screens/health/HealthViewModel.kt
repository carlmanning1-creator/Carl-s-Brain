package com.carlmanning.carlsbrain.ui.screens.health

import android.app.Application
import androidx.health.connect.client.HealthConnectClient
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.health.HealthPermissionStatus
import com.carlmanning.carlsbrain.data.health.HealthRepository
import com.carlmanning.carlsbrain.data.health.HealthSnapshot
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class HealthUiState(
    val permissionStatus: HealthPermissionStatus = HealthPermissionStatus.CHECKING,
    val selectedWindowDays: Int = 7,
    val snapshot: HealthSnapshot? = null,
    val isLoading: Boolean = false,
    val error: String? = null
)

class HealthViewModel(app: Application) : AndroidViewModel(app) {

    private val repo = HealthRepository(app)
    private val drive = DriveRepository(app)

    private val _uiState = MutableStateFlow(HealthUiState())
    val uiState: StateFlow<HealthUiState> = _uiState.asStateFlow()

    // Manual-entry save state (addendum #21)
    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _saveError = MutableStateFlow<String?>(null)
    val saveError: StateFlow<String?> = _saveError.asStateFlow()

    fun consumeSaveError() { _saveError.value = null }

    val requiredPermissions get() = repo.requiredPermissions

    init { checkStatus() }

    fun checkStatus() {
        viewModelScope.launch {
            _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.CHECKING) }
            when (repo.getSdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    // Skip upfront permission check — attempt reads directly.
                    // SecurityException from any read sets NEEDS_PERMISSION.
                    _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.GRANTED) }
                    loadData()
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NEEDS_INSTALL) }
                else ->
                    _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NOT_SUPPORTED) }
            }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        // Called after the permission launcher returns — attempt reads regardless
        // of what the granted set contains; a SecurityException during reads will
        // flip back to NEEDS_PERMISSION if something is still missing.
        _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.GRANTED) }
        loadData()
    }

    fun setWindow(days: Int) {
        _uiState.update { it.copy(selectedWindowDays = days) }
        loadData()
    }

    private fun loadData() {
        val days = _uiState.value.selectedWindowDays
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }
            runCatching { repo.readHealthData(days) }
                .onSuccess { snapshot ->
                    HealthRepository.updateCache(snapshot)
                    _uiState.update { it.copy(snapshot = snapshot, isLoading = false) }
                    if (days >= 7) maybeUpdateMemoryBaselines(snapshot)
                }
                .onFailure { e ->
                    if (e is SecurityException) {
                        _uiState.update { it.copy(isLoading = false, permissionStatus = HealthPermissionStatus.NEEDS_PERMISSION) }
                    } else {
                        _uiState.update { it.copy(isLoading = false, error = e.message) }
                    }
                }
        }
    }

    // --- Manual entry ---

    fun addWeight(kg: Double) = save { repo.writeWeight(kg) }

    fun addSteps(count: Long) {
        val now = System.currentTimeMillis()
        save { repo.writeSteps(count, now - 3_600_000L, now) }
    }

    fun addSleep(startMillis: Long, endMillis: Long) =
        save { repo.writeSleep(startMillis, endMillis) }

    fun addCalories(kcal: Double) = save { repo.writeNutrition(kcal) }

    private fun save(block: suspend () -> Result<Unit>) {
        viewModelScope.launch {
            _isSaving.value = true
            _saveError.value = null
            try {
                block()
                    .onSuccess { loadData() }
                    .onFailure { e -> _saveError.value = saveErrorMessage(e) }
            } catch (e: Exception) {
                _saveError.value = saveErrorMessage(e)
            } finally {
                _isSaving.value = false
            }
        }
    }

    private fun saveErrorMessage(e: Throwable): String = when (e) {
        is SecurityException ->
            "Health Connect hasn't granted write access. Open Health Connect and allow Carl's Brain to write this data type."
        else -> e.message?.takeIf { it.isNotBlank() }?.let { "Couldn't save: $it" }
            ?: "Couldn't save that entry. Please try again."
    }

    private suspend fun maybeUpdateMemoryBaselines(snapshot: HealthSnapshot) {
        val section = snapshot.baselineMemorySection() ?: return
        val memory = drive.getMemoryMd() ?: return
        val updated = if (memory.contains("## Health Baselines")) {
            val before = memory.substringBefore("## Health Baselines")
            val afterSection = memory.substringAfter("## Health Baselines")
            val after = if (afterSection.contains("\n## ")) afterSection.substringAfter("\n## ").let { "\n## $it" } else ""
            before + section + after
        } else {
            "$memory\n\n$section"
        }
        drive.updateMemoryMd(updated)
    }
}
