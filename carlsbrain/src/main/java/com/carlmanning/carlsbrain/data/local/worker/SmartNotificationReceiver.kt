package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for smart notification slots. Uses AlarmManager.setExactAndAllowWhileIdle()
 * so notifications fire at the correct time even through Doze mode. On each fire the receiver
 * posts the notification and re-arms the alarm for the same time tomorrow.
 */
class SmartNotificationReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val slotName = intent.getStringExtra(EXTRA_SLOT) ?: return
        val slot = runCatching { SmartNotificationWorker.Slot.valueOf(slotName) }.getOrNull() ?: return

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Re-arm FIRST: the alarm chain must never depend on the digest succeeding.
                // If postNotification throws (DB schema, app-init statics) or the process is
                // killed mid-work, tomorrow's alarm is already armed.
                runCatching {
                    val hour = intent.getIntExtra(EXTRA_HOUR, slot.defaultHour)
                    val minute = intent.getIntExtra(EXTRA_MINUTE, slot.defaultMinute)
                    SmartNotificationAlarmScheduler.scheduleSlot(context, slot, enabled = true, hour = hour, minute = minute)
                }

                // Busy mode: skip only the POSTING, never the re-arm above — otherwise these
                // slots would never come back after busy mode ends. isSuppressing() fails open
                // (false) and self-heals an expired session, so a notification is never lost to
                // an error. Per-todo reminders (ReminderReceiver) are deliberately NOT
                // suppressed: those are alarms Carl set himself for a specific thing at a
                // specific time, which is different from the app volunteering a summary.
                if (BusyMode.isSuppressing(context)) return@launch

                // Contain any failure in the digest work itself.
                runCatching { postNotification(context, slot) }
            } finally {
                pending.finish()
            }
        }
    }

    private suspend fun postNotification(context: Context, slot: SmartNotificationWorker.Slot) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // Single source of truth for digest text + the (vault-safe) todos it was built from.
        // Bounded so the whole pipeline fits inside the goAsync() window; on overrun this
        // yields the non-AI fallback digest instead of nothing.
        val digest = DigestGenerator.generateWithDataOrFallback(context, slot)
        val notificationText = digest.text
        val priorityTodos = digest.todos

        val tapIntent = PendingIntent.getActivity(
            context, slot.notificationId,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(context, slot.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(slot.title)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        if (slot == SmartNotificationWorker.Slot.AFTERNOON) {
            priorityTodos.filter { it.priority == 0 }.take(2).forEachIndexed { index, todo ->
                val doneIntent = Intent(context, ReminderActionReceiver::class.java).apply {
                    action = ReminderActionReceiver.ACTION_DONE
                    putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderActionReceiver.EXTRA_TODO_TITLE, todo.title)
                }
                val donePendingIntent = PendingIntent.getBroadcast(
                    context, AFTERNOON_ACTION_BASE_ID + index, doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val shortTitle = if (todo.title.length > 24) todo.title.take(21) + "…" else todo.title
                builder.addAction(0, "Done: $shortTitle", donePendingIntent)
            }
        }

        NotificationManagerCompat.from(context).notify(slot.notificationId, builder.build())
    }

    companion object {
        const val EXTRA_SLOT = "slot"
        const val EXTRA_HOUR = "hour"
        const val EXTRA_MINUTE = "minute"
        private const val AFTERNOON_ACTION_BASE_ID = 3000
    }
}

// Default times for each slot (used as fallback on re-arm)
private val SmartNotificationWorker.Slot.defaultHour: Int get() = when (this) {
    SmartNotificationWorker.Slot.MORNING -> 7
    SmartNotificationWorker.Slot.MIDDAY -> 12
    SmartNotificationWorker.Slot.AFTERNOON -> 15
    SmartNotificationWorker.Slot.EVENING -> 20
}
private val SmartNotificationWorker.Slot.defaultMinute: Int get() = 0
