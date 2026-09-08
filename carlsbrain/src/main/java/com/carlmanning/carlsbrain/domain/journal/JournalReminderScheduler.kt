package com.carlmanning.carlsbrain.domain.journal

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.local.worker.BusyMode
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.AppDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.Calendar

/**
 * Nudges Carl to write a templated entry, on the schedule the template itself carries.
 *
 * A rule is `DOW:HH:MM` — `SUN:10:00` for the training template after Sunday CrossFit. Blank
 * means no reminder, which is the default and stays the default: a template that nags without
 * being asked to is one Carl deletes.
 *
 * Deliberately built on AlarmManager rather than WorkManager, matching the digest and todo
 * reminders already in the app: this needs to land at a specific minute, and WorkManager's
 * batching moves it around.
 */
object JournalReminderScheduler {

    private const val TAG = "JournalReminder"
    const val CHANNEL_ID = "journal_reminders"
    const val EXTRA_TEMPLATE_ID = "template_id"
    const val EXTRA_TEMPLATE_NAME = "template_name"

    /**
     * Alarm request codes are offset so they cannot collide with any other alarm in the app.
     * A collision would silently replace someone else's alarm rather than failing.
     */
    private const val REQUEST_CODE_BASE = 71_000

    private val DAYS = mapOf(
        "SUN" to Calendar.SUNDAY, "MON" to Calendar.MONDAY, "TUE" to Calendar.TUESDAY,
        "WED" to Calendar.WEDNESDAY, "THU" to Calendar.THURSDAY, "FRI" to Calendar.FRIDAY,
        "SAT" to Calendar.SATURDAY
    )

    /** Parsed form of a rule, or null when the rule is blank or malformed. */
    data class Rule(val dayOfWeek: Int, val hour: Int, val minute: Int)

    fun parse(rule: String): Rule? {
        val parts = rule.trim().uppercase().split(":")
        if (parts.size != 3) return null
        val day = DAYS[parts[0]] ?: return null
        val hour = parts[1].toIntOrNull()?.takeIf { it in 0..23 } ?: return null
        val minute = parts[2].toIntOrNull()?.takeIf { it in 0..59 } ?: return null
        return Rule(day, hour, minute)
    }

    fun describe(rule: String): String {
        val parsed = parse(rule) ?: return "No reminder"
        val dayName = DAYS.entries.find { it.value == parsed.dayOfWeek }?.key ?: "?"
        return "%s %02d:%02d".format(dayName.lowercase().replaceFirstChar { it.uppercase() },
            parsed.hour, parsed.minute)
    }

    /**
     * Rebuilds every journal reminder alarm from the templates as they now stand.
     *
     * Rebuilt wholesale rather than adjusted in place: AlarmManager holds no readable list, so
     * the only way to be sure a deleted or retimed rule is gone is to cancel and re-set each
     * one. Cheap — there will only ever be a handful.
     *
     * ## Names and the lock screen
     *
     * A template that is private by default, or whose default bucket is a vault bucket, is
     * scheduled with a blank name. Its reminder still fires — Carl asked for it and switching
     * it off silently would be worse — but the notification says "Journal" rather than
     * announcing what he keeps private, once a week, on a screen anyone can see. The web app
     * already withholds exactly these templates while locked; the phone's own reminder did not.
     */
    fun rescheduleAll(context: Context, db: AppDatabase) {
        CarlsBrainApp.appScope.launch {
            runCatching {
                val templates = db.journalTemplateDao().getAllTemplatesIncludingDeleted()
                // A template whose name must not appear on the lock screen carries no name
                // into the alarm at all, rather than being filtered when the notification is
                // built. Resolved here, where the bucket list is already to hand, and the
                // PendingIntent then simply has nothing sensitive in it to leak.
                val vaultBucketIds = db.bucketDao().getAllBuckets().first()
                    .filter { it.isVault }.map { it.id }.toSet()
                for (template in templates) {
                    cancel(context, template.id)
                    if (template.deletedAt != null) continue
                    val rule = parse(template.reminderRule) ?: continue
                    val bucketId = template.bucketId
                    val nameIsPrivate = template.isPrivateByDefault ||
                        (bucketId != null && bucketId in vaultBucketIds)
                    schedule(
                        context,
                        template.id,
                        if (nameIsPrivate) "" else template.name,
                        rule
                    )
                }
            }.onFailure { Log.w(TAG, "Could not reschedule journal reminders: ${it.message}") }
        }
    }

