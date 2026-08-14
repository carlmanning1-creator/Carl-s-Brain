package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Callout mode — Carl is on an SES job and the app must stop talking to him.
 *
 * While active, the app's *ambient* AI notifications (morning digest, the four smart slots,
 * the weekly review) are not posted. Per-todo reminders are deliberately left alone: those
 * are alarms Carl set himself for a specific thing at a specific time, which is a different
 * promise from the app volunteering a summary.
 *
 * The mode is always visible as an ongoing, non-dismissible notification with a one-tap
 * "End callout" action, and it auto-expires after [MAX_DURATION_MS] so it can never leave
 * the app silent for days.
 */
object CalloutMode {

    const val CHANNEL_ID = "callout_mode"
    const val NOTIFICATION_ID = 1006

    /** Tapped from the ongoing notification's "End callout" action. */
    const val ACTION_END_CALLOUT = "com.carlmanning.carlsbrain.ACTION_END_CALLOUT"

    /** Fired by the backstop alarm scheduled in [start]. */
    const val ACTION_CALLOUT_EXPIRED = "com.carlmanning.carlsbrain.ACTION_CALLOUT_EXPIRED"

    /** Hard ceiling on a callout. Past this the mode is treated as ended, alarm or no alarm. */
    const val MAX_DURATION_MS = 12L * 60L * 60L * 1000L

    private const val END_REQUEST_CODE = 4997
    private const val EXPIRY_REQUEST_CODE = 4998
    private const val CONTENT_REQUEST_CODE = 4996
    private const val CAPTURE_REQUEST_CODE = 4995

    /** Turns callout mode on, shows the ongoing notification and arms the expiry backstop. */
    suspend fun start(context: Context) {
        val prefs = CarlsBrainApp.userPreferences
        prefs.setCalloutActive(true)
        val startedAt = prefs.calloutStartedAt.first()
        showOngoingNotification(context, startedAt)
        scheduleExpiry(context, startedAt)
    }

    /** Turns callout mode off and clears everything it owns — no stale alarm, no notification. */
    suspend fun end(context: Context) {
        CarlsBrainApp.userPreferences.setCalloutActive(false)
        cancelExpiry(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /**
     * Whether ambient notifications should be withheld right now.
     *
     * This is the second of the two places the 12-hour expiry is enforced: even if the backstop
     * alarm was lost to a reboot or process death, an expired callout can never suppress —
     * it is cleaned up here and reported as inactive.
     *
     * Fails open: any error reading the preference means "not on a callout", so a notification
     * is never lost to an unexpected failure.
     */
    suspend fun isSuppressing(context: Context): Boolean = runCatching {
        val prefs = CarlsBrainApp.userPreferences
        if (!prefs.calloutActive.first()) return@runCatching false
        val startedAt = prefs.calloutStartedAt.first()
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) {
            end(context)
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    /**
     * Re-establishes the ongoing notification and the expiry alarm after a reboot or a cold
     * start, or cleans up if the callout has already expired. Safe to call on every launch.
     */
    suspend fun restoreIfActive(context: Context) {
        runCatching {
            val prefs = CarlsBrainApp.userPreferences
            if (!prefs.calloutActive.first()) return@runCatching
            val startedAt = prefs.calloutStartedAt.first()
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) {
                end(context)
                return@runCatching
            }
            showOngoingNotification(context, startedAt)
            scheduleExpiry(context, startedAt)
        }
    }

    private fun showOngoingNotification(context: Context, startedAt: Long) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val startedText = if (startedAt > 0L) {
            " · started " + android.text.format.DateFormat.getTimeFormat(context).format(Date(startedAt))
        } else ""

        val tapIntent = PendingIntent.getActivity(
            context, CONTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getBroadcast(
            context, END_REQUEST_CODE,
            Intent(context, CalloutReceiver::class.java).apply { action = ACTION_END_CALLOUT },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Capture straight from the notification — the one thing worth doing mid-job. Reuses
        // MainActivity's existing voice-capture deep link (ACTION_OPEN_CAPTURE_VOICE), the same
        // action the voice-capture notification uses, so there is one capture entry point, not two.
        val captureIntent = PendingIntent.getActivity(
            context, CAPTURE_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CAPTURE_VOICE
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Callout mode on")
            .setContentText("Notifications paused$startedText")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(if (startedAt > 0L) startedAt else System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Capture", captureIntent)
            .addAction(0, "End callout", endIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * First of the two expiry enforcement points: an exact alarm at start + 12h, matching the
     * AlarmManager approach the other schedulers in this package use.
     */
    private fun scheduleExpiry(context: Context, startedAt: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, EXPIRY_REQUEST_CODE,
            Intent(context, CalloutReceiver::class.java).apply { action = ACTION_CALLOUT_EXPIRED },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = (if (startedAt > 0L) startedAt else System.currentTimeMillis()) + MAX_DURATION_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelExpiry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, EXPIRY_REQUEST_CODE,
            Intent(context, CalloutReceiver::class.java).apply { action = ACTION_CALLOUT_EXPIRED },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }
}

/**
 * Handles the "End callout" notification action and the 12-hour expiry alarm.
 * Both end the callout, so they share one receiver.
 */
class CalloutReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            CalloutMode.ACTION_END_CALLOUT, CalloutMode.ACTION_CALLOUT_EXPIRED -> Unit
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { CalloutMode.end(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
