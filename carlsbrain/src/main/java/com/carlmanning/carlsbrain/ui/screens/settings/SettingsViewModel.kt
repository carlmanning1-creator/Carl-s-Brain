package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import android.app.PendingIntent
import android.content.Intent
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.export.BrainExporter
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.worker.DigestAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.DigestGenerator
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.NotificationScheduler
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.AuthResolutionException
import com.carlmanning.carlsbrain.data.remote.AvailableCalendar
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.GoogleAuthManager
import com.carlmanning.carlsbrain.data.voice.WakeWordModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the Calendars sub-section in the Google Account accordion is currently showing. */
sealed class CalendarListState {
    object NotConnected : CalendarListState()
    object Loading : CalendarListState()
    data class Ready(val calendars: List<AvailableCalendar>) : CalendarListState()
    data class Error(val message: String) : CalendarListState()
}

sealed class RestoreState {
    object Idle : RestoreState()
    object Loading : RestoreState()
    data class Success(val message: String) : RestoreState()
    data class Error(val message: String) : RestoreState()
}

/** Progress of the "Export everything" zip in the Google Account accordion. */
sealed class ExportState {
    object Idle : ExportState()
    data class Running(val step: String) : ExportState()
    data class Success(val message: String) : ExportState()
    data class Error(val message: String) : ExportState()
}

class SettingsViewModel(app: Application) : AndroidViewModel(app) {