    /**
     * Arms one template's reminder for its next occurrence.
     *
     * Exact, and one-shot rather than repeating — which is what this class's own header always
     * said it needed ("this needs to land at a specific minute"), while the code used
     * `setInexactRepeating`, whose whole purpose is to let Android batch the alarm and move it
     * by up to an hour. A Sunday-10am nudge that arrives at 10:47 has missed the moment it was
     * written for.
     *
     * One-shot because an exact alarm has no repeating form: the receiver re-arms the next
     * occurrence after it fires, and `rescheduleAll` rebuilds everything at launch and after a
     * pull, so a missed re-arm self-heals the next time the app is opened.
     *
     * Falls back to an inexact alarm when the exact-alarm permission has been revoked, rather
     * than throwing away the reminder entirely — late is better than never here.
     */
    private fun schedule(context: Context, templateId: Long, name: String, rule: Rule) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextOccurrence(rule)
        val pi = pendingIntent(context, templateId, name)
        runCatching {
            val canBeExact = android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            if (canBeExact) {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            } else {
                alarmManager.set(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            }
        }.onFailure { Log.w(TAG, "Alarm for $name refused: ${it.message}") }
    }

    private fun cancel(context: Context, templateId: Long) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        runCatching { alarmManager.cancel(pendingIntent(context, templateId, "")) }
    }

    private fun pendingIntent(context: Context, templateId: Long, name: String): PendingIntent {
        val intent = Intent(context, JournalReminderReceiver::class.java).apply {
            putExtra(EXTRA_TEMPLATE_ID, templateId)
            putExtra(EXTRA_TEMPLATE_NAME, name)
        }
        return PendingIntent.getBroadcast(
            context,
            REQUEST_CODE_BASE + templateId.toInt(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun nextOccurrence(rule: Rule): Long {
        val now = System.currentTimeMillis()
        val cal = Calendar.getInstance().apply {
            set(Calendar.DAY_OF_WEEK, rule.dayOfWeek)
            set(Calendar.HOUR_OF_DAY, rule.hour)
            set(Calendar.MINUTE, rule.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        // set(DAY_OF_WEEK) can land in the past within the current week.
        if (cal.timeInMillis <= now) cal.add(Calendar.WEEK_OF_YEAR, 1)
        return cal.timeInMillis
    }
}

/**
 * Fires the reminder notification. Tapping it opens the Journal, not the template directly —
 * a template deleted since the alarm was set would otherwise open nothing.
 */
class JournalReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val name = intent.getStringExtra(JournalReminderScheduler.EXTRA_TEMPLATE_NAME).orEmpty()
        val templateId = intent.getLongExtra(JournalReminderScheduler.EXTRA_TEMPLATE_ID, -1L)

        // goAsync, because the busy-mode check is a suspend read of DataStore.
        //
        // The work is in a named function so its early returns are ordinary local returns:
        // inside a runCatching lambda they would be non-local and could skip pending.finish(),
        // leaking the lease and holding the process alive.
        val pending = goAsync()
        CarlsBrainApp.appScope.launch {
            runCatching { postIfAllowed(context, name, templateId) }
                .onFailure { ErrorLog.record("JournalReminderReceiver", it) }
            pending.finish()
        }
    }

    private suspend fun postIfAllowed(context: Context, name: String, templateId: Long) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Busy mode suppresses this too.
        //
        // It covered the digest, the four slots and the weekly review — every notification the
        // app volunteers — and missed this one, so the Sunday training nudge still fired on an
        // SES job. That is exactly the category busy mode exists for: the app suggesting Carl
        // write something, rather than an alarm he set for a specific thing at a specific time.
        //
        // isSuppressing fails open, so an error there means the reminder still arrives.
        if (BusyMode.isSuppressing(context)) return

        // Opens the app rather than deep-linking to the template: one deleted since the alarm
        // was set would otherwise open a screen with nothing on it.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            context, templateId.toInt(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, JournalReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(if (name.isBlank()) "Journal" else name)
            .setContentText("Write one up?")
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(JournalReminderScheduler.CHANNEL_ID.hashCode() + templateId.toInt(), notification)

        // Re-arm for next week. An exact alarm is one-shot, so without this the reminder fires
        // once and never again. rescheduleAll at launch is the backstop if this ever fails.
        runCatching { JournalReminderScheduler.rescheduleAll(context, AppDatabase.getInstance(context)) }
            .onFailure { ErrorLog.record("JournalReminderReceiver/rearm", it) }
    }
}
