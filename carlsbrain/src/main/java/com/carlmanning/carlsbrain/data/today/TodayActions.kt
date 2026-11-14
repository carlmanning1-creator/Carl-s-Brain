package com.carlmanning.carlsbrain.data.today

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.ErrorLog
import com.carlmanning.carlsbrain.data.local.worker.DriveSyncWorker
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.domain.usecase.CompleteTodoUseCase
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.TimeUnit

/**
 * What a tick, a snooze or an undo *does*, wherever it was tapped.
 *
 * The widget, the pinned notification and the check-ins are three ways in to these four
 * functions and nothing else. Completion goes through [CompleteTodoUseCase] — the rule for the
 * whole app — so a recurring to-do ticked from the lock screen produces its next occurrence
 * exactly as one ticked in the Todos screen does. There is no second copy of that logic here.
 */
object TodayActions {

    /** @param row where the tick sat on the widget; other surfaces leave it at 0. */
    suspend fun done(context: Context, todoId: Long, row: Int = 0) {
        val app = context.applicationContext
        val todo = AppDatabase.getInstance(app).todoDao().getTodoById(todoId) ?: return
        // A second tap on a row that has not redrawn yet. Completing twice would spawn a
        // second occurrence of a recurring to-do.
        if (todo.isDone) {
            TodaySurfaces.refresh(app)
            return
        }
        val spawnedId = CompleteTodoUseCase(app).markDone(todoId, true)
        TodayState.clearSnooze(app, todoId)
        TodayState.setUndo(
            app,
            TodayState.Undo(
                todoId = todoId,
                spawnedId = spawnedId,
                title = todo.title,
                expiresAt = System.currentTimeMillis() + TodayState.UNDO_WINDOW_MS,
                row = row
            )
        )
        requestSync(app)
        TodaySurfaces.refresh(app)
        // Close the window on screen. If the process dies first nothing is lost: every render
        // and undo() itself check the expiry, so a lingering "Undo" cannot undo anything.
        CarlsBrainApp.appScope.launch {
            delay(TodayState.UNDO_WINDOW_MS + 250)
            TodaySurfaces.refresh(app)
        }
    }

    suspend fun undo(context: Context, todoId: Long) {
        val app = context.applicationContext
        val pending = TodayState.undo(app)
        // Expired, or belongs to a different completion — either way there is nothing this
        // tap is entitled to reverse.
        if (pending == null || pending.todoId != todoId) {
            TodaySurfaces.refresh(app)
            return
        }
        TodayState.setUndo(app, null)
        CompleteTodoUseCase(app).undoDone(pending.todoId, pending.spawnedId)
        // markDone cancelled the reminder; an undone to-do should nag again.
        runCatching {
            val todo = AppDatabase.getInstance(app).todoDao().getTodoById(todoId)
            val at = todo?.reminderAt
            if (todo != null && at != null && at > System.currentTimeMillis()) {
                ReminderScheduler.schedule(app, todo.id, todo.title, at, false)
            }
        }.onFailure { ErrorLog.record("TodayActions/rearm", it) }
        requestSync(app)
        TodaySurfaces.refresh(app)
    }

    /** Hides the to-do from the Today surfaces until [until]. Changes nothing about the to-do. */
    suspend fun snooze(context: Context, todoId: Long, until: Long) {
        val app = context.applicationContext
        TodayState.snooze(app, todoId, until)
        TodaySurfaces.refresh(app)
    }

    fun inOneHour(now: Long = System.currentTimeMillis()): Long = now + TimeUnit.HOURS.toMillis(1)

    /** Six in the morning: before the digest, so it is back in time to be mentioned. */
    fun tomorrowMorning(zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.now(zone).plusDays(1).atTime(6, 0).atZone(zone).toInstant().toEpochMilli()

    /**
     * A tick away from the app still has to reach Drive, or the web app goes on showing it.
     * Delayed past the undo window and replaced on each call, so a tick-then-undo, or five
     * ticks in a row, costs one sync rather than several.
     */
    private fun requestSync(context: Context) {
        runCatching {
            val request = OneTimeWorkRequestBuilder<DriveSyncWorker>()
                .setConstraints(
                    Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
                )
                .setInitialDelay(20, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork("drive_sync_today", ExistingWorkPolicy.REPLACE, request)
        }.onFailure { ErrorLog.record("TodayActions/sync", it) }
    }
}

/**
 * The notification side's way in to [TodayActions]. (The widget calls them directly from a
 * Glance callback, which already runs off the main thread with its own lease.)
 *
 * Works with the process dead: a notification tap starts it, and nothing here needs an
 * activity, the biometric gate or the vault.
 */
class TodoActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        val todoId = intent.getLongExtra(EXTRA_TODO_ID, -1L)
        val pending = goAsync()
        CarlsBrainApp.appScope.launch {
            // A named function, not an inline runCatching block: an early return inside an
            // inline lambda would skip finish() and leak the lease.
            runCatching { handle(context.applicationContext, action, todoId) }
                .onFailure { ErrorLog.record("TodoActionReceiver/$action", it) }
            pending.finish()
        }
    }

    private suspend fun handle(context: Context, action: String, todoId: Long) {
        when (action) {
            ACTION_PINNED_DISMISSED -> {
                TodayState.setPinnedDismissed(context, true)
                return
            }
        }
        if (todoId == -1L) return
        when (action) {
            ACTION_DONE -> TodayActions.done(context, todoId)
            ACTION_UNDO -> TodayActions.undo(context, todoId)
            ACTION_SNOOZE_1H -> TodayActions.snooze(context, todoId, TodayActions.inOneHour())
            ACTION_SNOOZE_TOMORROW ->
                TodayActions.snooze(context, todoId, TodayActions.tomorrowMorning())
        }
    }

    companion object {
        const val ACTION_DONE = "com.carlmanning.carlsbrain.today.DONE"
        const val ACTION_UNDO = "com.carlmanning.carlsbrain.today.UNDO"
        const val ACTION_SNOOZE_1H = "com.carlmanning.carlsbrain.today.SNOOZE_1H"
        const val ACTION_SNOOZE_TOMORROW = "com.carlmanning.carlsbrain.today.SNOOZE_TOMORROW"
        const val ACTION_PINNED_DISMISSED = "com.carlmanning.carlsbrain.today.PINNED_DISMISSED"
        const val EXTRA_TODO_ID = "todo_id"
    }
}
