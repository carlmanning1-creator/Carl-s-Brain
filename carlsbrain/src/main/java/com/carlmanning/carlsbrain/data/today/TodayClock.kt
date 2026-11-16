package com.carlmanning.carlsbrain.data.today

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.ErrorLog
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId

/**
 * Redraws the Today surfaces when time alone changes the answer.
 *
 * Everything else that moves them is an edit — a table change, busy mode, the briefing — and
 * TodaySurfaces already hears about those. But nothing is edited when a one-hour snooze runs
 * out, when a 5 pm to-do becomes overdue at 5:00, or when the next calendar event starts. The
 * pinned notification and any open check-in went on showing the old answer until something
 * unrelated happened to change.
 *
 * So after every refresh one alarm is armed for the next moment anything on these surfaces
 * would change. On fire it refreshes, and that refresh arms the next one — a chain with one
 * link at a time, replaced on every arm, so it cannot multiply.
 *
 * Inexact on purpose (`setAndAllowWhileIdle`, not an exact alarm): a minute or two late is
 * invisible here, and exact alarms are a permission the to-do reminders already depend on.
 */
object TodayClock {

    /** Never sooner than this: a boundary a few seconds away is already being drawn. */
    private const val MIN_DELAY_MS = 60_000L

    /** Never later than this, so a missed boundary is corrected within the hour anyway. */
    private const val MAX_DELAY_MS = 60 * 60_000L

    private const val REQUEST_CODE = 9201

    /** Called at the end of every refresh. Never throws. */
    suspend fun armNext(context: Context, now: Long = System.currentTimeMillis()) {
        runCatching {
            val next = nextBoundary(context, now)
            val at = next.coerceIn(now + MIN_DELAY_MS, now + MAX_DELAY_MS)
            val am = context.getSystemService(AlarmManager::class.java) ?: return
            am.setAndAllowWhileIdle(AlarmManager.RTC, at, pendingIntent(context))
        }.onFailure { ErrorLog.record("TodayClock/arm", it) }
    }

    private suspend fun nextBoundary(context: Context, now: Long): Long {
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val dayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val tomorrowEnd = today.plusDays(2).atStartOfDay(zone).toInstant().toEpochMilli()
        val db = AppDatabase.getInstance(context)

        val times = ArrayList<Long>()
        // A snooze ending brings a to-do back.
        times += TodayState.snoozes(context, now).values
        // A due time passing turns "due today" into "overdue" — and tomorrow's into today's.
        db.todoDao().getTodayCandidatesNonVault(tomorrowEnd).mapNotNullTo(times) { it.dueDate }
        // The calendar line names the next event, and changes when one starts or ends.
        db.calendarEventDao().getEventsForDay(dayStart, tomorrowEnd)
            .filter { !it.isAllDay }
            .forEach { times += it.startMs; times += it.endMs }
        // The undo row has to go when its window closes, even with the process asleep.
        TodayState.undo(context, now)?.let { times += it.expiresAt }

        return times.filter { it > now }.minOrNull() ?: (now + MAX_DELAY_MS)
    }

    private fun pendingIntent(context: Context): PendingIntent = PendingIntent.getBroadcast(
        context, REQUEST_CODE,
        Intent(context, TodayClockReceiver::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )
}

/** Fired by [TodayClock]. Refreshes, which re-arms. */
class TodayClockReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        CarlsBrainApp.appScope.launch {
            try {
                TodaySurfaces.refresh(context.applicationContext)
            } catch (e: Exception) {
                ErrorLog.record("TodayClockReceiver", e)
            } finally {
                pending.finish()
            }
        }
    }
}
