package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class TodoEditorUiState(
    val id: Long = 0,
    val title: String = "",
    val priority: Priority = Priority.NORMAL,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
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
    fun onBucketChange(bucketId: Long) = _uiState.update { it.copy(selectedBucketId = bucketId) }

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
            db.todoDao().deleteTodo(existing)
            onComplete()
        }
    }
}
