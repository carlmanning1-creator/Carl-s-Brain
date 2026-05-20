package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * State surface for [SettingsScreen]. Each field is held as the single source of truth here
 * so the UI can bind directly without mirroring StateFlows into local Compose state. The
 * prior implementation stored everything in `remember { mutableStateOf(...) }` and silently
 * dropped settings (including the Anthropic API key) on navigation away.
 *
 * Persistence to DataStore is debounced so we don't issue a disk write on every keystroke
 * while still guaranteeing eventual durability if the user navigates away or backgrounds
 * the app. Switches persist immediately because their state changes are discrete.
 */
@OptIn(FlowPreview::class)
class SettingsViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs: UserPreferences =
        (application as CarlsBrainApp).userPreferences

    // ---------- Editing state (source of truth for the UI) ----------

    private val _apiKey = MutableStateFlow("")
    val apiKey: StateFlow<String> = _apiKey.asStateFlow()

    /** Free-form text so the user can type "1" on the way to "12" without rejection. */
    private val _digestHourText = MutableStateFlow("6")
    val digestHourText: StateFlow<String> = _digestHourText.asStateFlow()

    private val _digestMinuteText = MutableStateFlow("30")
    val digestMinuteText: StateFlow<String> = _digestMinuteText.asStateFlow()

    private val _showVaultInDashboard = MutableStateFlow(true)
    val showVaultInDashboard: StateFlow<Boolean> = _showVaultInDashboard.asStateFlow()

    private val _showVaultInNotifications = MutableStateFlow(true)
    val showVaultInNotifications: StateFlow<Boolean> = _showVaultInNotifications.asStateFlow()

    // ---------- Validation surface ----------

    val digestHourValid: StateFlow<Boolean> = _digestHourText
        .map { it.toIntOrNull() in 0..23 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    val digestMinuteValid: StateFlow<Boolean> = _digestMinuteText
        .map { it.toIntOrNull() in 0..59 }
        .stateIn(viewModelScope, SharingStarted.Eagerly, true)

    init {
        // Hydrate editing state from DataStore once, then persist changes back.
        viewModelScope.launch {
            _apiKey.value = prefs.anthropicApiKey.first()
            _digestHourText.value = prefs.morningDigestHour.first().toString()
            _digestMinuteText.value = prefs.morningDigestMinute.first().toString()
            _showVaultInDashboard.value = prefs.showVaultInDashboard.first()
            _showVaultInNotifications.value = prefs.showVaultInNotifications.first()

            // Debounce-persist further edits. drop(1) skips the value we just hydrated.
            _apiKey.drop(1).debounce(PERSIST_DEBOUNCE_MS).onEach { value ->
                prefs.setAnthropicApiKey(value)
            }.launchIn(viewModelScope)

            _digestHourText.drop(1).debounce(PERSIST_DEBOUNCE_MS).onEach { text ->
                text.toIntOrNull()?.takeIf { it in 0..23 }?.let { prefs.setMorningDigestHour(it) }
            }.launchIn(viewModelScope)

            _digestMinuteText.drop(1).debounce(PERSIST_DEBOUNCE_MS).onEach { text ->
                text.toIntOrNull()?.takeIf { it in 0..59 }?.let { prefs.setMorningDigestMinute(it) }
            }.launchIn(viewModelScope)
        }
    }

    fun setApiKey(value: String) {
        _apiKey.value = value
    }

    /** Accepts free-form text (digits only, max 2 chars). Validation happens at persist time. */
    fun setDigestHourText(value: String) {
        if (value.length <= 2 && value.all { it.isDigit() }) {
            _digestHourText.value = value
        }
    }

    fun setDigestMinuteText(value: String) {
        if (value.length <= 2 && value.all { it.isDigit() }) {
            _digestMinuteText.value = value
        }
    }

    fun setShowVaultInDashboard(show: Boolean) {
        _showVaultInDashboard.value = show
        viewModelScope.launch { prefs.setShowVaultInDashboard(show) }
    }

    fun setShowVaultInNotifications(show: Boolean) {
        _showVaultInNotifications.value = show
        viewModelScope.launch { prefs.setShowVaultInNotifications(show) }
    }

    private companion object {
        /** Wait this long after the last keystroke before writing to DataStore. */
        const val PERSIST_DEBOUNCE_MS = 300L
    }
}