    private val prefs = UserPreferences(app)
    private val googleAuthManager = GoogleAuthManager(app)
    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)
    private val calendarRepo = CalendarRepository(app)

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

    val wakeKeyword = prefs.wakeKeyword
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WakeWordModel.DEFAULT_KEYWORD.displayName
        )

    val wakeThreshold = prefs.wakeThreshold
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0f)

    /**
     * Recent activations, newest first — surfaced in Settings so an unexplained wake-up can be
     * attributed to a spoken phrase versus a pocket tap on the notification.
     */
    val wakeTriggerLog = prefs.wakeTriggerLog
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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

    val digestEnabled = prefs.digestEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val remindersEnabled = prefs.remindersEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    val weeklyReviewEnabled = prefs.weeklyReviewEnabled
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
        viewModelScope.launch {
            prefs.clearGoogleAccount()
            // The list belongs to the account that just went away.
            _calendarListState.value = CalendarListState.NotConnected
        }
    }

    // ── Per-calendar include/exclude ─────────────────────────────────────────

    private val _calendarListState = MutableStateFlow<CalendarListState>(CalendarListState.NotConnected)
    val calendarListState: StateFlow<CalendarListState> = _calendarListState.asStateFlow()

    val excludedCalendarIds = prefs.excludedCalendarIds
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    // One load at a time: reopening the accordion while a fetch is in flight must not
    // start a second one, nor let a slow reply overwrite a newer result.
    private var calendarLoadJob: Job? = null

    /** Called when the Google accordion opens, and by the retry button. */
    fun loadCalendars() {
        calendarLoadJob?.cancel()
        calendarLoadJob = viewModelScope.launch {
            if (!prefs.isGoogleConnected.first()) {
                _calendarListState.value = CalendarListState.NotConnected
                return@launch
            }
            _calendarListState.value = CalendarListState.Loading
            val result = calendarRepo.getAvailableCalendars()
            ensureActive()
            _calendarListState.value = result.fold(
                onSuccess = { CalendarListState.Ready(it) },
                onFailure = { e ->
                    if (e is AuthResolutionException) _googleAuthIntent.emit(e.pendingIntent)
                    CalendarListState.Error(e.message ?: "Couldn't load your calendars")
                }
            )
        }
    }

    /**
     * Turns a calendar on or off. The primary calendar is not togglable — the switch is
     * disabled in the UI and the request is refused here too, so a stale composition
     * cannot lock Carl out of his own events.
     */
    fun setCalendarExcluded(calendar: AvailableCalendar, excluded: Boolean) {
        if (calendar.isPrimary) return
        viewModelScope.launch {
            prefs.setCalendarExcluded(calendar.id, excluded)
            // Cached rows are keyed by calendar name, so drop this calendar's cached
            // events now — otherwise the offline path and the widget keep showing them
            // until the next successful fetch.
            if (excluded) {
                runCatching { calendarRepo.removeCachedEventsForCalendars(setOf(calendar.name)) }
            }
            // Refill the cache through the filtered fetch. Re-including a calendar needs
            // this to bring its events back; failure is fine — the next fetch will do it.
            runCatching { calendarRepo.getUpcomingEvents() }
        }
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

    // ── Export everything ────────────────────────────────────────────────────
    // A plain .zip of Markdown and CSV, written straight into the document the system
    // file picker handed back. Nothing app-private, no storage permission, no importer.

    private val _exportState = MutableStateFlow<ExportState>(ExportState.Idle)
    val exportState: StateFlow<ExportState> = _exportState.asStateFlow()

    /** Whether there is anything in the vault worth offering to include. */
    val hasVaultBuckets = db.bucketDao().getAllBuckets()
        .map { buckets -> buckets.any { it.isVault } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Suggested file name for the picker, so every export is self-dating. */
    fun suggestedExportFileName(): String {
        val stamp = java.time.LocalDateTime.now()
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmm"))
        return "carls-brain-export-$stamp.zip"
    }

    /**
     * Writes the export to [uri].
     *
     * [includeVault] is what Carl chose in the dialog; [vaultUnlocked] is the live vault
     * gate state (AppViewModel.isVaultVisible, passed down through SettingsScreen). Vault
     * content is written only when BOTH are true — re-checked here rather than trusted from
     * the composition, so the export can never become a way around the vault gate.
     */
    fun exportEverything(uri: Uri, includeVault: Boolean, vaultUnlocked: Boolean) {
        if (_exportState.value is ExportState.Running) return
        val effectiveIncludeVault = includeVault && vaultUnlocked
        // Runs on the application scope, not viewModelScope: navigating out of Settings
        // mid-export would otherwise cancel the write and leave a truncated zip behind with
        // no warning. State updates below are best-effort — if the ViewModel is gone nobody
        // is watching, but the file still finishes correctly, which is the part that matters.
        CarlsBrainApp.appScope.launch {
            _exportState.value = ExportState.Running("Starting…")
            val result = BrainExporter.export(
                context = getApplication(),
                uri = uri,
                includeVault = effectiveIncludeVault
            ) { progress ->
                when (progress) {
                    is BrainExporter.Progress.Step ->
                        _exportState.value = ExportState.Running(progress.label)
                }
            }
            _exportState.value = result.fold(
                onSuccess = { s ->
                    val parts = buildList {
                        add("${s.noteCount} notes")
                        add("${s.todoCount} to-dos")
                        add("${s.meetingCount} meetings")
                        add("${s.eventCount} events")
                        if (s.memoryIncluded) add("memory.md")
                    }
                    val vaultNote = when {
                        s.includedVault -> " Vault included — the zip is not encrypted."
                        includeVault -> " Vault was left out: the vault wasn't unlocked."
                        else -> " Vault items were left out."
                    }
                    ExportState.Success("Exported ${parts.joinToString(", ")}.$vaultNote")
                },
                onFailure = { e ->
                    ExportState.Error(e.message ?: "Export failed — nothing was written")
                }
            )
        }
    }

    fun dismissExportState() {
        _exportState.value = ExportState.Idle
    }

    fun saveDigestTime(hour: Int, minute: Int) {
        viewModelScope.launch {
            prefs.setMorningDigestTime(hour, minute)
            // Only arm the alarm if the digest is actually on — a time change while it
            // is off must not resurrect it.
            if (prefs.digestEnabled.first()) {
                DigestAlarmScheduler.schedule(getApplication(), hour, minute)
            }
        }
    }

    fun setDigestEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setDigestEnabled(enabled)
            if (enabled) {
                DigestAlarmScheduler.schedule(
                    getApplication(),
                    prefs.morningDigestHour.first(),
                    prefs.morningDigestMinute.first()
                )
            } else {
                // Cancel, not just suppress — a disabled digest must stop waking the device.
                DigestAlarmScheduler.cancel(getApplication())
            }
        }
    }

    fun setRemindersEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setRemindersEnabled(enabled)
            val ctx = getApplication<Application>()
            val todos = db.todoDao().getActiveReminders()
            todos.forEach { todo ->
                if (enabled) {
                    val reminderAt = todo.reminderAt ?: return@forEach
                    ReminderScheduler.schedule(ctx, todo.id, todo.title, reminderAt)
                } else {
                    ReminderScheduler.cancel(ctx, todo.id)
                }
            }
        }
    }

    fun setWeeklyReviewEnabled(enabled: Boolean) {
        viewModelScope.launch {
            prefs.setWeeklyReviewEnabled(enabled)
            if (enabled) {
                NotificationScheduler.scheduleWeeklyReview(
                    getApplication(),
                    ExistingPeriodicWorkPolicy.REPLACE
                )
            } else {
                WorkManager.getInstance(getApplication())
                    .cancelUniqueWork(WeeklyReviewWorker.WORK_NAME)
            }
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

    val wakeResumeWindowSec = prefs.wakeResumeWindowSec
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            UserPreferences.DEFAULT_RESUME_WINDOW_SEC
        )

    val wakeQuietEnabled = prefs.wakeQuietEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val wakeQuietStartMin = prefs.wakeQuietStartMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 22 * 60)

    val wakeQuietEndMin = prefs.wakeQuietEndMin
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 6 * 60)

    val conversationEndTone = prefs.conversationEndTone
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    fun setWakeResumeWindowSec(seconds: Int) {
        viewModelScope.launch {
            prefs.setWakeResumeWindowSec(seconds)
            // The service caches this value, so it needs the loop recycled to pick it up.
            restartWakeWordLoop()
        }
    }

    fun setWakeQuietHours(enabled: Boolean, startMin: Int, endMin: Int) {
        viewModelScope.launch {
            prefs.setWakeQuietHours(enabled, startMin, endMin)
            // Restart so the change applies now — entering a window that has already begun
            // must release the mic immediately, not at the next 5-minute tick.
            restartWakeWordLoop()
        }
    }

    fun setConversationEndTone(enabled: Boolean) {
        // No restart: the service reads this preference live at each conversation end.
        viewModelScope.launch { prefs.setConversationEndTone(enabled) }
    }

    fun clearWakeTriggerLog() {
        viewModelScope.launch { prefs.clearWakeTriggerLog() }
    }

    fun setWakeKeyword(displayName: String) {
        viewModelScope.launch {
            prefs.setWakeKeyword(displayName)
            restartWakeWordLoop()
        }
    }

    fun setWakeThreshold(threshold: Float) {
        viewModelScope.launch {
            prefs.setWakeThreshold(threshold)
            restartWakeWordLoop()
        }
    }

    /**
     * Recycles the wake-word loop so a new phrase or threshold takes effect without an app
     * restart — keywords.txt is rewritten when the loop starts, not while it is running.
     *
     * A plain START would be a no-op: startWakeWordLoop() returns early while a loop is
     * already listening, so the change would silently not apply.
     */
    private suspend fun restartWakeWordLoop() {
        if (!prefs.wakeWordEnabled.first()) return
        val ctx = getApplication<Application>()
        // startForegroundService, not startService: the service may not be running yet.
        ctx.startForegroundService(
            Intent(ctx, VoiceCaptureService::class.java).apply {
                action = VoiceCaptureService.ACTION_RESTART_WAKE_WORD
            }
        )
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
    // Delegates to DigestGenerator — the same code path that builds the real
    // notification — so the preview can never drift from what Carl receives.
    // That generator uses the vault-safe query (todoDao().getVisibleNonVaultTodos());
    // vault items must never reach a notification, nor this preview of one.

    data class DigestPreview(
        val slot: SmartNotificationWorker.Slot,
        val text: String
    )

    private val _digestPreview = MutableStateFlow<DigestPreview?>(null)
    val digestPreview: StateFlow<DigestPreview?> = _digestPreview.asStateFlow()

    private val _isPreviewLoading = MutableStateFlow(false)
    val isPreviewLoading: StateFlow<Boolean> = _isPreviewLoading.asStateFlow()

    // Only one preview may be in flight: a second tap cancels the first, so a
    // slow slot can never land its text in a sheet that has moved on (and we
    // never pay for two concurrent Claude + Calendar fetches).
    private var previewJob: Job? = null

    fun generateDigestPreview(slot: SmartNotificationWorker.Slot) {
        previewJob?.cancel()
        previewJob = viewModelScope.launch {
            _isPreviewLoading.value = true
            _digestPreview.value = null
            val text = runCatching {
                DigestGenerator.generate(getApplication(), slot)
            }.getOrElse { "" }
            // runCatching swallows CancellationException too, so re-check before
            // publishing — a cancelled job must never touch the shared state.
            ensureActive()
            _digestPreview.value = DigestPreview(slot, text)
            _isPreviewLoading.value = false
        }
    }

    fun clearDigestPreview() {
        previewJob?.cancel()
        previewJob = null
        _digestPreview.value = null
        _isPreviewLoading.value = false
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

    // ── Bucket deletion ──────────────────────────────────────────────────────
    // TodoEntity and NoteEntity both declare onDelete = CASCADE on bucketId, so
    // removing a bucket row destroys every row still pointing at it — bypassing
    // deletedAt, Recently Deleted and the tombstone sync. The CASCADE stays (removing
    // it needs a schema migration); instead every path below guarantees the bucket is
    // empty of referencing rows BEFORE its row is deleted, so the CASCADE can only
    // ever fire on zero rows.

    /**
     * What is inside a bucket the user has asked to delete. [blockedReason] non-null
     * means deletion is refused outright.
     */
    data class BucketDeletionInfo(
        val bucket: BucketEntity,
        val todoCount: Int,
        val noteCount: Int,
        val meetingCount: Int,
        val moveTargets: List<BucketEntity>,
        val blockedReason: String? = null
    ) {
        val isEmpty: Boolean get() = todoCount == 0 && noteCount == 0 && meetingCount == 0
    }

    private val _pendingBucketDeletion = MutableStateFlow<BucketDeletionInfo?>(null)
    val pendingBucketDeletion: StateFlow<BucketDeletionInfo?> = _pendingBucketDeletion.asStateFlow()

    /**
     * Counts a bucket's contents and opens the appropriate confirmation. Nothing is
     * written here — this only gathers the facts the dialog needs.
     */
    fun requestBucketDeletion(bucket: BucketEntity) {
        if (!bucket.isUserCreated) return
        viewModelScope.launch {
            val all = db.bucketDao().getAllBuckets().first()
            val targets = all.filter { it.id != bucket.id }
            val nonVaultCount = db.bucketDao().getNonVaultBucketCount()

            val blocked = when {
                targets.isEmpty() ->
                    "This is your only bucket. Items need somewhere to live — create another bucket first."
                !bucket.isVault && nonVaultCount <= 1 ->
                    "This is your last non-vault bucket. Items need somewhere to live — create another bucket first."
                else -> null
            }

            _pendingBucketDeletion.value = BucketDeletionInfo(
                bucket = bucket,
                todoCount = db.todoDao().countInBucket(bucket.id),
                noteCount = db.noteDao().countInBucket(bucket.id),
                meetingCount = liveMeetingIdsInBucket(bucket.id).size,
                moveTargets = targets,
                blockedReason = blocked
            )
        }
    }

    fun cancelBucketDeletion() {
        _pendingBucketDeletion.value = null
    }

    /**
     * Safe path: reassigns every todo, note and meeting to [destBucketId], then deletes
     * the now-empty bucket. Nothing is deleted and nothing changes its deletedAt state.
     */
    fun moveContentsAndDeleteBucket(destBucketId: Long) {
        val info = _pendingBucketDeletion.value ?: return
        val bucket = info.bucket
        if (info.blockedReason != null) return
        if (destBucketId == bucket.id) return
        viewModelScope.launch {
            // Meeting ids are read from Flow-backed queries, so they are gathered
            // outside the transaction — collecting Room Flows inside one can deadlock.
            val meetingIds = allMeetingIdsInBucket(bucket.id)
            runCatching {
                db.withTransaction {
                    reassignAll(bucket.id, destBucketId, meetingIds)
                    // Bucket row LAST, and only once nothing references it.
                    db.bucketDao().deleteBucket(bucket)
                }
            }.onFailure {
                _errorMessage.emit(it.message ?: "Could not move items — nothing was deleted")
            }
            _pendingBucketDeletion.value = null
        }
    }

    /**
     * Destructive path, made recoverable: soft-deletes every todo and note so they land
     * in Recently Deleted, then reassigns ALL rows (soft-deleted ones included) onto a
     * surviving bucket so the CASCADE has nothing to destroy, then deletes the bucket.
     *
     * [typedName] must match the bucket name (trimmed, case-insensitive) — the caller's
     * button is gated on this too, but it is re-checked here so the guard cannot be
     * bypassed by a stale composition.
     */
    fun deleteBucketAndContents(typedName: String) {
        val info = _pendingBucketDeletion.value ?: return
        val bucket = info.bucket
        if (info.blockedReason != null) return
        if (!typedName.trim().equals(bucket.name.trim(), ignoreCase = true)) return

        // Prefer a fallback of the same vault-ness: a restored vault item must not
        // reappear in a normal bucket.
        val fallback = info.moveTargets.firstOrNull { it.isVault == bucket.isVault }
            ?: info.moveTargets.firstOrNull()
            ?: return

        viewModelScope.launch {
            // Flow-backed meeting queries are read outside the transaction — collecting
            // Room Flows inside one can deadlock.
            val liveMeetingIds = liveMeetingIdsInBucket(bucket.id)
            val allMeetingIds = allMeetingIdsInBucket(bucket.id)
            runCatching {
                db.withTransaction {
                    val now = System.currentTimeMillis()
                    db.todoDao().getIdsInBucket(bucket.id).forEach { id ->
                        db.todoDao().softDeleteTodo(id, now)
                    }
                    db.noteDao().getIdsInBucket(bucket.id).forEach { id ->
                        db.noteDao().softDeleteNote(id, now)
                    }
                    liveMeetingIds.forEach { id ->
                        db.meetingDao().softDeleteMeeting(id, now)
                    }
                    // Everything is now soft-deleted but still points at this bucket —
                    // move it off before the bucket row goes, or CASCADE would erase it
                    // from Recently Deleted.
                    reassignAll(bucket.id, fallback.id, allMeetingIds)
                    db.bucketDao().deleteBucket(bucket)
                }
            }.onFailure {
                _errorMessage.emit(it.message ?: "Could not delete bucket — nothing was lost")
            }
            _pendingBucketDeletion.value = null
        }
    }

    /**
     * Moves every row referencing [fromId] onto [toId] — todos and notes including
     * soft-deleted rows, and meetings (which reference bucketId with no FK, so they
     * would otherwise be left pointing at a dead id).
     */
    private suspend fun reassignAll(fromId: Long, toId: Long, meetingIds: List<Long>) {
        db.todoDao().moveAllToBucket(fromId, toId)
        db.noteDao().moveAllToBucket(fromId, toId)
        meetingIds.forEach { db.meetingDao().setBucket(it, toId) }
    }

    /** Meetings currently visible (not in the bin) that reference this bucket. */
    private suspend fun liveMeetingIdsInBucket(bucketId: Long): List<Long> =
        db.meetingDao().getAllMeetings().first()
            .filter { it.bucketId == bucketId }
            .map { it.id }

    /** Every meeting referencing this bucket, including ones in Recently Deleted. */
    private suspend fun allMeetingIdsInBucket(bucketId: Long): List<Long> =
        (db.meetingDao().getAllMeetings().first() + db.meetingDao().getDeletedMeetings().first())
            .filter { it.bucketId == bucketId }
            .map { it.id }

    /** Empty bucket only — no contents to move or delete. */
    fun deleteEmptyBucket() {
        val info = _pendingBucketDeletion.value ?: return
        if (info.blockedReason != null || !info.isEmpty) return
        val fallback = info.moveTargets.firstOrNull { it.isVault == info.bucket.isVault }
            ?: info.moveTargets.firstOrNull()
            ?: return
        viewModelScope.launch {
            val meetingIds = allMeetingIdsInBucket(info.bucket.id)
            runCatching {
                db.withTransaction {
                    // Re-check inside the transaction: something may have been captured
                    // into this bucket while the dialog was open.
                    val stillEmpty = db.todoDao().countInBucket(info.bucket.id) == 0 &&
                        db.noteDao().countInBucket(info.bucket.id) == 0
                    if (!stillEmpty) error("Bucket is no longer empty — try again")
                    // Sweep any soft-deleted rows off the bucket regardless, so the
                    // CASCADE cannot reach items sitting in Recently Deleted.
                    reassignAll(info.bucket.id, fallback.id, meetingIds)
                    db.bucketDao().deleteBucket(info.bucket)
                }
            }.onFailure {
                _errorMessage.emit(it.message ?: "Could not delete bucket")
            }
            _pendingBucketDeletion.value = null
        }
    }
}
