package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules (or cancels) the four smart notification slots and the weekly review.
 * Each slot is a separate unique periodic work request so they can be individually
 * enabled/disabled and rescheduled when the user changes the time in Settings.
 */
object NotificationScheduler {

    private fun workName(slot: SmartNotificationWorker.Slot) = "smart_notif_${slot.name.lowercase()}"

    /**
     * Schedule a single slot. If [enabled] is false, the existing work is cancelled.
     */
    fun scheduleSlot(
        context: Context,
        slot: SmartNotificationWorker.Slot,
        enabled: Boolean,
        hour: Int,
        minute: Int,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.REPLACE
    ) {
        val wm = WorkManager.getInstance(context)
        val name = workName(slot)

        if (!enabled) {
            wm.cancelUniqueWork(name)
            return
        }

        val now = System.currentTimeMillis()
        val nextTrigger = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        val initialDelay = nextTrigger.timeInMillis - now

        val inputData = Data.Builder()
            .putString(SmartNotificationWorker.KEY_SLOT, slot.name)
            .build()

        val request = PeriodicWorkRequestBuilder<SmartNotificationWorker>(1, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .setInputData(inputData)
            .build()

        wm.enqueueUniquePeriodicWork(name, policy, request)
    }

    /**
     * Cancel a single slot work.
     */
    fun cancelSlot(context: Context, slot: SmartNotificationWorker.Slot) {
        WorkManager.getInstance(context).cancelUniqueWork(workName(slot))
    }

    /**
     * Schedule the weekly review worker (fires every 7 days, targeting Friday 17:00).
     */
    fun scheduleWeeklyReview(
        context: Context,
        policy: ExistingPeriodicWorkPolicy = ExistingPeriodicWorkPolicy.KEEP
    ) {
        val now = System.currentTimeMillis()
        // Find next Friday at 17:00
        val nextFriday = Calendar.getInstance().apply {
            while (get(Calendar.DAY_OF_WEEK) != Calendar.FRIDAY) add(Calendar.DAY_OF_YEAR, 1)
            set(Calendar.HOUR_OF_DAY, 17)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 7)
        }
        val initialDelay = nextFriday.timeInMillis - now

        val request = PeriodicWorkRequestBuilder<WeeklyReviewWorker>(7, TimeUnit.DAYS)
            .setInitialDelay(initialDelay, TimeUnit.MILLISECONDS)
            .build()

        WorkManager.getInstance(context)
            .enqueueUniquePeriodicWork(WeeklyReviewWorker.WORK_NAME, policy, request)
    }
}
