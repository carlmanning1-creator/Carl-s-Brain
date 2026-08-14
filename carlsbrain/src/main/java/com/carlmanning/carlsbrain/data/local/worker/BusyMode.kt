package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.R
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.util.formatSmartDate
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Date

/**
 * Busy mode — Carl is on an SES job and the app must stop talking to him.
 *
 * While active, the app's *ambient* AI notifications (morning digest, the four smart slots,
 * the weekly review) are not posted. Per-todo reminders are deliberately left alone: those
 * are alarms Carl set himself for a specific thing at a specific time, which is a different
 * promise from the app volunteering a summary.
 *
 * The mode is always visible as an ongoing, non-dismissible notification with a one-tap
 * "End busy mode" action, and it auto-expires after [MAX_DURATION_MS] so it can never leave
 * the app silent for days.
 */
object BusyMode {

    const val CHANNEL_ID = "busy_mode"
    const val NOTIFICATION_ID = 1006

    /** Tapped from the ongoing notification's "End busy mode" action. */
    const val ACTION_END_BUSY_MODE = "com.carlmanning.carlsbrain.ACTION_END_BUSY_MODE"

    /** Fired by the backstop alarm scheduled in [start]. */
    const val ACTION_BUSY_MODE_EXPIRED = "com.carlmanning.carlsbrain.ACTION_BUSY_MODE_EXPIRED"

    /** Hard ceiling on one session. Past this the mode is treated as ended, alarm or no alarm. */
    const val MAX_DURATION_MS = 12L * 60L * 60L * 1000L

    private const val END_REQUEST_CODE = 4997
    private const val EXPIRY_REQUEST_CODE = 4998
    private const val CONTENT_REQUEST_CODE = 4996
    private const val CAPTURE_REQUEST_CODE = 4995

    /** Turns busy mode on, shows the ongoing notification and arms the expiry backstop. */
    suspend fun start(context: Context) {
        val prefs = CarlsBrainApp.userPreferences
        prefs.setBusyModeActive(true)
        val startedAt = prefs.busyModeStartedAt.first()
        showOngoingNotification(context, startedAt)
        scheduleExpiry(context, startedAt)
        // Never lets a database problem stop busy mode starting: the log is a nicety,
        // the suppression is the point.
        runCatching { createSessionNote(context, startedAt) }
    }

    /**
     * Turns busy mode off and clears everything it owns — no stale alarm, no notification,
     * no stale session-note id.
     *
     * The session note is tidied up *here* rather than in the Dashboard, because most of the
     * ways a session ends have no UI attached at all: the notification action, the expiry
     * alarm, and the self-heal inside [isSuppressing] all land on this one function.
     */
    suspend fun end(context: Context) {
        CarlsBrainApp.userPreferences.setBusyModeActive(false)
        cancelExpiry(context)
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
        runCatching { finishSessionNote(context) }
    }

    /**
     * Creates the empty note this session's entries will be appended to.
     *
     * Bucket choice is "SES" (case-insensitive) when it exists, else the first non-vault
     * bucket. Only [BucketDao.getNonVaultBuckets] is ever consulted: the log is explicitly
     * offered for sharing when the session ends, so it must never live in a vault bucket.
     * With no non-vault bucket at all there is nowhere safe to put it, and busy mode simply
     * runs without a log.
     */
    private suspend fun createSessionNote(context: Context, startedAt: Long) {
        val db = AppDatabase.getInstance(context)
        val buckets = db.bucketDao().getNonVaultBuckets().first()
        val bucket = buckets.firstOrNull { it.name.equals("SES", ignoreCase = true) }
            ?: buckets.firstOrNull()
            ?: return

        val stamp = if (startedAt > 0L) startedAt else System.currentTimeMillis()
        // The time formats itself relative to `now`, so passing the same instant gives the
        // time-of-day alone; passing an instant a week later forces the absolute "9 Aug" form,
        // which is what a note title needs — "Today" would be a lie tomorrow.
        val time = formatSmartDateTime(stamp, stamp)
        val day = formatSmartDate(stamp, stamp + 7L * 24L * 60L * 60L * 1000L)

        val noteId = db.noteDao().insertNote(
            NoteEntity(
                title = "Busy session — $time, $day",
                content = "",
                bucketId = bucket.id,
                createdAt = stamp,
                updatedAt = stamp
            )
        )
        CarlsBrainApp.userPreferences.setBusyModeNoteId(noteId)
    }

