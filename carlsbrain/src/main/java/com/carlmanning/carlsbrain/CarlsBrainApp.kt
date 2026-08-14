package com.carlmanning.carlsbrain

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Intent
import androidx.work.Configuration
import androidx.work.Constraints
import okhttp3.OkHttpClient
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.worker.BusyMode
import com.carlmanning.carlsbrain.data.local.worker.DigestAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.DigestReceiver
import com.carlmanning.carlsbrain.data.local.worker.NotificationScheduler
import com.carlmanning.carlsbrain.data.local.worker.ReminderReceiver
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.FirefliesSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.MidnightCleanupWorker
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.util.initDateFormatting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CarlsBrainApp : Application(), Configuration.Provider {

    companion object {
        const val MEETINGS_CHANNEL_ID = "meeting_ready"

        lateinit var httpClient: OkHttpClient
            private set
        lateinit var userPreferences: UserPreferences
            private set
        lateinit var claudeClient: ClaudeClient
            private set

        /**
         * Application-lifetime coroutine scope for fire-and-forget work that must
         * survive a ViewModel being cleared (e.g. Claude auto-tagging a capture
         * after Quick Capture navigates away).
         */
        val appScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        // Bounded by default: an unbounded client meant a single bad-connectivity call
        // (e.g. the calendar fetch behind the Settings digest preview) could hang for
        // minutes. Callers that legitimately need longer — Whisper transcription, Drive
        // media uploads — derive their own client via httpClient.newBuilder().
        httpClient = OkHttpClient.Builder()
            .callTimeout(60, TimeUnit.SECONDS)
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        userPreferences = UserPreferences(this)
        claudeClient = ClaudeClient(userPreferences)
        // Sync displayed times with the device clock setting before any UI renders, so times
        // are shown in the same format the pickers accept them in.
        initDateFormatting(this)
        createNotificationChannels()
        scheduleMidnightCleanup()
        scheduleDriveSync()
        scheduleDigestFromPrefs()
        scheduleSmartNotificationsFromPrefs()
        scheduleWeeklyReviewFromPrefs()
        scheduleFirefliesSync()
        startVoiceCaptureServiceIfEnabled()
        restoreBusyModeIfActive()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                DigestReceiver.CHANNEL_ID,
                "Morning Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Daily morning briefing from Carl's Brain" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                ReminderReceiver.CHANNEL_ID,
                "To-Do Reminders",
                NotificationManager.IMPORTANCE_HIGH
            ).apply { description = "Alerts when a to-do reminder fires" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                MEETINGS_CHANNEL_ID,
                "Meeting Ready",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Notifies when meeting analysis is complete" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                VoiceCaptureService.CHANNEL_ID,
                "Brain Listener",
                NotificationManager.IMPORTANCE_LOW
            ).apply { description = "Persistent notification for Hey Brain voice capture" }
        )

        nm.createNotificationChannel(
            NotificationChannel(
                VoiceCaptureService.CONFIRM_CHANNEL_ID,
                "Voice Captures",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Confirmation when a voice capture is saved" }
        )

        // High-importance channel for the wake-word trigger — required for
        // setFullScreenIntent to fire the VoiceCaptureActivity overlay.
        nm.createNotificationChannel(
            NotificationChannel(
                VoiceCaptureService.TRIGGER_CHANNEL_ID,
                "Hey Brain Trigger",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Pops up the voice overlay when Hey Brain is detected"
                setShowBadge(false)
            }
        )

        // Four smart notification slots
        nm.createNotificationChannel(
            NotificationChannel(
                SmartNotificationWorker.Slot.MORNING.channelId,
                "Morning Digest",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Morning briefing: top priorities and today's calendar" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                SmartNotificationWorker.Slot.MIDDAY.channelId,
                "Midday Check-in",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Quick midday check on urgent tasks" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                SmartNotificationWorker.Slot.AFTERNOON.channelId,
                "Afternoon Check-in",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Afternoon nudge with inline done actions" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                SmartNotificationWorker.Slot.EVENING.channelId,
                "Evening Prep",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Evening wrap-up and tomorrow prep" }
        )
        nm.createNotificationChannel(
            NotificationChannel(
                WeeklyReviewWorker.CHANNEL_ID,
                "Weekly Review",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply { description = "Friday reminder to do your weekly review" }
        )

        // Low importance on purpose: the busy-mode banner is a status indicator that sits
        // silently in the shade, not an alert.
        nm.createNotificationChannel(
            NotificationChannel(
                BusyMode.CHANNEL_ID,
                "Busy Mode",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Shows while busy mode is pausing notifications"
                setShowBadge(false)
            }
        )
    }

    /**
     * Re-establishes the busy-mode banner and its expiry alarm after a reboot or a cold start,
     * or cleans up if the 12-hour ceiling passed while the app was not running. Application
     * .onCreate() also runs before BootReceiver's broadcast is delivered, so this covers reboot
     * without BootReceiver needing to know about busy mode.
     */
    private fun restoreBusyModeIfActive() {
        CoroutineScope(Dispatchers.IO).launch {
            BusyMode.restoreIfActive(this@CarlsBrainApp)
        }
    }

    private fun scheduleMidnightCleanup() {
        val now = System.currentTimeMillis()
        val nextMidnight = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = nextMidnight.timeInMillis - now

        val request = PeriodicWorkRequestBuilder<MidnightCleanupWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "midnight_cleanup",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleDriveSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()

        val request = PeriodicWorkRequestBuilder<DriveSyncWorker>(15, TimeUnit.MINUTES)
            .setConstraints(constraints)
            .build()

        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "drive_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun scheduleDigestFromPrefs() {
        CoroutineScope(Dispatchers.IO).launch {
            if (!userPreferences.digestEnabled.first()) {
                DigestAlarmScheduler.cancel(this@CarlsBrainApp)
                return@launch
            }
            val hour = userPreferences.morningDigestHour.first()
            val minute = userPreferences.morningDigestMinute.first()
            DigestAlarmScheduler.schedule(this@CarlsBrainApp, hour, minute)
        }
    }

    private fun scheduleWeeklyReviewFromPrefs() {
        CoroutineScope(Dispatchers.IO).launch {
            if (userPreferences.weeklyReviewEnabled.first()) {
                NotificationScheduler.scheduleWeeklyReview(this@CarlsBrainApp)
            } else {
                WorkManager.getInstance(this@CarlsBrainApp)
                    .cancelUniqueWork(WeeklyReviewWorker.WORK_NAME)
            }
        }
    }

    private fun scheduleSmartNotificationsFromPrefs() {
        CoroutineScope(Dispatchers.IO).launch {
            // One-time migration: Carl was getting two morning notifications (the 06:30
            // digest and this 07:00 slot). Flipping the default alone would not help
            // anyone whose DataStore already holds an explicit `true`, so force it off
            // once and cancel the armed alarm. He can re-enable it from Settings.
            if (!userPreferences.morningSlotMigratedV1.first()) {
                userPreferences.applyMorningSlotMigrationV1()
                SmartNotificationAlarmScheduler.cancelSlot(
                    this@CarlsBrainApp, SmartNotificationWorker.Slot.MORNING
                )
            }

            SmartNotificationAlarmScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.MORNING,
                userPreferences.notifMorningEnabled.first(),
                userPreferences.notifMorningHour.first(), userPreferences.notifMorningMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.MIDDAY,
                userPreferences.notifMiddayEnabled.first(),
                userPreferences.notifMiddayHour.first(), userPreferences.notifMiddayMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.AFTERNOON,
                userPreferences.notifAfternoonEnabled.first(),
                userPreferences.notifAfternoonHour.first(), userPreferences.notifAfternoonMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.EVENING,
                userPreferences.notifEveningEnabled.first(),
                userPreferences.notifEveningHour.first(), userPreferences.notifEveningMinute.first()
            )
        }
    }

    private fun scheduleFirefliesSync() {
        val constraints = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
        val request = PeriodicWorkRequestBuilder<FirefliesSyncWorker>(6, TimeUnit.HOURS)
            .setConstraints(constraints)
            .build()
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            FirefliesSyncWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )
    }

    private fun startVoiceCaptureServiceIfEnabled() {
        CoroutineScope(Dispatchers.IO).launch {
            if (userPreferences.wakeWordEnabled.first()) {
                withContext(Dispatchers.Main) {
                    startForegroundService(Intent(this@CarlsBrainApp, VoiceCaptureService::class.java))
                }
            }
        }
    }
}
