package com.carlmanning.carlsbrain.domain.usecase

import android.content.Context
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.domain.model.Recurrence
import java.util.Calendar
import java.util.concurrent.TimeUnit

/**
 * The single implementation of "complete a to-do".
 *
 * This logic used to live verbatim in both TodosViewModel and DashboardViewModel. Two copies of
 * recurrence handling drift, and the failure mode is silent: a recurring task stops recurring, or
 * spawns duplicates. Every caller (Todos, bulk actions, Dashboard) now goes through here.
 */
class CompleteTodoUseCase(private val context: Context) {

    private val db = AppDatabase.getInstance(context)

    /**
     * Marks a todo done/undone, spawning the next occurrence when a recurring todo is completed.
     *
     * @return the id of the spawned occurrence, or null when nothing was spawned. Callers offering
     *         an Undo must keep this id and hand it back to [undoDone], otherwise the undo leaves
     *         the spawned occurrence behind as a duplicate.
     */
    suspend fun markDone(todoId: Long, isDone: Boolean): Long? {
        db.todoDao().setTodoDone(todoId, isDone)
        if (!isDone) return null
        // A finished to-do must stop nagging. Its reminder alarm was left armed, so a to-do
        // ticked off in the morning still buzzed in the afternoon — the app reminding Carl about
        // work he had already done, which is precisely the thing it exists to prevent.
        ReminderScheduler.cancel(context, todoId)
        val entity = db.todoDao().getTodoById(todoId) ?: return null
        val recurrence = Recurrence.fromStorageString(entity.recurrence)
        if (recurrence == Recurrence.None) return null
        // Idempotency: skip if a non-done todo with same title+recurrence already exists
        val existing = db.todoDao().findActiveRecurringByTitleAndRecurrence(
            entity.title, entity.recurrence
        )
        return if (existing == null) spawnNextRecurrence(entity, recurrence) else null
    }

    /**
     * Reverses [markDone]: un-ticks the original and removes the occurrence the completion
     * spawned. The spawned row is hard-deleted rather than soft-deleted — it was never a task
     * Carl saw, so it must not land in Recently Deleted.
     */
    suspend fun undoDone(todoId: Long, spawnedId: Long?) {
        db.todoDao().setTodoDone(todoId, false)
        if (spawnedId == null) return
        val spawned = db.todoDao().getTodoById(spawnedId) ?: return
        ReminderScheduler.cancel(context, spawnedId)
        db.todoDao().deleteTodo(spawned)
    }

    /** @return the id of the newly inserted occurrence, or null when no next date applies. */
    private suspend fun spawnNextRecurrence(entity: TodoEntity, recurrence: Recurrence): Long? {
        val nextDue = nextDateMs(entity.dueDate, recurrence) ?: return null
        // The reminder is placed relative to the NEW due date, keeping the offset the old one
        // had. Shifting it by the gap between occurrences was equivalent while that gap was one
        // interval, but nextDateMs now catches up past due dates — so the old arithmetic would
        // have thrown a reminder weeks past its own to-do.
        val oldReminder = entity.reminderAt
        val oldDue = entity.dueDate
        val nextReminder = when {
            entity.leadDays > 0 -> nextDue - TimeUnit.DAYS.toMillis(entity.leadDays.toLong())
            oldReminder != null && oldDue != null -> nextDue + (oldReminder - oldDue)
            else -> null
        }
        val newId = db.todoDao().insertTodo(
            entity.copy(
                id = 0, dueDate = nextDue, reminderAt = nextReminder,
                isDone = false, isArchived = false, archivedAt = null,
                // Cleared, not copied. Both are back-references to something that belongs to the
                // occurrence just completed: two rows claiming the same calendarEventId made the
                // calendar import's duplicate guard return whichever it found first, and a
                // sourceMeetingId carried forward said this week's task came out of a meeting
                // held weeks ago.
                calendarEventId = null,
                sourceMeetingId = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
        if (nextReminder != null && nextReminder > System.currentTimeMillis()) {
            ReminderScheduler.schedule(context, newId, entity.title, nextReminder)
        }
        return newId
    }

    /**
     * The next occurrence, which is always in the future.
     *
     * One step from the old due date was the obvious reading and the wrong one: completing a
     * weekly to-do that had been sitting there three weeks overdue produced another to-do that
     * was *already two weeks overdue*, and completing that one produced another. A recurring
     * task Carl had fallen behind on could never be caught up — it just re-presented itself as
     * failure, over and over.
     *
     * Stepping until the date is in the future keeps the rhythm — a Sunday weekly stays on
     * Sundays, a monthly stays on its day of the month — while never being born late. The loop
     * is bounded because a Custom recurrence with a nonsensical interval must not spin.
     */
    private fun nextDateMs(baseMs: Long?, recurrence: Recurrence): Long? {
        val now = System.currentTimeMillis()
        var from = baseMs ?: now
        var next = stepOnce(from, recurrence) ?: return null
        var guard = 0
        while (next <= now && guard < MAX_CATCH_UP_STEPS) {
            from = next
            next = stepOnce(from, recurrence) ?: return next
            guard++
        }
        return next
    }

    /** One interval on from [from]. Null for a recurrence with no next date. */
    private fun stepOnce(from: Long, recurrence: Recurrence): Long? = when (recurrence) {
        is Recurrence.Daily -> from + TimeUnit.DAYS.toMillis(1)
        is Recurrence.Weekly -> from + TimeUnit.DAYS.toMillis(7)
        is Recurrence.Fortnightly -> from + TimeUnit.DAYS.toMillis(14)
        is Recurrence.Monthly -> Calendar.getInstance().apply {
            timeInMillis = from; add(Calendar.MONTH, 1)
        }.timeInMillis
        is Recurrence.Custom ->
            if (recurrence.intervalDays <= 0) null
            else from + TimeUnit.DAYS.toMillis(recurrence.intervalDays.toLong())
        else -> null
    }

    private companion object {
        /**
         * Enough to catch up a daily to-do abandoned for years, and low enough that a broken
         * interval cannot loop forever.
         */
        const val MAX_CATCH_UP_STEPS = 2000
    }
}
