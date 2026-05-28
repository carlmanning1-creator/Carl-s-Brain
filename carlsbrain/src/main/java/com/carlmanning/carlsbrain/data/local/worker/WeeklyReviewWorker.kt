package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R

/**
 * Fires every Friday at 17:00. Shows a "Weekly Review" notification that, when tapped,
 * opens MainActivity with [EXTRA_REVIEW_PROMPT] set — ChatScreen detects this and
 * auto-sends the weekly review prompt.
 */
class WeeklyReviewWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val tapIntent = PendingIntent.getActivity(
            applicationContext,
            NOTIFICATION_ID,
            Intent(applicationContext, MainActivity::class.java).apply {
                action = ACTION_OPEN_WEEKLY_REVIEW
                putExtra(EXTRA_REVIEW_PROMPT, WEEKLY_REVIEW_PROMPT)
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Time for your weekly review")
            .setContentText("Tap to open a chat with your weekly summary")
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(applicationContext).notify(NOTIFICATION_ID, notification)

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "weekly_review"
        const val CHANNEL_ID = "weekly_review"
        const val ACTION_OPEN_WEEKLY_REVIEW = "com.carlmanning.carlsbrain.ACTION_OPEN_WEEKLY_REVIEW"
        const val EXTRA_REVIEW_PROMPT = "extra_review_prompt"
        const val WEEKLY_REVIEW_PROMPT =
            "Please give me a weekly review: what did I accomplish this week, what's outstanding, what should I focus on next week?"
        private const val NOTIFICATION_ID = 1005
    }
}
