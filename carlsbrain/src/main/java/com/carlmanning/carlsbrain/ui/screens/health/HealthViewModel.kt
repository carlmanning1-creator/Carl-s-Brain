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

    val requiredPermissions get() = repo.requiredPermissions

    init { checkStatus() }

    fun checkStatus() {
        viewModelScope.launch {
            when (repo.getSdkStatus()) {
                HealthConnectClient.SDK_AVAILABLE -> {
                    if (repo.hasPermissions()) {
                        _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.GRANTED) }
                        loadData()
                    } else {
                        _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NEEDS_PERMISSION) }
                    }
                }
                HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED ->
                    _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NEEDS_INSTALL) }
                else ->
                    _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NOT_SUPPORTED) }
            }
        }
    }

    fun onPermissionsResult(granted: Set<String>) {
        viewModelScope.launch {
            if (repo.hasPermissions()) {
                _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.GRANTED) }
                loadData()
            } else {
                _uiState.update { it.copy(permissionStatus = HealthPermissionStatus.NEEDS_PERMISSION) }
            }
        }
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
                    _uiState.update { it.copy(isLoading = false, error = e.message) }
                }
        }
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
