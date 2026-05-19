package com.carlmanning.carlsbrain.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

class UserPreferences(private val context: Context) {

    companion object {
        private val KEY_ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_MORNING_DIGEST_HOUR = intPreferencesKey("morning_digest_hour")
        private val KEY_MORNING_DIGEST_MINUTE = intPreferencesKey("morning_digest_minute")
        private val KEY_SHOW_VAULT_IN_DASHBOARD = booleanPreferencesKey("show_vault_in_dashboard")
        private val KEY_SHOW_VAULT_IN_NOTIFICATIONS = booleanPreferencesKey("show_vault_in_notifications")
        private val KEY_GOOGLE_CONNECTED = booleanPreferencesKey("google_connected")
        private val KEY_TODOS_SORT_MODE = stringPreferencesKey("todos_sort_mode")
        private val KEY_NOTES_SORT_MODE = stringPreferencesKey("notes_sort_mode")
        private val KEY_SWIPE_TO_COMPLETE = booleanPreferencesKey("swipe_to_complete")
        private val KEY_BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        private val KEY_VOICE_CAPTURE_ENABLED = booleanPreferencesKey("voice_capture_enabled")
    }

    val anthropicApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_ANTHROPIC_API_KEY] ?: ""
    }

    val morningDigestHour: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MORNING_DIGEST_HOUR] ?: 6
    }

    val morningDigestMinute: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_MORNING_DIGEST_MINUTE] ?: 30
    }

    val showVaultInDashboard: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_VAULT_IN_DASHBOARD] ?: true
    }

    val showVaultInNotifications: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SHOW_VAULT_IN_NOTIFICATIONS] ?: true
    }

    val isGoogleConnected: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_GOOGLE_CONNECTED] ?: false
    }

    suspend fun setAnthropicApiKey(apiKey: String) {
        context.dataStore.edit { prefs -> prefs[KEY_ANTHROPIC_API_KEY] = apiKey }
    }

    suspend fun setMorningDigestTime(hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_MORNING_DIGEST_HOUR] = hour
            prefs[KEY_MORNING_DIGEST_MINUTE] = minute
        }
    }

    suspend fun setShowVaultInDashboard(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_VAULT_IN_DASHBOARD] = show }
    }

    suspend fun setShowVaultInNotifications(show: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SHOW_VAULT_IN_NOTIFICATIONS] = show }
    }

    suspend fun setGoogleAccessToken(token: String) {
        context.dataStore.edit { prefs -> prefs[KEY_GOOGLE_CONNECTED] = token.isNotEmpty() }
    }

    val todosSortMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_TODOS_SORT_MODE] ?: "PRIORITY"
    }

    val notesSortMode: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_NOTES_SORT_MODE] ?: "UPDATED"
    }

    suspend fun setTodosSortMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_TODOS_SORT_MODE] = mode }
    }

    suspend fun setNotesSortMode(mode: String) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTES_SORT_MODE] = mode }
    }

    val swipeToCompleteEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_SWIPE_TO_COMPLETE] ?: false
    }

    suspend fun setSwipeToCompleteEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_SWIPE_TO_COMPLETE] = enabled }
    }

    val biometricLockEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_BIOMETRIC_LOCK_ENABLED] ?: true
    }

    suspend fun setBiometricLockEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_BIOMETRIC_LOCK_ENABLED] = enabled }
    }

    val voiceCaptureEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_VOICE_CAPTURE_ENABLED] ?: false
    }

    suspend fun setVoiceCaptureEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_VOICE_CAPTURE_ENABLED] = enabled }
    }

    suspend fun clearGoogleAccount() {
        context.dataStore.edit { prefs -> prefs[KEY_GOOGLE_CONNECTED] = false }
    }
}
