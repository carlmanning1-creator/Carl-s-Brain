package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.ExistingPeriodicWorkPolicy
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.GoogleAuthManager
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferences(app)
    private val googleAuthManager = GoogleAuthManager(app)

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
        viewModelScope.launch { prefs.setAnthropicApiKey(key) }
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
}
