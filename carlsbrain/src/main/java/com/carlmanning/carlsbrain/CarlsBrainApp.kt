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
import com.carlmanning.carlsbrain.data.local.worker.DigestNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.local.worker.NotificationScheduler
import com.carlmanning.carlsbrain.data.local.worker.ReminderReceiver
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.MidnightCleanupWorker
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        httpClient = OkHttpClient()
        userPreferences = UserPreferences(this)
        claudeClient = ClaudeClient(userPreferences)
        createNotificationChannels()
        scheduleMidnightCleanup()
        scheduleDriveSync()
        scheduleDigestFromPrefs()
        scheduleSmartNotificationsFromPrefs()
        NotificationScheduler.scheduleWeeklyReview(this)
        startVoiceCaptureServiceIfEnabled()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)

        nm.createNotificationChannel(
            NotificationChannel(
                DigestNotificationWorker.CHANNEL_ID,
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
            val hour = userPreferences.morningDigestHour.first()
            val minute = userPreferences.morningDigestMinute.first()
            DigestScheduler.schedule(this@CarlsBrainApp, hour, minute, ExistingPeriodicWorkPolicy.KEEP)
        }
    }

    private fun scheduleSmartNotificationsFromPrefs() {
        CoroutineScope(Dispatchers.IO).launch {
            val morningEnabled = userPreferences.notifMorningEnabled.first()
            val morningHour = userPreferences.notifMorningHour.first()
            val morningMinute = userPreferences.notifMorningMinute.first()
            NotificationScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.MORNING,
                morningEnabled, morningHour, morningMinute, ExistingPeriodicWorkPolicy.KEEP
            )

            val middayEnabled = userPreferences.notifMiddayEnabled.first()
            val middayHour = userPreferences.notifMiddayHour.first()
            val middayMinute = userPreferences.notifMiddayMinute.first()
            NotificationScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.MIDDAY,
                middayEnabled, middayHour, middayMinute, ExistingPeriodicWorkPolicy.KEEP
            )

            val afternoonEnabled = userPreferences.notifAfternoonEnabled.first()
            val afternoonHour = userPreferences.notifAfternoonHour.first()
            val afternoonMinute = userPreferences.notifAfternoonMinute.first()
            NotificationScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.AFTERNOON,
                afternoonEnabled, afternoonHour, afternoonMinute, ExistingPeriodicWorkPolicy.KEEP
            )

            val eveningEnabled = userPreferences.notifEveningEnabled.first()
            val eveningHour = userPreferences.notifEveningHour.first()
            val eveningMinute = userPreferences.notifEveningMinute.first()
            NotificationScheduler.scheduleSlot(
                this@CarlsBrainApp, SmartNotificationWorker.Slot.EVENING,
                eveningEnabled, eveningHour, eveningMinute, ExistingPeriodicWorkPolicy.KEEP
            )
        }
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
