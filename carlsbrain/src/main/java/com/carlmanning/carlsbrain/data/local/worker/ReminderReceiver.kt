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
        // Notes use this same alarm. Without knowing which, the vault check below looked a note
        // up in the to-dos table, found nothing, and posted the title anyway.
        val isNote = intent.getBooleanExtra(EXTRA_IS_NOTE, false)

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
            runCatching { postIfAllowed(context, todoId, title, isNote) }
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
    private suspend fun postIfAllowed(
        context: Context,
        todoId: Long,
        title: String,
        isNote: Boolean
    ) {
        // Master switch. Settings also cancels the pending alarms when this is
        // turned off; this guard catches any that were already in flight.
        val enabled = runCatching { CarlsBrainApp.userPreferences.remindersEnabled.first() }
            .getOrDefault(true)
        if (!enabled) return

        // Vault check — must never show a vault item's title on the lock screen.
        //
        // Looked up in the table the alarm actually came from. Notes were checked against the
        // to-dos table, which by construction never matched, so every note reminder passed.
        // The row is also required to still exist: a reminder whose item has been deleted (or
        // whose type cannot be resolved) is not proven safe, so it is dropped rather than posted.
        val db = AppDatabase.getInstance(context)
        val bucketId = if (isNote) db.noteDao().getNoteById(todoId)?.bucketId
                       else db.todoDao().getTodoById(todoId)?.bucketId
        if (bucketId == null) return
        if (db.bucketDao().getBucketById(bucketId)?.isVault == true) return
        postNotification(context, todoId, title, isNote)
    }

    private fun postNotification(context: Context, todoId: Long, title: String, isNote: Boolean) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        // Offset for notes, so note 5 and to-do 5 are two notifications rather than one
        // replacing the other.
        val notifId = (((if (isNote) todoId + 1_000_000L else todoId)) and 0x7FFFFFFF).toInt()

        val tapIntent = PendingIntent.getActivity(
            context, notifId,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Only a to-do can be marked done. A note has no completion state, and the action
        // receiver would have taken the note's id straight to CompleteTodoUseCase — ticking off
        // whichever unrelated to-do happened to share that number.
        val doneIntent = if (isNote) null else PendingIntent.getBroadcast(
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
                putExtra(ReminderActionReceiver.EXTRA_IS_NOTE, isNote)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Reminder")
            .setContentText(title)
            .setContentIntent(tapIntent)
            .apply { doneIntent?.let { addAction(0, "Mark Done", it) } }
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

        /**
         * True when [EXTRA_TODO_ID] is a note id. Notes share this alarm; without this the
         * vault check ran against the wrong table and every note reminder passed it.
         */
        const val EXTRA_IS_NOTE = "is_note"
    }
}
