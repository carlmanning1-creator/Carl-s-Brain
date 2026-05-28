package com.carlmanning.carlsbrain

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.ExistingPeriodicWorkPolicy
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.worker.DigestScheduler
import com.carlmanning.carlsbrain.data.local.worker.NotificationScheduler
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED) return

        CoroutineScope(Dispatchers.IO).launch {
            val prefs = CarlsBrainApp.userPreferences
            val hour = prefs.morningDigestHour.first()
            val minute = prefs.morningDigestMinute.first()
            DigestScheduler.schedule(context, hour, minute, ExistingPeriodicWorkPolicy.REPLACE)

            // Reschedule four smart notification slots
            val morningEnabled = prefs.notifMorningEnabled.first()
            NotificationScheduler.scheduleSlot(context, SmartNotificationWorker.Slot.MORNING, morningEnabled, prefs.notifMorningHour.first(), prefs.notifMorningMinute.first(), ExistingPeriodicWorkPolicy.REPLACE)
            val middayEnabled = prefs.notifMiddayEnabled.first()
            NotificationScheduler.scheduleSlot(context, SmartNotificationWorker.Slot.MIDDAY, middayEnabled, prefs.notifMiddayHour.first(), prefs.notifMiddayMinute.first(), ExistingPeriodicWorkPolicy.REPLACE)
            val afternoonEnabled = prefs.notifAfternoonEnabled.first()
            NotificationScheduler.scheduleSlot(context, SmartNotificationWorker.Slot.AFTERNOON, afternoonEnabled, prefs.notifAfternoonHour.first(), prefs.notifAfternoonMinute.first(), ExistingPeriodicWorkPolicy.REPLACE)
            val eveningEnabled = prefs.notifEveningEnabled.first()
            NotificationScheduler.scheduleSlot(context, SmartNotificationWorker.Slot.EVENING, eveningEnabled, prefs.notifEveningHour.first(), prefs.notifEveningMinute.first(), ExistingPeriodicWorkPolicy.REPLACE)
            NotificationScheduler.scheduleWeeklyReview(context, ExistingPeriodicWorkPolicy.REPLACE)

            // Reschedule all active todo reminders (AlarmManager clears on reboot)
            val todos = AppDatabase.getInstance(context).todoDao().getActiveReminders()
            todos.forEach { todo ->
                val reminderAt = todo.reminderAt ?: return@forEach
                ReminderScheduler.schedule(context, todo.id, todo.title, reminderAt)
            }

            // Restart Hey Brain wake word service if it was enabled
            if (prefs.wakeWordEnabled.first()) {
                context.startForegroundService(
                    Intent(context, VoiceCaptureService::class.java)
                )
            }
        }
    }
}
