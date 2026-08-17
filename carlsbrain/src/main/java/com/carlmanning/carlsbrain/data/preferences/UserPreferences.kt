package com.carlmanning.carlsbrain.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.carlmanning.carlsbrain.data.voice.WakeWordModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_preferences")

/**
 * One recorded wake-word / conversation activation, for diagnosing unexplained triggers.
 *
 * @param at epoch millis the activation happened.
 * @param source one of [UserPreferences.TRIGGER_SOURCE_KWS],
 *   [UserPreferences.TRIGGER_SOURCE_NOTIFICATION_ACTION],
 *   [UserPreferences.TRIGGER_SOURCE_RESUME] or [UserPreferences.TRIGGER_SOURCE_EXTERNAL_INTENT].
 * @param keywordIndex index of the matched keyword within keywords.txt, or -1 when the
 *   source is not the keyword spotter.
 * @param rms RMS level of the audio frame that fired, or -1 when not applicable.
 */
@Serializable
data class WakeTriggerEntry(
    val at: Long,
    val source: String,
    val keywordIndex: Int = -1,
    val rms: Int = -1
)

class UserPreferences(private val context: Context) {

    companion object {
        private val KEY_ANTHROPIC_API_KEY = stringPreferencesKey("anthropic_api_key")
        private val KEY_MORNING_DIGEST_HOUR = intPreferencesKey("morning_digest_hour")
        private val KEY_MORNING_DIGEST_MINUTE = intPreferencesKey("morning_digest_minute")
        private val KEY_GOOGLE_CONNECTED = booleanPreferencesKey("google_connected")
        private val KEY_TODOS_SORT_MODE = stringPreferencesKey("todos_sort_mode")
        private val KEY_NOTES_SORT_MODE = stringPreferencesKey("notes_sort_mode")
        private val KEY_TODOS_KANBAN_MODE = booleanPreferencesKey("todos_kanban_mode")
        private val KEY_SWIPE_TO_COMPLETE = booleanPreferencesKey("swipe_to_complete")
        private val KEY_BIOMETRIC_LOCK_ENABLED = booleanPreferencesKey("biometric_lock_enabled")
        private val KEY_VOICE_CAPTURE_ENABLED = booleanPreferencesKey("voice_capture_enabled")
        private val KEY_WAKE_WORD_ENABLED = booleanPreferencesKey("wake_word_enabled")
        private val KEY_WAKE_KEYWORD = stringPreferencesKey("wake_keyword")
        private val KEY_WAKE_THRESHOLD = floatPreferencesKey("wake_threshold")
        private val KEY_WAKE_RESUME_WINDOW_SEC = intPreferencesKey("wake_resume_window_sec")
        private val KEY_WAKE_QUIET_ENABLED = booleanPreferencesKey("wake_quiet_enabled")
        private val KEY_WAKE_QUIET_START_MIN = intPreferencesKey("wake_quiet_start_min")
        private val KEY_WAKE_QUIET_END_MIN = intPreferencesKey("wake_quiet_end_min")
        private val KEY_CONVERSATION_END_TONE = booleanPreferencesKey("conversation_end_tone")
        private val KEY_OPENAI_API_KEY = stringPreferencesKey("openai_api_key")
        private val KEY_FIREFLIES_API_KEY = stringPreferencesKey("fireflies_api_key")

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

        // Master switches for the remaining notification types
        private val KEY_DIGEST_ENABLED = booleanPreferencesKey("digest_enabled")
        private val KEY_REMINDERS_ENABLED = booleanPreferencesKey("reminders_enabled")
        private val KEY_WEEKLY_REVIEW_ENABLED = booleanPreferencesKey("weekly_review_enabled")

        /** One-time migration: turn the 07:00 smart morning slot off (duplicate of the digest). */
        private val KEY_MORNING_SLOT_MIGRATED_V1 = booleanPreferencesKey("morning_slot_migrated_v1")

        /** Standing instructions Carl has saved for the Dashboard briefing, JSON-encoded. */
        private val KEY_BRIEFING_RULES = stringPreferencesKey("briefing_rules")

        /**
         * The last Dashboard briefing Claude produced, plus when it landed.
         * Cached purely so the home-screen widget can show it without making an API call.
         */
        private val KEY_CACHED_BRIEFING = stringPreferencesKey("cached_briefing")
        private val KEY_CACHED_BRIEFING_AT = longPreferencesKey("cached_briefing_at")

        /** Hard ceiling so the briefing prompt can't grow without bound. */
        const val MAX_BRIEFING_RULES = 20

        private val briefingRulesJson = Json { ignoreUnknownKeys = true }
        private val briefingRulesSerializer = ListSerializer(String.serializer())

        // Busy mode — Carl is on an SES job and the app must stop volunteering chatter.
        private val KEY_BUSY_MODE_ACTIVE = booleanPreferencesKey("busy_mode_active")
        private val KEY_BUSY_MODE_STARTED_AT = longPreferencesKey("busy_mode_started_at")
        private val KEY_BUSY_MODE_NOTE_ID = longPreferencesKey("busy_mode_note_id")

        /**
         * Google Calendar ids Carl has switched OFF, stored as exclusions rather than
         * inclusions so a calendar he creates later shows up by default instead of
         * silently going missing.
         */
        private val KEY_EXCLUDED_CALENDAR_IDS = stringSetPreferencesKey("excluded_calendar_ids")

        /** Ring buffer of the last [MAX_WAKE_TRIGGER_LOG] wake-word activations, JSON-encoded. */
        private val KEY_WAKE_TRIGGER_LOG = stringPreferencesKey("wake_trigger_log")

        /** Hard cap so the trigger log can never grow without bound. */
        const val MAX_WAKE_TRIGGER_LOG = 20

        /** Default follow-up window: comfortably longer than the 8 s silence timeout. */
        const val DEFAULT_RESUME_WINDOW_SEC = 45

        /**
         * Upper bound on the follow-up window. Beyond a couple of minutes, "resuming" stops
         * feeling like continuing a sentence and starts being a stale conversation reopening
         * itself with all its prior history still loaded.
         */
        const val MAX_RESUME_WINDOW_SEC = 180

        /**
         * True when [minutesSinceMidnight] falls inside the quiet window.
         *
         * The window normally **wraps past midnight** (22:00 → 06:00), which is the case a naive
         * `in start..end` gets silently wrong — it would be empty for every overnight setting,
         * i.e. quiet hours that never actually apply. Equal start and end is treated as an empty
         * window rather than all day: switching quiet hours on with both pickers untouched
         * should not silently deafen the app permanently.
         */
        fun isWithinQuietHours(minutesSinceMidnight: Int, startMin: Int, endMin: Int): Boolean =
            when {
                startMin == endMin -> false
                startMin < endMin -> minutesSinceMidnight in startMin until endMin
                else -> minutesSinceMidnight >= startMin || minutesSinceMidnight < endMin
            }

        /** sherpa-onnx keyword spotter — the always-listening wake word. */
        const val TRIGGER_SOURCE_KWS = "KWS"
        const val TRIGGER_SOURCE_NOTIFICATION_ACTION = "NOTIFICATION_ACTION"
        const val TRIGGER_SOURCE_RESUME = "RESUME"
        const val TRIGGER_SOURCE_EXTERNAL_INTENT = "EXTERNAL_INTENT"

        private val wakeTriggerJson = Json { ignoreUnknownKeys = true }
        private val wakeTriggerSerializer = ListSerializer(WakeTriggerEntry.serializer())

        private val KEY_ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
        val KEY_VAULT_PIN_HASH = stringPreferencesKey("vault_pin_hash")

        fun hashPin(pin: String): String {
            val digest = java.security.MessageDigest.getInstance("SHA-256")
            val hashBytes = digest.digest(pin.toByteArray(Charsets.UTF_8))
            return hashBytes.joinToString("") { "%02x".format(it) }
        }
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

    val todosKanbanMode: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_TODOS_KANBAN_MODE] ?: false
    }

    suspend fun setTodosKanbanMode(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_TODOS_KANBAN_MODE] = enabled }
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

    /**
     * Drops preference keys belonging to removed features.
     *
     * Deleting the Kotlin property does not delete the stored value — DataStore keeps it
     * forever on existing installs. That matters here because the orphan is a Picovoice access
     * key: a credential, sitting in storage with nothing left that could ever read or clear it.
     * Removing it explicitly is the only way it actually goes away on Carl's phone.
     *
     * Cheap and idempotent (removing an absent key is a no-op), so it simply runs at startup
     * rather than being gated behind a migration flag that would itself need storing.
     */
    suspend fun purgeRemovedPreferences() {
        context.dataStore.edit { prefs ->
            prefs.remove(stringPreferencesKey("picovoice_access_key"))
        }
    }

    /**
     * Display name of the selected wake phrase. Always resolved through
     * [WakeWordModel.keywordFor] before use, so a stale or hand-edited value falls back to the
     * default rather than writing an out-of-vocabulary keywords.txt.
     */
    val wakeKeyword: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_KEYWORD] ?: WakeWordModel.DEFAULT_KEYWORD.displayName
    }

    suspend fun setWakeKeyword(displayName: String) {
        context.dataStore.edit { prefs -> prefs[KEY_WAKE_KEYWORD] = displayName }
    }

    /**
     * Trigger threshold for the wake phrase. 0 means unset — the model default is used and no
     * `#threshold` is written into keywords.txt. Higher values mean fewer false triggers and
     * more misses.
     */
    val wakeThreshold: Flow<Float> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_THRESHOLD] ?: 0f
    }

    suspend fun setWakeThreshold(threshold: Float) {
        context.dataStore.edit { prefs -> prefs[KEY_WAKE_THRESHOLD] = threshold.coerceIn(0f, 1f) }
    }

    /**
     * Seconds after a conversation ends unintentionally (silence timeout) during which saying
     * the wake phrase *resumes* that conversation instead of starting a fresh one.
     *
     * Clamped rather than free-form: 0 disables resuming altogether, and the upper bound stops
     * a stale conversation being silently reopened minutes later with its history intact.
     */
    val wakeResumeWindowSec: Flow<Int> = context.dataStore.data.map { prefs ->
        (prefs[KEY_WAKE_RESUME_WINDOW_SEC] ?: DEFAULT_RESUME_WINDOW_SEC)
            .coerceIn(0, MAX_RESUME_WINDOW_SEC)
    }

    suspend fun setWakeResumeWindowSec(seconds: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_RESUME_WINDOW_SEC] = seconds.coerceIn(0, MAX_RESUME_WINDOW_SEC)
        }
    }

    /** Whether the wake word stops listening during [wakeQuietStartMin]–[wakeQuietEndMin]. */
    val wakeQuietEnabled: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_QUIET_ENABLED] ?: false
    }

    /** Quiet-hours start, as minutes since midnight. Defaults to 22:00. */
    val wakeQuietStartMin: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_QUIET_START_MIN] ?: (22 * 60)
    }

    /** Quiet-hours end, as minutes since midnight. Defaults to 06:00. */
    val wakeQuietEndMin: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[KEY_WAKE_QUIET_END_MIN] ?: (6 * 60)
    }

    suspend fun setWakeQuietHours(enabled: Boolean, startMin: Int, endMin: Int) {
        context.dataStore.edit { prefs ->
            prefs[KEY_WAKE_QUIET_ENABLED] = enabled
            prefs[KEY_WAKE_QUIET_START_MIN] = startMin.coerceIn(0, 24 * 60 - 1)
            prefs[KEY_WAKE_QUIET_END_MIN] = endMin.coerceIn(0, 24 * 60 - 1)
        }
    }

    /** Whether the beep that marks the end of a voice conversation is played. */
    val conversationEndTone: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_CONVERSATION_END_TONE] ?: true
    }

    suspend fun setConversationEndTone(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_CONVERSATION_END_TONE] = enabled }
    }

    val openaiApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_OPENAI_API_KEY] ?: ""
    }

    suspend fun setOpenaiApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[KEY_OPENAI_API_KEY] = key }
    }

    val firefliesApiKey: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_FIREFLIES_API_KEY] ?: ""
    }

    suspend fun setFirefliesApiKey(key: String) {
        context.dataStore.edit { prefs -> prefs[KEY_FIREFLIES_API_KEY] = key }
    }

    suspend fun clearGoogleAccount() {
        context.dataStore.edit { prefs -> prefs[KEY_GOOGLE_CONNECTED] = false }
    }

    // ── Four-slot notification settings ───────────────────────────────────────

    // Defaults off: the 06:30 morning digest already covers the morning, and two
    // morning notifications is one too many. Carl can re-enable it in Settings.
    val notifMorningEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_NOTIF_MORNING_ENABLED] ?: false }
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

    // ── Master switches for the other notification types ─────────────────────

    val digestEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_DIGEST_ENABLED] ?: true }

    suspend fun setDigestEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_DIGEST_ENABLED] = enabled }
    }

    val remindersEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_REMINDERS_ENABLED] ?: true }

    suspend fun setRemindersEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_REMINDERS_ENABLED] = enabled }
    }

    val weeklyReviewEnabled: Flow<Boolean> = context.dataStore.data.map { it[KEY_WEEKLY_REVIEW_ENABLED] ?: true }

    suspend fun setWeeklyReviewEnabled(enabled: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_WEEKLY_REVIEW_ENABLED] = enabled }
    }

    // ── Busy mode ─────────────────────────────────────────────────────────

    /** True while busy mode is on. Suppresses ambient AI notifications. Suppresses the app's ambient AI notifications. */
    val busyModeActive: Flow<Boolean> = context.dataStore.data.map { it[KEY_BUSY_MODE_ACTIVE] ?: false }

    /** Epoch millis busy mode started, or 0L when it is off. */
    val busyModeStartedAt: Flow<Long> = context.dataStore.data.map { it[KEY_BUSY_MODE_STARTED_AT] ?: 0L }

    /**
     * Row id of the note acting as the current session log, or 0L when there is no session note.
     *
     * The log is an ordinary note rather than a table of its own, so it is searchable,
     * Drive-synced and shareable with the machinery that already exists.
     */
    val busyModeNoteId: Flow<Long> = context.dataStore.data.map { it[KEY_BUSY_MODE_NOTE_ID] ?: 0L }

    /** Set to the new note's id at session start, and back to 0L when the session ends. */
    suspend fun setBusyModeNoteId(noteId: Long) {
        context.dataStore.edit { prefs -> prefs[KEY_BUSY_MODE_NOTE_ID] = noteId }
    }

    /** Stamps the start time when turning busy mode on, and clears it when turning it off. */
    suspend fun setBusyModeActive(active: Boolean) {
        context.dataStore.edit { prefs ->
            prefs[KEY_BUSY_MODE_ACTIVE] = active
            prefs[KEY_BUSY_MODE_STARTED_AT] = if (active) System.currentTimeMillis() else 0L
        }
    }

    // ── One-time morning-slot migration ──────────────────────────────────────

    val morningSlotMigratedV1: Flow<Boolean> = context.dataStore.data.map {
        it[KEY_MORNING_SLOT_MIGRATED_V1] ?: false
    }

    /** Turns the 07:00 smart morning slot off once, keeping its saved time, then marks it done. */
    suspend fun applyMorningSlotMigrationV1() {
        context.dataStore.edit { prefs ->
            prefs[KEY_NOTIF_MORNING_ENABLED] = false
            prefs[KEY_MORNING_SLOT_MIGRATED_V1] = true
        }
    }

    // ── First-run onboarding ─────────────────────────────────────────────────
    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[KEY_ONBOARDING_COMPLETED] ?: false
    }

    suspend fun setOnboardingCompleted(value: Boolean) {
        context.dataStore.edit { prefs -> prefs[KEY_ONBOARDING_COMPLETED] = value }
    }

    // ── Briefing standing rules ──────────────────────────────────────────────
    /**
     * Standing instructions applied to every Dashboard briefing.
     * Decoded defensively: a malformed or hand-edited value yields an empty list rather than
     * crashing the Dashboard, since these rules are read on every load.
     */
    val briefingRules: Flow<List<String>> = context.dataStore.data.map { prefs ->
        decodeBriefingRules(prefs[KEY_BRIEFING_RULES])
    }

    /** No-ops on a blank rule, a duplicate, or once [MAX_BRIEFING_RULES] are already saved. */
    suspend fun addBriefingRule(rule: String) {
        val trimmed = rule.trim()
        if (trimmed.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = decodeBriefingRules(prefs[KEY_BRIEFING_RULES])
            if (trimmed in current || current.size >= MAX_BRIEFING_RULES) return@edit
            prefs[KEY_BRIEFING_RULES] = briefingRulesJson.encodeToString(briefingRulesSerializer, current + trimmed)
        }
    }

    suspend fun removeBriefingRule(rule: String) {
        context.dataStore.edit { prefs ->
            val current = decodeBriefingRules(prefs[KEY_BRIEFING_RULES])
            val updated = current.filterNot { it == rule }
            if (updated.size == current.size) return@edit
            prefs[KEY_BRIEFING_RULES] = briefingRulesJson.encodeToString(briefingRulesSerializer, updated)
        }
    }

    private fun decodeBriefingRules(raw: String?): List<String> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            briefingRulesJson.decodeFromString(briefingRulesSerializer, raw)
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(MAX_BRIEFING_RULES)
    }

    // ── Cached briefing (widget) ─────────────────────────────────────────────
    /**
     * The text of the last briefing the Dashboard generated, or "" before the first one.
     *
     * Read only by the home-screen widget, which must never call Claude itself. Note this is
     * cached as-is: if it was generated while the vault was open it may reflect vault items.
     * That is a deliberate trade-off — the item lists on the widget stay vault-filtered.
     */
    val cachedBriefing: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_CACHED_BRIEFING] ?: ""
    }

    /** Epoch millis the cached briefing was written, or 0L when there is none. */
    val cachedBriefingAt: Flow<Long> = context.dataStore.data.map { prefs ->
        prefs[KEY_CACHED_BRIEFING_AT] ?: 0L
    }

    /** No-ops on a blank briefing so the widget never caches an empty line over a good one. */
    suspend fun setCachedBriefing(briefing: String, at: Long = System.currentTimeMillis()) {
        if (briefing.isBlank()) return
        context.dataStore.edit { prefs ->
            prefs[KEY_CACHED_BRIEFING] = briefing
            prefs[KEY_CACHED_BRIEFING_AT] = at
        }
    }

    // ── Calendar include/exclude ─────────────────────────────────────────────
    /**
     * Calendars kept out of briefings and the schedule. Empty by default — everything
     * is included until Carl turns something off.
     */
    val excludedCalendarIds: Flow<Set<String>> = context.dataStore.data.map { prefs ->
        prefs[KEY_EXCLUDED_CALENDAR_IDS] ?: emptySet()
    }

    /** No-ops on a blank id, or when the set would not actually change. */
    suspend fun setCalendarExcluded(calendarId: String, excluded: Boolean) {
        if (calendarId.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = prefs[KEY_EXCLUDED_CALENDAR_IDS] ?: emptySet()
            val updated = if (excluded) current + calendarId else current - calendarId
            if (updated == current) return@edit
            prefs[KEY_EXCLUDED_CALENDAR_IDS] = updated
        }
    }

    // ── Wake-word trigger log (diagnostics) ──────────────────────────────────
    /**
     * The last [MAX_WAKE_TRIGGER_LOG] activations, newest first.
     *
     * Exists so an unexplained "the app started talking on its own" can be traced to a
     * specific source rather than guessed at. Decoded defensively: a malformed or
     * hand-edited value yields an empty list rather than breaking the wake-word service,
     * which writes to this on every activation.
     */
    val wakeTriggerLog: Flow<List<WakeTriggerEntry>> = context.dataStore.data.map { prefs ->
        decodeWakeTriggerLog(prefs[KEY_WAKE_TRIGGER_LOG])
    }

    /** Prepends an activation and drops anything past [MAX_WAKE_TRIGGER_LOG]. */
    suspend fun logWakeTrigger(
        source: String,
        keywordIndex: Int = -1,
        rms: Int = -1,
        at: Long = System.currentTimeMillis()
    ) {
        if (source.isBlank()) return
        context.dataStore.edit { prefs ->
            val current = decodeWakeTriggerLog(prefs[KEY_WAKE_TRIGGER_LOG])
            val updated = (listOf(WakeTriggerEntry(at, source, keywordIndex, rms)) + current)
                .take(MAX_WAKE_TRIGGER_LOG)
            prefs[KEY_WAKE_TRIGGER_LOG] = wakeTriggerJson.encodeToString(wakeTriggerSerializer, updated)
        }
    }

    suspend fun clearWakeTriggerLog() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_WAKE_TRIGGER_LOG) }
    }

    private fun decodeWakeTriggerLog(raw: String?): List<WakeTriggerEntry> {
        if (raw.isNullOrBlank()) return emptyList()
        return runCatching {
            wakeTriggerJson.decodeFromString(wakeTriggerSerializer, raw)
        }.getOrDefault(emptyList())
            .filter { it.source.isNotBlank() }
            .take(MAX_WAKE_TRIGGER_LOG)
    }

    // ── Vault PIN ────────────────────────────────────────────────────────────
    val vaultPinHash: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[KEY_VAULT_PIN_HASH] ?: ""
    }

    suspend fun setVaultPinHash(hash: String) {
        context.dataStore.edit { prefs -> prefs[KEY_VAULT_PIN_HASH] = hash }
    }

    suspend fun clearVaultPinHash() {
        context.dataStore.edit { prefs -> prefs.remove(KEY_VAULT_PIN_HASH) }
    }

}
