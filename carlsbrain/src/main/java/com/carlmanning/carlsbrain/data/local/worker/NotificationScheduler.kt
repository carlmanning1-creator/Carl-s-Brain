package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * Schedules the weekly review.
 *
 * The four smart-notification slots are NOT scheduled here — see
 * [SmartNotificationAlarmScheduler]. The weekly review is genuinely periodic and has no
 * to-the-minute requirement, so WorkManager remains the right home for it.
 */
object NotificationScheduler {

    // scheduleSlot and cancelSlot lived here and were dead: the four slots moved from
    // WorkManager to AlarmManager because a check-in has to land on a minute, and WorkManager
    // does not promise that. They sat beside SmartNotificationAlarmScheduler under near-identical
    // names, on a different mechanism — so a change made to the wrong one would have looked
    // applied and done nothing. SmartNotificationAlarmScheduler is the live one.

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
