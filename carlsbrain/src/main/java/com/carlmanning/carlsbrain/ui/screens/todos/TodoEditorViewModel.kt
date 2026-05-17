package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodoEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val priority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
    val recurrence: Recurrence = Recurrence.None,
    val selectedBucketId: Long? = null,
    val isLoading: Boolean = true,
    val isSaved: Boolean = false
)

class TodoEditorViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(TodoEditorUiState())
    val uiState: StateFlow<TodoEditorUiState> = _uiState.asStateFlow()

    val subtasks: StateFlow<List<SubtaskEntity>> = _uiState
        .flatMapLatest { state ->
            if (state.id == 0L) flowOf(emptyList())
            else db.subtaskDao().getSubtasksForTodo(state.id)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun loadTodo(todoId: Long) {
        viewModelScope.launch {
            val todo = db.todoDao().getTodoById(todoId)
            if (todo != null) {
                _uiState.update {
                    it.copy(
                        id = todo.id,
                        title = todo.title,
                        priority = Priority.valueOf(todo.priority),
                        dueDate = todo.dueDate,
                        reminderAt = todo.reminderAt,
                        recurrence = Recurrence.fromStorageString(todo.recurrence),
                        selectedBucketId = todo.bucketId,
                        isLoading = false
                    )
                }
            } else {
                _uiState.update { it.copy(isLoading = false) }
            }
        }
    }

    fun onTitleChange(title: String) = _uiState.update { it.copy(title = title) }
    fun onPriorityChange(priority: Priority) = _uiState.update { it.copy(priority = priority) }
    fun onDueDateChange(dateMs: Long?) = _uiState.update { it.copy(dueDate = dateMs) }
    fun onReminderChange(reminderAt: Long?) = _uiState.update { it.copy(reminderAt = reminderAt) }
    fun onRecurrenceChange(recurrence: Recurrence) = _uiState.update { it.copy(recurrence = recurrence) }
    fun onBucketChange(bucketId: Long) = _uiState.update { it.copy(selectedBucketId = bucketId) }

    // ── Subtask CRUD ────────────────────────────────────────────────

    fun addSubtask(title: String) {
        val trimmed = title.trim()
        if (trimmed.isBlank()) return
        viewModelScope.launch {
            val nextOrder = subtasks.value.size
            db.subtaskDao().insertSubtask(
                SubtaskEntity(todoId = _uiState.value.id, title = trimmed, sortOrder = nextOrder)
            )
        }
    }

    fun toggleSubtask(subtask: SubtaskEntity) {
        viewModelScope.launch {
            db.subtaskDao().updateSubtask(subtask.copy(isDone = !subtask.isDone))
        }
    }

    fun deleteSubtask(subtask: SubtaskEntity) {
        viewModelScope.launch { db.subtaskDao().deleteSubtask(subtask) }
    }

    fun reorderSubtask(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = subtasks.value.toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
            val moved = current.removeAt(fromIndex)
            current.add(toIndex, moved)
            current.forEachIndexed { index, subtask ->
                db.subtaskDao().updateSubtask(subtask.copy(sortOrder = index))
            }
        }
    }

    // ── Save / Delete ───────────────────────────────────────────────

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        if (state.title.isBlank()) return
        viewModelScope.launch {
            val existing = db.todoDao().getTodoById(state.id) ?: return@launch
            db.todoDao().updateTodo(
                existing.copy(
                    title = state.title.trim(),
                    priority = state.priority.name,
                    dueDate = state.dueDate,
                    reminderAt = state.reminderAt,
                    recurrence = state.recurrence.toStorageString(),
                    bucketId = state.selectedBucketId ?: existing.bucketId,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )
            )
            val reminderAt = state.reminderAt
            if (reminderAt != null && reminderAt > System.currentTimeMillis()) {
                ReminderScheduler.schedule(getApplication(), state.id, state.title.trim(), reminderAt)
            } else {
                ReminderScheduler.cancel(getApplication(), state.id)
            }
            onComplete()
        }
    }

    fun delete(onComplete: () -> Unit) {
        viewModelScope.launch {
            val existing = db.todoDao().getTodoById(_uiState.value.id) ?: return@launch
            ReminderScheduler.cancel(getApplication(), existing.id)
            db.subtaskDao().deleteSubtasksForTodo(existing.id)
            db.todoDao().deleteTodo(existing)
            onComplete()
        }
    }
}
