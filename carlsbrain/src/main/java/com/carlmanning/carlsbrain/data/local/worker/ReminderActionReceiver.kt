package com.carlmanning.carlsbrain.data.local.worker

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class ReminderActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        if (todoId == -1L) return
        val notificationId = (todoId and 0x7FFFFFFF).toInt()

        NotificationManagerCompat.from(context).cancel(notificationId)

        when (intent.action) {
            ACTION_DONE -> {
                val pending = goAsync()
                CoroutineScope(Dispatchers.IO).launch {
                    try {
                        AppDatabase.getInstance(context).todoDao().setTodoDone(todoId, true)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_SNOOZE -> {
                val title = intent.getStringExtra(EXTRA_TODO_TITLE) ?: return
                val snoozeMs = System.currentTimeMillis() + SNOOZE_MS
                ReminderScheduler.schedule(context, todoId, title, snoozeMs)
            }
        }
    }

    companion object {
        const val ACTION_DONE = "com.carlmanning.carlsbrain.ACTION_REMINDER_DONE"
        const val ACTION_SNOOZE = "com.carlmanning.carlsbrain.ACTION_REMINDER_SNOOZE"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TODO_TITLE = "todo_title"
        private const val SNOOZE_MS = 60 * 60 * 1000L
    }
}
