package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.ErrorLog
import kotlinx.coroutines.flow.first

/**
 * Rebuilds every to-do and note reminder alarm after a reboot.
 *
 * Split out of `BootReceiver` because of the lease. A broadcast receiver's `goAsync` window is
 * roughly ten seconds, and the receiver was holding it across DataStore reads, a Room query, an
 * unbounded loop over every active reminder and two service starts — on a device that has just
 * booted and is at its busiest. Past the window Android may kill the process mid-rearm, and the
 * reminders after the failure point are simply gone: nothing else rebuilds them, so Carl finds
 * out when one never arrives.
 *
 * WorkManager has no such window and will retry, so the unbounded half moves here and the
 * receiver keeps only the short, ordered work that must happen immediately.
 */
class RearmRemindersWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val prefs = CarlsBrainApp.userPreferences
        // The master switch, checked here as well as by the receiver: this runs later, and Carl
        // may have turned reminders off in between.
        val enabled = runCatching { prefs.remindersEnabled.first() }.getOrDefault(true)
        if (!enabled) return Result.success()

        val db = AppDatabase.getInstance(applicationContext)

        // Guarded per reminder, exactly as the receiver did it: one bad row must not cost Carl
        // the rest of them.
        runCatching { db.todoDao().getActiveReminders() }
            .onFailure { ErrorLog.record("RearmRemindersWorker/todos", it) }
            .getOrDefault(emptyList())
            .forEach { todo ->
                val at = todo.reminderAt ?: return@forEach
                runCatching { ReminderScheduler.schedule(applicationContext, todo.id, todo.title, at) }
                    .onFailure { ErrorLog.record("RearmRemindersWorker/todo ${todo.id}", it) }
            }

        runCatching { db.noteDao().getActiveReminders() }
            .onFailure { ErrorLog.record("RearmRemindersWorker/notes", it) }
            .getOrDefault(emptyList())
            .forEach { note ->
                val at = note.reminderAt ?: return@forEach
                runCatching {
                    ReminderScheduler.schedule(applicationContext, note.id, note.title, at, isNote = true)
                }.onFailure { ErrorLog.record("RearmRemindersWorker/note ${note.id}", it) }
            }

        return Result.success()
    }

    companion object {
        const val WORK_NAME = "rearm_reminders"

        /**
         * Queues a rebuild. REPLACE rather than KEEP: a second boot's rearm should supersede an
         * earlier one still waiting, not be dropped in favour of it.
         */
        fun enqueue(context: Context) {
            WorkManager.getInstance(context).enqueueUniqueWork(
                WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<RearmRemindersWorker>().build()
            )
        }
    }
}
