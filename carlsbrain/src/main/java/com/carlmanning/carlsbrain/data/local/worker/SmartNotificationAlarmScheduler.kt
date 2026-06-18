package com.carlmanning.carlsbrain.data.local.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

/**
 * Schedules smart notification slots using AlarmManager.setExactAndAllowWhileIdle().
 * This replaces the WorkManager periodic approach which drifted over time due to
 * WorkManager's flex window and Doze deferral behaviour.
 *
 * Each slot gets its own PendingIntent keyed by slot ordinal. On each alarm fire,
 * SmartNotificationReceiver re-arms the alarm for the same time the next day.
 */
object SmartNotificationAlarmScheduler {

    fun scheduleSlot(
        context: Context,
        slot: SmartNotificationWorker.Slot,
        enabled: Boolean,
        hour: Int,
        minute: Int
    ) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = buildPendingIntent(context, slot, hour, minute)

        if (!enabled) {
            alarmManager.cancel(pi)
            pi.cancel()
            return
        }

        val triggerAt = nextTriggerMs(hour, minute)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    fun cancelSlot(context: Context, slot: SmartNotificationWorker.Slot) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = buildPendingIntent(context, slot, 0, 0,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun nextTriggerMs(hour: Int, minute: Int): Long {
        val now = System.currentTimeMillis()
        return Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }.timeInMillis
    }

    private fun buildPendingIntent(
        context: Context,
        slot: SmartNotificationWorker.Slot,
        hour: Int,
        minute: Int,
        flags: Int = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    ): PendingIntent? {
        val intent = Intent(context, SmartNotificationReceiver::class.java).apply {
            putExtra(SmartNotificationReceiver.EXTRA_SLOT, slot.name)
            putExtra(SmartNotificationReceiver.EXTRA_HOUR, hour)
            putExtra(SmartNotificationReceiver.EXTRA_MINUTE, minute)
        }
        return PendingIntent.getBroadcast(context, ALARM_REQUEST_BASE + slot.ordinal, intent, flags)
    }

    private const val ALARM_REQUEST_BASE = 5000
}
