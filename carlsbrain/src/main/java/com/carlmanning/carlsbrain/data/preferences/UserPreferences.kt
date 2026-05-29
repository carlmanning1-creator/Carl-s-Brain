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
        private val KEY_GOOGLE_CONNECTED = booleanPreferencesKey("google_connected")
        private val KEY_TODOS_SORT_MODE = stringPreferencesKey("todos_sort_mode")
        private val KEY_NOTES_SORT_MODE = stringPreferencesKey("notes_sort_mode")
        private val KEY_SWIPE_TO_COMPLETE = booleanPreferencesKey("swipe_to_complete")
        private val KEY_BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        private val KEY_VOICE_CAPTURE_ENABLED = booleanPreferencesKey("voice_capture_enabled")
        private val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        private val KEY_PICOVOICE_ACCESS_KEY = stringPreferencesKey("picovoice_access_key")
        private val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")

        // Four-slot notification settings
        private val KEY_NOTIF_MORNING_ENABLED = booleanPreferencesKey("notif_morning_enabled")
        private val KEY_NOTIF_MORNING_HOUR = intPreferencesKey("notif_morning_hour")
        private val KEY_NOTIF_MORNING_MINUTE = intPreferencesKey("notif_morning_minute")

        private val KEY_NOTIF_MIDDAY_ENABLED = booleanPreferencesKey("notif_midday_enabled")
        private val KEY_NOTIF_MIDDAY_HOUR = intPreferencesKey("notif_midday_hour")
        private val KEY_NOTIF_MIDDAY_MINUTE = intPreferencesKey("notif_midday_minute")

        private val KEY_NOTIF_AFTERNOON_ENABLED = booleanPreferencesKey("notif_afternoon_enabled")
        private val KEY_NOTIF_AFTERNOON_HOUR = intPreferencesKey("notif_afternoon_hour")
        private val KEY_NOTIF_AFTERNOON_MINUTE = intPreferencesKey("notif_afternoon_minute")

        private val KEY_NOTIF_EVENING_ENABLED = booleanPreferencesKey("notif_evening_enabled")
        private val KEY_NOTIF_EVENING_HOUR = intPreferencesKey("notif_evening_hour")
        private val KEY_NOTIF_EVENING_MINUTE = intPreferencesKey("notif_evening_minute")

        private val KEY_NOTIF_AI_ENABLED = booleanPreferencesKey("notif_ai_enabled")
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

    val wakeWordEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_WORD_ENABLED] ?: false
    }

    suspend fun setWakeWordEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_WAKE_WORD_ENABLED] = enabled }
    }

    val picovoiceAccessKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_PICOVOICE_ACCESS_KEY] ?: ""
    }

    suspend fun setPicovoiceAccessKey(key: String) {
        context.dataStore.edit { prefs -> prefs[KEY_PICOVOICE_ACCESS_KEY] = key }
    }

    val openaiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENAI_API_KEY] ?: ""
    }

    suspend fun setOpenaiApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OPENAI_API_KEY] = key }
    }

    suspend fun clearGoogleAccount() {
        context.dataStore.edit { prefs -> prefs[KEY_GOOGLE_CONNECTED] = false }
    }

    // ── Four-slot notification settings ───────────────────────────────────────

    val notifMorningEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_MORNING_ENABLED] ?: true }
    val notifMorningHour: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_MORNING_HOUR] ?: 7 }
    val notifMorningMinute: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_MORNING_MINUTE] ?: 0 }

    val notifMiddayEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_MIDDAY_ENABLED] ?: true }
    val notifMiddayHour: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_MIDDAY_HOUR] ?: 12 }
    val notifMiddayMinute: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_MIDDAY_MINUTE] ?: 0 }

    val notifAfternoonEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_AFTERNOON_ENABLED] ?: true }
    val notifAfternoonHour: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_AFTERNOON_HOUR] ?: 15 }
    val notifAfternoonMinute: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_AFTERNOON_MINUTE] ?: 0 }

    val notifEveningEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_EVENING_ENABLED] ?: true }
    val notifEveningHour: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_EVENING_HOUR] ?: 18 }
    val notifEveningMinute: Flow<Int> = context.dataStore.data.map { it[KEY_NOTIF_EVENING_MINUTE] ?: 0 }

    val notifAiEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_AI_ENABLED] ?: true }

    suspend fun setNotifMorning(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_MORNING_ENABLED] = enabled
            prefs[KEY_NOTIF_MORNING_HOUR] = hour
            prefs[KEY_NOTIF_MORNING_MINUTE] = minute
        }
    }

    suspend fun setNotifMidday(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_MIDDAY_ENABLED] = enabled
            prefs[KEY_NOTIF_MIDDAY_HOUR] = hour
            prefs[KEY_NOTIF_MIDDAY_MINUTE] = minute
        }
    }

    suspend fun setNotifAfternoon(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_AFTERNOON_ENABLED] = enabled
            prefs[KEY_NOTIF_AFTERNOON_HOUR] = hour
            prefs[KEY_NOTIF_AFTERNOON_MINUTE] = minute
        }
    }

    suspend fun setNotifEvening(enabled: Boolean, hour: Int, minute: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_EVENING_ENABLED] = enabled
            prefs[KEY_NOTIF_EVENING_HOUR] = hour
            prefs[KEY_NOTIF_EVENING_MINUTE] = minute
        }
    }

    suspend fun setNotifAiEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_NOTIF_AI_ENABLED] = enabled }
    }
}
