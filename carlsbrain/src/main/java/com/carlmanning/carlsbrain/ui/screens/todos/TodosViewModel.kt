package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.domain.model.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

class TodosViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val buckets: StateFlow<Map<Long, BucketEntity>> = db.bucketDao()
        .getAllBuckets()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val bucketList: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getAllBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPriority = MutableStateFlow<Priority?>(null)
    val selectedPriority: StateFlow<Priority?> = _selectedPriority

    private val _selectedBucketId = MutableStateFlow<Long?>(null)
    val selectedBucketId: StateFlow<Long?> = _selectedBucketId

    val todos: StateFlow<List<Todo>> = db.todoDao().getVisibleTodos()
        .combine(_selectedPriority) { entities, priorityFilter ->
            val all = entities.map { it.toDomain() }
            if (priorityFilter == null) all else all.filter { it.priority == priorityFilter }
        }
        .combine(_selectedBucketId) { filtered, bucketFilter ->
            if (bucketFilter == null) filtered else filtered.filter { it.bucketId == bucketFilter }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onPriorityFilterSelected(priority: Priority?) {
        _selectedPriority.value = priority
    }

    fun onBucketFilterSelected(bucketId: Long?) {
        _selectedBucketId.value = bucketId
    }

    fun toggleDone(todoId: Long, isDone: Boolean) {
        viewModelScope.launch {
            db.todoDao().setTodoDone(todoId, isDone)
            if (isDone) {
                val entity = db.todoDao().getTodoById(todoId) ?: return@launch
                val recurrence = Recurrence.fromStorageString(entity.recurrence)
                if (recurrence != Recurrence.None) {
                    spawnNextRecurrence(entity, recurrence)
                }
            }
        }
    }

    private suspend fun spawnNextRecurrence(entity: TodoEntity, recurrence: Recurrence) {
        val nextDue = nextDateMs(entity.dueDate, recurrence) ?: return
        val intervalMs = nextDue - (entity.dueDate ?: System.currentTimeMillis())
        val nextReminder = entity.reminderAt?.let { it + intervalMs }

        val newId = db.todoDao().insertTodo(
            entity.copy(
                id = 0,
                dueDate = nextDue,
                reminderAt = nextReminder,
                isDone = false,
                isArchived = false,
                archivedAt = null,
                createdAt = System.currentTimeMillis(),
                updatedAt = System.currentTimeMillis(),
                isSynced = false
            )
        )
        if (nextReminder != null && nextReminder > System.currentTimeMillis()) {
            ReminderScheduler.schedule(getApplication(), newId, entity.title, nextReminder)
        }
    }

    private fun nextDateMs(baseMs: Long?, recurrence: Recurrence): Long? {
        val from = baseMs ?: System.currentTimeMillis()
        return when (recurrence) {
            is Recurrence.Daily -> from + TimeUnit.DAYS.toMillis(1)
            is Recurrence.Weekly -> from + TimeUnit.DAYS.toMillis(7)
            is Recurrence.Monthly -> Calendar.getInstance().apply {
                timeInMillis = from; add(Calendar.MONTH, 1)
            }.timeInMillis
            is Recurrence.Custom -> from + TimeUnit.DAYS.toMillis(recurrence.intervalDays.toLong())
            else -> null
        }
    }

    fun archiveTodo(todoId: Long) {
        viewModelScope.launch {
            db.todoDao().archiveTodo(todoId)
        }
    }
}
