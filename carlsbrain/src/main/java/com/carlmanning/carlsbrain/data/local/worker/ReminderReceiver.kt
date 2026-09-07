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
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.ErrorLog
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        val title = intent.getStringExtra(EXTRA_TODO_TITLE) ?: return
        if (todoId == -1L) return

        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // Vault check — must never show vault todo titles on the lock screen
        val pending = goAsync()
        CarlsBrainApp.appScope.launch {
            // runCatching around the whole body, for the reason spelled out in
            // ReminderActionReceiver: a throw here has no screen to surface on and kills the
            // process instead. A reminder that fails to post is a missed nudge; a reminder that
            // crashes the app is a bug report with nothing in it.
            runCatching { postIfAllowed(context, todoId, title) }
                .onFailure { ErrorLog.record("ReminderReceiver", it) }
            // Always released, whichever way the block above ended. Deliberately outside the
            // guarded call rather than inside it: an early return in there must not be able to
            // skip this, or the goAsync lease leaks and holds the process alive indefinitely.
            pending.finish()
        }
    }

    /**
     * The two gates a reminder has to pass, then the notification.
     *
     * A named function rather than an inline block so its early returns are ordinary local
     * returns — inside a `runCatching` lambda they would have been non-local and could have
     * skipped the caller's `pending.finish()`.
     */
    private suspend fun postIfAllowed(context: Context, todoId: Long, title: String) {
        // Master switch. Settings also cancels the pending alarms when this is
        // turned off; this guard catches any that were already in flight.
        val enabled = runCatching { CarlsBrainApp.userPreferences.remindersEnabled.first() }
            .getOrDefault(true)
        if (!enabled) return

        // Vault check — must never show vault todo titles on the lock screen.
        val db = AppDatabase.getInstance(context)
        val todo = db.todoDao().getTodoById(todoId)
        if (todo != null) {
            val bucket = db.bucketDao().getBucketById(todo.bucketId)
            if (bucket?.isVault == true) return
        }
        postNotification(context, todoId, title)
    }

    private fun postNotification(context: Context, todoId: Long, title: String) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val notifId = (todoId and 0x7FFFFFFF).toInt()

        val tapIntent = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val doneIntent = PendingIntent.getBroadcast(
            context, notifId + 0x01000000,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_DONE
                putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todoId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val snoozeIntent = PendingIntent.getBroadcast(
            context, notifId + 0x02000000,
            Intent(context, ReminderActionReceiver::class.java).apply {
                action = ReminderActionReceiver.ACTION_SNOOZE
                putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todoId)
                putExtra(ReminderActionReceiver.EXTRA_TODO_TITLE, title)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Reminder")
            .setContentText(title)
            .setContentIntent(tapIntent)
            .addAction(0, "Mark Done", doneIntent)
            .addAction(0, "Snooze 1h", snoozeIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .build()

        NotificationManagerCompat.from(context).notify(notifId, notification)
    }

    companion object {
        const val CHANNEL_ID = "reminders"
        const val EXTRA_TODO_ID = "todo_id"
        const val EXTRA_TODO_TITLE = "todo_title"
    }
}
