package com.carlmanning.carlsbrain.data.local.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.domain.usecase.CompleteTodoUseCase
import kotlinx.coroutines.launch

class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (todoId == -1L) return
        // Notes share this alarm and this receiver. Their ids come from a different table, so
        // the notification id has to be offset the same way ReminderReceiver offsets it — and
        // Done must never reach here for a note, or "Mark Done" on note 5 would tick to-do 5
        // off. ReminderReceiver does not offer the action on a note reminder; this is the
        // second half of that guarantee.
        val isNote = intent.getBooleanExtra(EXTRA_IS_NOTE, false)
        val notificationId = (((if (isNote) todoId + 1_000_000L else todoId)) and 0x7FFFFFFF).toInt()

        NotificationManagerCompat.from(context).cancel(notificationId)

        when (intent.action) {
            ACTION_DONE -> {
                if (isNote) return
                val pending = goAsync()
                CarlsBrainApp.appScope.launch {
                    // Wrapped, not merely finally'd. This ran bare on a scope with no exception
                    // handler, so a throw from the database or the alarm manager reached the
                    // default handler and killed the process — from a notification tap, with no
                    // screen open, so the only symptom was the app dying under Carl's finger.
                    // The other receivers in this package all guard their work; these two did not.
                    runCatching {
                        // Same rule as every other completion path: recurrence is handled
                        // in the use case, so ticking Done on the notification keeps the chain.
                        CompleteTodoUseCase(context).markDone(todoId, true)
                    }.onFailure { ErrorLog.record("ReminderActionReceiver/done", it) }
                    pending.finish()
                }
            }
            ACTION_SNOOZE -> {
                val title = intent.getStringExtra(EXTRA_TODO_TITLE) ?: return
                val snoozeMs = System.currentTimeMillis() + SNOOZE_MS
                runCatching { ReminderScheduler.schedule(context, todoId, title, snoozeMs, isNote) }
                    .onFailure { ErrorLog.record("ReminderActionReceiver/snooze", it) }
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.carlmanning.carlsbrain.ACTION_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.carlmanning.carlsbrain.ACTION_REMINDER_SNOOZE"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TODO_TITLE = "todo_title"
        /** True when the id belongs to a note rather than a to-do. */
        const val EXTRA_IS_NOTE = "is_note"
        private const val SNOOZE_MS = 60 * 60 * 1000L
    }
}
