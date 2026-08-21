package com.carlmanning.carlsbrain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.worker.AmbientBufferService
import com.carlmanning.carlsbrain.data.local.worker.DigestAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationAlarmScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        // Everything below runs off the main thread, so onReceive returns immediately — and
        // once it returns the process is an ordinary candidate for termination, at exactly the
        // moment a booting device is under most memory pressure. Reaped here, the per-to-do
        // reminders are simply gone: nothing outside this receiver ever rebuilds them, so they
        // stay dead until Carl notices a reminder never arrived. The lease keeps the process
        // alive until the work is done, as every other receiver in the app already does.
        val pending = goAsync()
        val job = CoroutineScope(Dispatchers.IO).launch {
            val prefs = CarlsBrainApp.userPreferences

            // Cancel any leftover WorkManager periodic jobs from the old implementation
            val wm = WorkManager.getInstance(context)
            wm.cancelUniqueWork("morning_digest")
            wm.cancelUniqueWork("smart_notif_morning")
            wm.cancelUniqueWork("smart_notif_midday")
            wm.cancelUniqueWork("smart_notif_afternoon")
            wm.cancelUniqueWork("smart_notif_evening")

            // Morning digest — AlarmManager exact alarm
            if (prefs.digestEnabled.first()) {
                val hour = prefs.morningDigestHour.first()
                val minute = prefs.morningDigestMinute.first()
                DigestAlarmScheduler.schedule(context, hour, minute)
            }

            // Four smart notification slots — AlarmManager exact alarms
            SmartNotificationAlarmScheduler.scheduleSlot(
                context, SmartNotificationWorker.Slot.MORNING,
                prefs.notifMorningEnabled.first(),
                prefs.notifMorningHour.first(), prefs.notifMorningMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                context, SmartNotificationWorker.Slot.MIDDAY,
                prefs.notifMiddayEnabled.first(),
                prefs.notifMiddayHour.first(), prefs.notifMiddayMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                context, SmartNotificationWorker.Slot.AFTERNOON,
                prefs.notifAfternoonEnabled.first(),
                prefs.notifAfternoonHour.first(), prefs.notifAfternoonMinute.first()
            )
            SmartNotificationAlarmScheduler.scheduleSlot(
                context, SmartNotificationWorker.Slot.EVENING,
                prefs.notifEveningEnabled.first(),
                prefs.notifEveningHour.first(), prefs.notifEveningMinute.first()
            )

            // Reschedule all active todo reminders (AlarmManager clears on reboot)
            if (prefs.remindersEnabled.first()) {
                val todos = AppDatabase.getInstance(context).todoDao().getActiveReminders()
                todos.forEach { todo ->
                    val reminderAt = todo.reminderAt ?: return@forEach
                    ReminderScheduler.schedule(context, todo.id, todo.title, reminderAt)
                }
            }

            // Restart Hey Brain wake word service if it was enabled
            if (prefs.wakeWordEnabled.first()) {
                context.startForegroundService(Intent(context, VoiceCaptureService::class.java))
            }

            // Restart the ambient buffer if it was switched on. Started after the wake word so
            // it sees the right microphone owner and does not open a second AudioRecord.
            if (prefs.ambientBufferEnabled.first()) {
                AmbientBufferService.send(context, AmbientBufferService.ACTION_START_BUFFER)
            }
        }
        // Released however the job ends, including on failure — a lease that leaks would keep
        // the process alive indefinitely.
        job.invokeOnCompletion { pending.finish() }
    }
}
