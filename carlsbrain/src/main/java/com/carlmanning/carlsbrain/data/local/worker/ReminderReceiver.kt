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
import com.carlmanning.carlsbrain.data.local.AppDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
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
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val db = AppDatabase.getInstance(context)
                val todo = db.todoDao().getTodoById(todoId)
                if (todo != null) {
                    val bucket = db.bucketDao().getBucketById(todo.bucketId)
                    if (bucket?.isVault == true) return@launch
                }
                postNotification(context, todoId, title)
            } finally {
                pending.finish()
            }
        }
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
