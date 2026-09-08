package com.carlmanning.carlsbrain.data.local.worker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

object ReminderScheduler {

    /**
     * Note reminders share this alarm with to-dos, offset so their request codes cannot collide
     * with a to-do of the same id.
     *
     * It lives here rather than in the note editor because the *receiver* has to understand it.
     * It did not: the offset key was looked up in the to-dos table, found nothing, and a null
     * row was read as "nothing to check" — so a reminder on a note in a vault bucket put its
     * title on the lock screen, outside both the biometric gate and the vault. The type now
     * travels with the alarm instead of being encoded in an id one side could not decode.
     */
    private const val NOTE_ID_OFFSET = 1_000_000L

    /**
     * A stable 31-bit request code for one entity's alarm.
     *
     * Hashed rather than truncated. Truncation drops the high bits, so two ids an exact multiple
     * of 2^31 apart — about 25 days in epoch milliseconds, and `IdFloor` seeds these sequences
     * from the clock — collide and one alarm silently replaces the other. A remote coincidence,
     * noted only because the consequence is a reminder that never fires and nothing anywhere
     * saying so. Mixing both halves makes it as unlikely as the space allows.
     */
    private fun requestCode(entityId: Long, isNote: Boolean): Int {
        val key = if (isNote) entityId + NOTE_ID_OFFSET else entityId
        return ((key xor (key ushr 32)) and 0x7FFFFFFF).toInt()
    }

    fun schedule(
        context: Context,
        entityId: Long,
        title: String,
        reminderAt: Long,
        isNote: Boolean = false
    ) {
        if (reminderAt <= System.currentTimeMillis()) return
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = buildPendingIntent(context, entityId, title, isNote)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, reminderAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, reminderAt, pi)
        }
    }

    fun cancel(context: Context, entityId: Long, isNote: Boolean = false) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val intent = Intent(context, ReminderReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            context, requestCode(entityId, isNote), intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }

    private fun buildPendingIntent(
        context: Context,
        entityId: Long,
        title: String,
        isNote: Boolean
    ): PendingIntent {
        val intent = Intent(context, ReminderReceiver::class.java).apply {
            putExtra(ReminderReceiver.EXTRA_TODO_ID, entityId)
            putExtra(ReminderReceiver.EXTRA_TODO_TITLE, title)
            putExtra(ReminderReceiver.EXTRA_IS_NOTE, isNote)
        }
        return PendingIntent.getBroadcast(
            context, requestCode(entityId, isNote), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }
}
