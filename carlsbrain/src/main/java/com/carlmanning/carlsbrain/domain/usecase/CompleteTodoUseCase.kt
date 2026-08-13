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
        val intervalMs = nextDue - (entity.dueDate ?: System.currentTimeMillis())
        // Apply lead-days reminder: notify leadDays before the due date
        val nextReminder = if (entity.leadDays > 0) {
            nextDue - TimeUnit.DAYS.toMillis(entity.leadDays.toLong())
        } else {
            entity.reminderAt?.let { it + intervalMs }
        }
        val newId = db.todoDao().insertTodo(
            entity.copy(
                id = 0, dueDate = nextDue, reminderAt = nextReminder,
                isDone = false, isArchived = false, archivedAt = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
        if (nextReminder != null && nextReminder > System.currentTimeMillis()) {
            ReminderScheduler.schedule(context, newId, entity.title, nextReminder)
        }
        return newId
    }

    private fun nextDateMs(baseMs: Long?, recurrence: Recurrence): Long? {
        val from = baseMs ?: System.currentTimeMillis()
        return when (recurrence) {
            is Recurrence.Daily -> from + TimeUnit.DAYS.toMillis(1)
            is Recurrence.Weekly -> from + TimeUnit.DAYS.toMillis(7)
            is Recurrence.Fortnightly -> from + TimeUnit.DAYS.toMillis(14)
            is Recurrence.Monthly -> Calendar.getInstance().apply {
                timeInMillis = from; add(Calendar.MONTH, 1)
            }.timeInMillis
            is Recurrence.Custom -> from + TimeUnit.DAYS.toMillis(recurrence.intervalDays.toLong())
            else -> null
        }
    }
}
