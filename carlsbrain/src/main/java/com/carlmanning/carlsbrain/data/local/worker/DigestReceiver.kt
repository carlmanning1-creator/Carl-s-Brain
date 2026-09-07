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
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.CarlsBrainApp
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * AlarmManager-based replacement for DigestNotificationWorker.
 * Fires the morning digest at the user-configured time and re-arms for the next day.
 */
class DigestReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val hour = intent.getIntExtra(EXTRA_HOUR, 6)
        val minute = intent.getIntExtra(EXTRA_MINUTE, 30)

        val pending = goAsync()
        // appScope, not a bare CoroutineScope(Dispatchers.IO). A raw scope has no exception
        // handler, so a throw here reaches the default handler and kills the process — from an
        // alarm, with no screen open, which is the least diagnosable place for it to happen.
        // This is the pattern already removed from Application.onCreate and BootReceiver.
        CarlsBrainApp.appScope.launch {
            try {
                // Carl can turn the digest off entirely — when he has, don't re-arm
                // either, or the alarm keeps waking the device for nothing. Settings
                // re-schedules it when he turns it back on. A failure reading the
                // preference is treated as "enabled" so the chain can never be lost.
                val enabled = runCatching { CarlsBrainApp.userPreferences.digestEnabled.first() }
                    .getOrDefault(true)
                if (!enabled) return@launch

                // Re-arm FIRST: the alarm chain must never depend on the digest succeeding.
                // A throw from postDigest (DB schema, app-init statics) or a process kill
                // mid-work would otherwise stop this alarm permanently.
                runCatching { DigestAlarmScheduler.schedule(context, hour, minute) }

                // Busy mode: skip only the POSTING, never the re-arm above — otherwise the
                // digest would never come back after busy mode ends. isSuppressing() fails
                // open (false) and self-heals an expired session, so a notification is never
                // lost to an error. Per-todo reminders (ReminderReceiver) are deliberately NOT
                // suppressed: those are alarms Carl set himself for a specific thing at a
                // specific time, which is different from the app volunteering a summary.
                if (BusyMode.isSuppressing(context)) return@launch

                // Contain any failure in the digest work itself.
                runCatching { postDigest(context) }
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * Builds the digest through [DigestGenerator] and posts it.
     *
     * This used to carry its own copy of the whole pipeline — Room query, calendar fetch,
     * prompt, Claude call, fallback — beside DigestGenerator, whose header calls itself the
     * single source of truth for digest text. The two had drifted: the generator honours
     * `notifAiEnabled` and this did not, so switching AI notifications off still billed for a
     * Claude call every single morning. The generator also has the honest timeout text; this
     * copy's fallback read an empty to-do list as knowledge.
     *
     * MORNING is the right slot: this is the 6:30 alarm, and the slot only selects the
     * generator's tone and to-do window.
     */
    private suspend fun postDigest(context: Context) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val briefingText = DigestGenerator
            .generateWithDataOrFallback(context, SmartNotificationWorker.Slot.MORNING)
            .text

        val tapIntent = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Good morning, Carl")
            .setContentText(briefingText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(briefingText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        const val CHANNEL_ID = "morning_digest"
        const val NOTIFICATION_ID = 1001
        const val ALARM_REQUEST_CODE = 4999
    }
}

object DigestAlarmScheduler {

    fun schedule(context: Context, hour: Int, minute: Int) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, DigestReceiver::class.java).apply {
            putExtra(DigestReceiver.EXTRA_HOUR, hour)
            putExtra(DigestReceiver.EXTRA_MINUTE, minute)
        }
        val pi = PendingIntent.getBroadcast(
            context, DigestReceiver.ALARM_REQUEST_CODE, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val now = System.currentTimeMillis()
        val triggerAt = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, DigestReceiver.ALARM_REQUEST_CODE,
            Intent(context, DigestReceiver::class.java),
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }
}
