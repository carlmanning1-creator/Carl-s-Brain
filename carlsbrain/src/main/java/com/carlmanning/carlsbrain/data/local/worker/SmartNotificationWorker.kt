package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R

/**
 * Worker that fires for a specific notification slot (MORNING/MIDDAY/AFTERNOON/EVENING).
 * The slot is passed as an input data string via key [KEY_SLOT].
 *
 * For the AFTERNOON slot only, inline "Done" action buttons are added for the top 2 urgent todos.
 * Vault bucket todos are never included in any notification.
 */
class SmartNotificationWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        if (ActivityCompat.checkSelfPermission(
                applicationContext,
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return Result.success()

        val slotName = inputData.getString(KEY_SLOT) ?: Slot.MORNING.name
        val slot = runCatching { Slot.valueOf(slotName) }.getOrDefault(Slot.MORNING)

        val digest = DigestGenerator.generateWithData(applicationContext, slot)
        val notificationText = digest.text
        val priorityTodos = digest.todos

        val tapIntent = PendingIntent.getActivity(
            applicationContext,
            slot.notificationId,
            Intent(applicationContext, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val builder = NotificationCompat.Builder(applicationContext, slot.channelId)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(slot.title)
            .setContentText(notificationText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notificationText))
            .setContentIntent(tapIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)

        // AFTERNOON slot: inline "Done" action buttons for top 2 urgent todos
        if (slot == Slot.AFTERNOON) {
            priorityTodos.filter { it.priority == 0 }.take(2).forEachIndexed { index, todo ->
                val doneIntent = Intent(applicationContext, ReminderActionReceiver::class.java).apply {
                    action = ReminderActionReceiver.ACTION_DONE
                    putExtra(ReminderActionReceiver.EXTRA_TODO_ID, todo.id)
                    putExtra(ReminderActionReceiver.EXTRA_TODO_TITLE, todo.title)
                }
                val donePendingIntent = PendingIntent.getBroadcast(
                    applicationContext,
                    AFTERNOON_ACTION_BASE_ID + index,
                    doneIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
                val shortTitle = if (todo.title.length > 24) todo.title.take(21) + "…" else todo.title
                builder.addAction(0, "Done: $shortTitle", donePendingIntent)
            }
        }

        NotificationManagerCompat.from(applicationContext)
            .notify(slot.notificationId, builder.build())

        return Result.success()
    }

    enum class Slot(
        val channelId: String,
        val title: String,
        val notificationId: Int
    ) {
        MORNING("smart_morning", "Good morning, Carl", 2001),
        MIDDAY("smart_midday", "Midday check-in", 2002),
        AFTERNOON("smart_afternoon", "Afternoon check-in", 2003),
        EVENING("smart_evening", "Evening prep", 2004)
    }

    companion object {
        const val KEY_SLOT = "slot"
        private const val AFTERNOON_ACTION_BASE_ID = 3000
    }
}
