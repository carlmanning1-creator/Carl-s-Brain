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
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.AppDatabase
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
     */
    fun rescheduleAll(context: Context, db: AppDatabase) {
        CarlsBrainApp.appScope.launch {
            runCatching {
                val templates = db.journalTemplateDao().getAllTemplatesIncludingDeleted()
                for (template in templates) {
                    cancel(context, template.id)
                    if (template.deletedAt != null) continue
                    val rule = parse(template.reminderRule) ?: continue
                    schedule(context, template.id, template.name, rule)
                }
            }.onFailure { Log.w(TAG, "Could not reschedule journal reminders: ${it.message}") }
        }
    }

    private fun schedule(context: Context, templateId: Long, name: String, rule: Rule) {
        val alarmManager = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = nextOccurrence(rule)
        runCatching {
            alarmManager.setInexactRepeating(
                AlarmManager.RTC_WAKEUP,
                triggerAt,
                AlarmManager.INTERVAL_DAY * 7,
                pendingIntent(context, templateId, name)
            )
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
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            ActivityCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        // Opens the app rather than deep-linking to the template: one deleted since the alarm
        // was set would otherwise open a screen with nothing on it.
        val tapIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pending = PendingIntent.getActivity(
            context, templateId.toInt(), tapIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(context, JournalReminderScheduler.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_edit)
            .setContentTitle(if (name.isBlank()) "Journal" else name)
            .setContentText("Write one up?")
            .setAutoCancel(true)
            .setContentIntent(pending)
            .build()
        context.getSystemService(NotificationManager::class.java)
            .notify(JournalReminderScheduler.CHANNEL_ID.hashCode() + templateId.toInt(), notification)
    }
}
