package com.carlmanning.carlsbrain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _isVaultVisible = MutableStateFlow(false)
    val isVaultVisible: StateFlow<Boolean> = _isVaultVisible.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _openCaptureRequested = MutableStateFlow(false)
    val openCaptureRequested: StateFlow<Boolean> = _openCaptureRequested.asStateFlow()

    fun toggleVaultVisibility() {
        _isVaultVisible.value = !_isVaultVisible.value
    }

    fun requestOpenCapture() {
        _openCaptureRequested.value = true
    }

    fun consumeOpenCaptureRequest() {
        _openCaptureRequested.value = false
    }

    fun syncNow() {
        viewModelScope.launch {
            _isSyncing.value = true
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .build()
            WorkManager.getInstance(getApplication())
                .enqueueUniqueWork("drive_sync_now", ExistingWorkPolicy.REPLACE, request)
            delay(3_000)
            _isSyncing.value = false
        }
    }
}