    /**
     * Drops the session note if nothing was ever logged to it — an empty "Busy session" note is
     * clutter, not a record — and clears the stored id either way so the next session starts
     * clean. Uses the same soft delete as every other deletion, so an accidental end can still
     * be recovered from Recently Deleted.
     */
    private suspend fun finishSessionNote(context: Context) {
        val prefs = CarlsBrainApp.userPreferences
        val noteId = prefs.busyModeNoteId.first()
        if (noteId > 0L) {
            val noteDao = AppDatabase.getInstance(context).noteDao()
            val note = noteDao.getNoteById(noteId)
            if (note != null && note.content.isBlank()) noteDao.softDeleteNote(noteId)
        }
        prefs.setBusyModeNoteId(0L)
    }

    /**
     * Whether ambient notifications should be withheld right now.
     *
     * This is the second of the two places the 12-hour expiry is enforced: even if the backstop
     * alarm was lost to a reboot or process death, an expired session can never suppress —
     * it is cleaned up here and reported as inactive.
     *
     * Fails open: any error reading the preference means "not busy", so a notification
     * is never lost to an unexpected failure.
     */
    suspend fun isSuppressing(context: Context): Boolean = runCatching {
        val prefs = CarlsBrainApp.userPreferences
        if (!prefs.busyModeActive.first()) return@runCatching false
        val startedAt = prefs.busyModeStartedAt.first()
        if (startedAt <= 0L || System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) {
            end(context)
            return@runCatching false
        }
        true
    }.getOrDefault(false)

    /**
     * Re-establishes the ongoing notification and the expiry alarm after a reboot or a cold
     * start, or cleans up if it has already expired. Safe to call on every launch.
     */
    suspend fun restoreIfActive(context: Context) {
        runCatching {
            val prefs = CarlsBrainApp.userPreferences
            if (!prefs.busyModeActive.first()) return@runCatching
            val startedAt = prefs.busyModeStartedAt.first()
            if (startedAt <= 0L || System.currentTimeMillis() - startedAt >= MAX_DURATION_MS) {
                end(context)
                return@runCatching
            }
            showOngoingNotification(context, startedAt)
            scheduleExpiry(context, startedAt)
        }
    }

    private fun showOngoingNotification(context: Context, startedAt: Long) {
        if (ActivityCompat.checkSelfPermission(
                context, Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) return

        val startedText = if (startedAt > 0L) {
            " · started " + android.text.format.DateFormat.getTimeFormat(context).format(Date(startedAt))
        } else ""

        val tapIntent = PendingIntent.getActivity(
            context, CONTENT_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply { flags = Intent.FLAG_ACTIVITY_SINGLE_TOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val endIntent = PendingIntent.getBroadcast(
            context, END_REQUEST_CODE,
            Intent(context, BusyModeReceiver::class.java).apply { action = ACTION_END_BUSY_MODE },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        // Capture straight from the notification — the one thing worth doing mid-job. Reuses
        // MainActivity's existing voice-capture deep link (ACTION_OPEN_CAPTURE_VOICE), the same
        // action the voice-capture notification uses, so there is one capture entry point, not two.
        val captureIntent = PendingIntent.getActivity(
            context, CAPTURE_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                action = MainActivity.ACTION_OPEN_CAPTURE_VOICE
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("Busy mode on")
            .setContentText("Notifications paused$startedText")
            .setContentIntent(tapIntent)
            .setOngoing(true)
            .setAutoCancel(false)
            .setShowWhen(true)
            .setWhen(if (startedAt > 0L) startedAt else System.currentTimeMillis())
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .addAction(0, "Capture", captureIntent)
            .addAction(0, "End busy mode", endIntent)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
    }

    /**
     * First of the two expiry enforcement points: an exact alarm at start + 12h, matching the
     * AlarmManager approach the other schedulers in this package use.
     */
    private fun scheduleExpiry(context: Context, startedAt: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, EXPIRY_REQUEST_CODE,
            Intent(context, BusyModeReceiver::class.java).apply { action = ACTION_BUSY_MODE_EXPIRED },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val triggerAt = (if (startedAt > 0L) startedAt else System.currentTimeMillis()) + MAX_DURATION_MS

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !alarmManager.canScheduleExactAlarms()) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        } else {
            alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
        }
    }

    private fun cancelExpiry(context: Context) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val pi = PendingIntent.getBroadcast(
            context, EXPIRY_REQUEST_CODE,
            Intent(context, BusyModeReceiver::class.java).apply { action = ACTION_BUSY_MODE_EXPIRED },
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        ) ?: return
        alarmManager.cancel(pi)
        pi.cancel()
    }
}

/**
 * Handles the "End busy mode" notification action and the 12-hour expiry alarm.
 * Both end busy mode, so they share one receiver.
 */
class BusyModeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BusyMode.ACTION_END_BUSY_MODE, BusyMode.ACTION_BUSY_MODE_EXPIRED -> Unit
            else -> return
        }

        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                runCatching { BusyMode.end(context) }
            } finally {
                pending.finish()
            }
        }
    }
}
