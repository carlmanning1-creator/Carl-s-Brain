package com.carlmanning.carlsbrain

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Configuration
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.worker.DigestNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.MidnightCleanupWorker
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class CarlsBrainApp : Application(), Configuration.Provider {

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.INFO)
            .build()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        scheduleMidnightCleanup()
        scheduleDriveSync()
        scheduleDigestFromPrefs()
    }

    private fun createNotificationChannels() {
        val digestChannel = NotificationChannel(
            DigestNotificationWorker.CHANNEL_ID,
            "Morning Digest",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply { description = "Daily morning briefing from Carl's Brain" }

        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(digestChannel)
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
            val prefs = UserPreferences(this@CarlsBrainApp)
            val hour = prefs.morningDigestHour.first()
            val minute = prefs.morningDigestMinute.first()
            DigestScheduler.schedule(this@CarlsBrainApp, hour, minute, ExistingPeriodicWorkPolicy.KEEP)
        }
    }
}
