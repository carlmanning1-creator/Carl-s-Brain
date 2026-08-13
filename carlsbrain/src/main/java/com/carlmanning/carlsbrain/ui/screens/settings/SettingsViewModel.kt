package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.DigestAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.GoogleAuthManager
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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

    val openaiApiKey = prefs.openaiApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val isGoogleConnected = prefs.isGoogleConnected
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val morningDigestHour = prefs.morningDigestHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 6)

    val morningDigestMinute = prefs.morningDigestMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 30)

    val swipeToCompleteEnabled = prefs.swipeToCompleteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val biometricLockEnabled = prefs.biometricLockEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val wakeWordEnabled = prefs.wakeWordEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val picovoiceAccessKey = prefs.picovoiceAccessKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    val firefliesApiKey = prefs.firefliesApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    // ── Smart notification settings ───────────────────────────────────────────
    val notifMorningEnabled = prefs.notifMorningEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifMorningHour = prefs.notifMorningHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 7)
    val notifMorningMinute = prefs.notifMorningMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notifMiddayEnabled = prefs.notifMiddayEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifMiddayHour = prefs.notifMiddayHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 12)
    val notifMiddayMinute = prefs.notifMiddayMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notifAfternoonEnabled = prefs.notifAfternoonEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifAfternoonHour = prefs.notifAfternoonHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 15)
    val notifAfternoonMinute = prefs.notifAfternoonMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notifEveningEnabled = prefs.notifEveningEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)
    val notifEveningHour = prefs.notifEveningHour
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 18)
    val notifEveningMinute = prefs.notifEveningMinute
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    val notifAiEnabled = prefs.notifAiEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

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

    fun saveOpenaiApiKey(key: String) {
        viewModelScope.launch { prefs.setOpenaiApiKey(key) }
    }

    fun saveFirefliesApiKey(key: String) {
        viewModelScope.launch { prefs.setFirefliesApiKey(key) }
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
            DigestAlarmScheduler.schedule(getApplication(), hour, minute)
        }
    }

    fun setSwipeToCompleteEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setSwipeToCompleteEnabled(enabled) }
    }

    fun setBiometricLockEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setBiometricLockEnabled(enabled) }
    }

    fun setWakeWordEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setWakeWordEnabled(enabled)
            val ctx = getApplication<Application>()
            if (enabled) {
                ctx.startForegroundService(
                    Intent(ctx, VoiceCaptureService::class.java).apply {
                        action = VoiceCaptureService.ACTION_START_WAKE_WORD
                    }
                )
            } else {
                ctx.startService(
                    Intent(ctx, VoiceCaptureService::class.java).apply {
                        action = VoiceCaptureService.ACTION_DISABLE_WAKE_WORD
                    }
                )
            }
        }
    }

    fun savePicovoiceAccessKey(key: String) {
        viewModelScope.launch {
            prefs.setPicovoiceAccessKey(key)
            // If wake word is enabled, kick the service to retry the audio loop
            // now that a key is available (the loop exits early when key is blank).
            if (prefs.wakeWordEnabled.first()) {
                val ctx = getApplication<Application>()
                ctx.startForegroundService(
                    Intent(ctx, VoiceCaptureService::class.java).apply {
                        action = VoiceCaptureService.ACTION_START_WAKE_WORD
                    }
                )
            }
        }
    }

    fun forceResyncNotes() {
        viewModelScope.launch {
            db.noteDao().markAllNotesUnsynced()
            syncFromDrive()
            _restoreState.value = RestoreState.Success("All notes queued for re-upload to Drive")
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

    fun saveNotifSlot(
        slot: SmartNotificationWorker.Slot,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ) {
        viewModelScope.launch {
            when (slot) {
                SmartNotificationWorker.Slot.MORNING ->
                    prefs.setNotifMorning(enabled, hour, minute)
                SmartNotificationWorker.Slot.MIDDAY ->
                    prefs.setNotifMidday(enabled, hour, minute)
                SmartNotificationWorker.Slot.AFTERNOON ->
                    prefs.setNotifAfternoon(enabled, hour, minute)
                SmartNotificationWorker.Slot.EVENING ->
                    prefs.setNotifEvening(enabled, hour, minute)
            }
            SmartNotificationAlarmScheduler.scheduleSlot(getApplication(), slot, enabled, hour, minute)
        }
    }

    fun setNotifAiEnabled(enabled: Boolean) {
        viewModelScope.launch { prefs.setNotifAiEnabled(enabled) }
    }

    // ── Digest preview ───────────────────────────────────────────────────────
    // Mirrors SmartNotificationWorker.doWork() exactly, including its vault-safe
    // query (todoDao().getVisibleNonVaultTodos()). Vault items must never reach a
    // notification — nor this preview of one.

    private val _digestPreview = MutableStateFlow<String?>(null)
    val digestPreview: StateFlow<String?> = _digestPreview.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    fun generateDigestPreview(slot: SmartNotificationWorker.Slot) {
        viewModelScope.launch {
            _isPreviewLoading.value = true
            _digestPreview.value = null
            val text = runCatching { buildDigestPreview(slot) }.getOrElse { "" }
            _digestPreview.value = text
            _isPreviewLoading.value = false
        }
    }

    fun clearDigestPreview() {
        _digestPreview.value = null
        _isPreviewLoading.value = false
    }

    private suspend fun buildDigestPreview(slot: SmartNotificationWorker.Slot): String {
        val app = getApplication<Application>()

        // Vault-safe: same DAO query the notification worker uses.
        val priorityTodos = db.todoDao().getVisibleNonVaultTodos().first()
            .filter { !it.isDone }
            .let { todos ->
                when (slot) {
                    SmartNotificationWorker.Slot.MORNING -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.MIDDAY -> todos.filter { it.priority == 0 }
                    SmartNotificationWorker.Slot.AFTERNOON -> todos.filter { it.priority in listOf(0, 1) }
                    SmartNotificationWorker.Slot.EVENING -> todos
                }
            }

        val todayEvents: List<CalendarEvent> = runCatching {
            val today = LocalDate.now()
            val zone = ZoneId.systemDefault()
            CalendarRepository(app).getUpcomingEvents(daysAhead = 2)
                .getOrThrow()
                .filter {
                    val eventDate = Instant.ofEpochMilli(it.startMs).atZone(zone).toLocalDate()
                    when (slot) {
                        SmartNotificationWorker.Slot.MORNING,
                        SmartNotificationWorker.Slot.MIDDAY,
                        SmartNotificationWorker.Slot.AFTERNOON -> eventDate == today
                        SmartNotificationWorker.Slot.EVENING -> eventDate == today.plusDays(1)
                    }
                }
        }.getOrElse { emptyList() }

        if (priorityTodos.isEmpty() && todayEvents.isEmpty()) return ""

        val aiEnabled = prefs.notifAiEnabled.first()
        val apiKey = prefs.anthropicApiKey.first()

        return if (aiEnabled && apiKey.isNotBlank()) {
            runCatching {
                withTimeoutOrNull(10_000L) {
                    CarlsBrainApp.claudeClient.chat(
                        messages = listOf(ApiMessage("user", buildPreviewPrompt(slot, todayEvents, priorityTodos))),
                        systemPrompt = "You are Carl's assistant. Carl has ADHD and works as an NSW SES Deputy. Be direct and warm. One sentence max.",
                        model = ClaudeClient.HAIKU
                    ).getOrNull()
                }
            }.getOrNull() ?: buildPreviewFallback(slot, todayEvents, priorityTodos)
        } else {
            buildPreviewFallback(slot, todayEvents, priorityTodos)
        }
    }

    private fun buildPreviewPrompt(
        slot: SmartNotificationWorker.Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
        val eventsStr = if (events.isEmpty()) "no calendar events"
        else events.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }
        val todosStr = if (todos.isEmpty()) "no pending tasks"
        else todos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }

        return when (slot) {
            SmartNotificationWorker.Slot.MORNING ->
                "Give Carl a concise morning briefing in 1 sentence. Today: $eventsStr. Priority tasks: $todosStr. End with one quick nudge. No bullet points."
            SmartNotificationWorker.Slot.MIDDAY ->
                "Quick midday check-in for Carl in 1 sentence. Urgent tasks right now: $todosStr. Events: $eventsStr. Be direct."
            SmartNotificationWorker.Slot.AFTERNOON ->
                "Afternoon nudge for Carl in 1 sentence. Top urgent tasks: $todosStr. Events remaining today: $eventsStr. Encourage action."
            SmartNotificationWorker.Slot.EVENING ->
                "Evening prep for Carl in 1 sentence. Tomorrow: $eventsStr. Still incomplete today: $todosStr. Suggest wrapping up or planning ahead."
        }
    }

    private fun buildPreviewFallback(
        slot: SmartNotificationWorker.Slot,
        events: List<CalendarEvent>,
        todos: List<TodoEntity>
    ): String {
        val parts = mutableListOf<String>()
        when (slot) {
            SmartNotificationWorker.Slot.MORNING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} today")
                if (todos.isNotEmpty()) parts.add("${todos.size} priority task${if (todos.size > 1) "s" else ""}")
            }
            SmartNotificationWorker.Slot.MIDDAY -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} urgent item${if (todos.size > 1) "s" else ""} need attention")
                else parts.add("All clear — no urgent tasks")
            }
            SmartNotificationWorker.Slot.AFTERNOON -> {
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} still pending")
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} remaining")
            }
            SmartNotificationWorker.Slot.EVENING -> {
                if (events.isNotEmpty()) parts.add("${events.size} event${if (events.size > 1) "s" else ""} tomorrow")
                if (todos.isNotEmpty()) parts.add("${todos.size} task${if (todos.size > 1) "s" else ""} incomplete")
            }
        }
        return parts.joinToString(" · ")
    }

    // ── Vault PIN ────────────────────────────────────────────────────────────

    val vaultPinHash = prefs.vaultPinHash
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    fun saveVaultPin(pin: String) {
        viewModelScope.launch {
            prefs.setVaultPinHash(com.carlmanning.carlsbrain.data.preferences.UserPreferences.hashPin(pin))
        }
    }

    fun clearVaultPin() {
        viewModelScope.launch { prefs.clearVaultPinHash() }
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
