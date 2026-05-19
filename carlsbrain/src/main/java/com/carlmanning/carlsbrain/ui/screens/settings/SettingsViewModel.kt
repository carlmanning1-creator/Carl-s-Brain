package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.GoogleAuthManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class RestoreState {
    object Idle : RestoreState()
    object Loading : RestoreState()
    data class Success(val message: String) : RestoreState()
    data class Error(val message: String) : RestoreState()
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferences(app)
    private val googleAuthManager = GoogleAuthManager(app)
    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)

    private val _restoreState = MutableStateFlow<RestoreState>(RestoreState.Idle)
    val restoreState: StateFlow<RestoreState> = _restoreState.asStateFlow()

    val anthropicApiKey = prefs.anthropicApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val isGoogleConnected = prefs.isGoogleConnected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val morningDigestHour = prefs.morningDigestHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 6)

    val morningDigestMinute = prefs.morningDigestMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    val showVaultInDashboard = prefs.showVaultInDashboard
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val showVaultInNotifications = prefs.showVaultInNotifications
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val swipeToCompleteEnabled = prefs.swipeToCompleteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val biometricLockEnabled = prefs.biometricLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val voiceCaptureEnabled = prefs.voiceCaptureEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wakeWordEnabled = prefs.wakeWordEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val buckets = db.bucketDao().getAllBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _googleAuthIntent = MutableSharedFlow<PendingIntent>()
    val googleAuthIntent = _googleAuthIntent.asSharedFlow()

    private val _errorMessage = MutableSharedFlow<String>()
    val errorMessage = _errorMessage.asSharedFlow()

    fun connectGoogle() {
        googleAuthManager.authorize(
            onSuccess = { token ->
                viewModelScope.launch { prefs.setGoogleAccessToken(token) }
            },
            onResolutionRequired = { pendingIntent ->
                viewModelScope.launch { _googleAuthIntent.emit(pendingIntent) }
            },
            onError = { e ->
                viewModelScope.launch { _errorMessage.emit(e.message ?: "Google sign-in failed") }
            }
        )
    }

    fun handleGoogleAuthResult(data: Intent?) {
        val token = googleAuthManager.getTokenFromResult(data) ?: return
        viewModelScope.launch { prefs.setGoogleAccessToken(token) }
    }

    fun disconnectGoogle() {
        viewModelScope.launch { prefs.clearGoogleAccount() }
    }

    fun saveApiKey(key: String) {
        viewModelScope.launch {
            prefs.setAnthropicApiKey(key)
            if (key.isNotBlank()) drive.saveApiKeyToSettings(key)
        }
    }

    fun restoreFromDrive() {
        viewModelScope.launch {
            _restoreState.value = RestoreState.Loading
            var restoredParts = mutableListOf<String>()
            // Restore API key from Drive settings.json
            val apiKey = runCatching { drive.getApiKeyFromSettings() }.getOrNull()
            if (!apiKey.isNullOrBlank()) {
                prefs.setAnthropicApiKey(apiKey)
                restoredParts.add("API key")
            }
            // Trigger DriveSyncWorker to restore todos and notes
            syncFromDrive()
            restoredParts.add("notes & todos syncing")
            _restoreState.value = RestoreState.Success("Restored: ${restoredParts.joinToString(", ")}")
        }
    }

    fun dismissRestoreState() {
        _restoreState.value = RestoreState.Idle
    }

    fun saveDigestTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            prefs.setMorningDigestTime(hour, minute)
            DigestScheduler.schedule(getApplication(), hour, minute, ExistingPeriodicWorkPolicy.REPLACE)
        }
    }

    fun setShowVaultInDashboard(show: Boolean) {
        viewModelScope.launch { prefs.setShowVaultInDashboard(show) }
    }

    fun setShowVaultInNotifications(show: Boolean) {
        viewModelScope.launch { prefs.setShowVaultInNotifications(show) }
    }

    fun setSwipeToCompleteEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSwipeToCompleteEnabled(enabled) }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setBiometricLockEnabled(enabled) }
    }

    fun setVoiceCaptureEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setVoiceCaptureEnabled(enabled)
            val ctx = getApplication<Application>()
            if (enabled) {
                ctx.startForegroundService(Intent(ctx, VoiceCaptureService::class.java))
            } else {
                // Also turn off wake word when the whole feature is disabled
                prefs.setWakeWordEnabled(false)
                ctx.stopService(Intent(ctx, VoiceCaptureService::class.java))
            }
        }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setWakeWordEnabled(enabled)
            val ctx = getApplication<Application>()
            val action = if (enabled) VoiceCaptureService.ACTION_START_WAKE_WORD
                         else VoiceCaptureService.ACTION_STOP_WAKE_WORD
            ctx.startService(Intent(ctx, VoiceCaptureService::class.java).apply {
                this.action = action
            })
        }
    }

    fun syncFromDrive() {
        val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
            .setConstraints(
                Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            )
            .build()
        WorkManager.getInstance(getApplication())
            .enqueueUniqueWork("drive_sync_now", ExistingWorkPolicy.REPLACE, request)
    }

    fun createBucket(name: String, isVault: Boolean, color: String = "#6750A4") {
        val trimmed = name.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            db.bucketDao().insertBucket(
                BucketEntity(name = trimmed, isVault = isVault, isUserCreated = true, colorHex = color)
            )
        }
    }

    fun renameBucket(bucket: BucketEntity, newName: String, newColor: String = bucket.colorHex) {
        val trimmed = newName.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            db.bucketDao().updateBucket(bucket.copy(name = trimmed, colorHex = newColor))
        }
    }

    fun setBucketVault(bucket: BucketEntity, isVault: Boolean) {
        viewModelScope.launch {
            db.bucketDao().updateBucket(bucket.copy(isVault = isVault))
        }
    }

    fun deleteBucket(bucket: BucketEntity) {
        if (!bucket.isUserCreated) return
        viewModelScope.launch {
            db.bucketDao().deleteBucket(bucket)
        }
    }
}
