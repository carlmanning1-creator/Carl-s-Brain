package com.carlmanning.carlsbrain

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.ExistingPeriodicWorkPolicy
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

data class CaptureRequest(
    val type: String = "TODO",
    val startVoice: Boolean = false
)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    init {
        viewModelScope.launch {
            val prefs = CarlsBrainApp.userPreferences
            val hour = prefs.morningDigestHour.first()
            val minute = prefs.morningDigestMinute.first()
            DigestScheduler.schedule(app, hour, minute, ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private val _isVaultVisible = MutableStateFlow(false)
    val isVaultVisible: StateFlow<Boolean> = _isVaultVisible.asStateFlow()

    private val _isSyncing = MutableStateFlow(false)
    val isSyncing: StateFlow<Boolean> = _isSyncing.asStateFlow()

    private val _pendingCapture = MutableStateFlow<CaptureRequest?>(null)
    val pendingCapture: StateFlow<CaptureRequest?> = _pendingCapture.asStateFlow()

    fun toggleVaultVisibility() {
        _isVaultVisible.value = !_isVaultVisible.value
    }

    fun requestCapture(type: String = "TODO", startVoice: Boolean = false) {
        _pendingCapture.value = CaptureRequest(type, startVoice)
    }

    fun consumePendingCapture() {
        _pendingCapture.value = null
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
            WorkManager.getInstance(getApplication())
                .getWorkInfoByIdFlow(request.id)
                .first { it?.state?.isFinished == true }
            _isSyncing.value = false
        }
    }
}
