package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.domain.model.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar
import java.util.concurrent.TimeUnit

enum class TodoSortMode(val label: String) {
    PRIORITY("Priority"),
    DUE_DATE("Due date"),
    CREATED("Created"),
    ALPHABETICAL("A–Z"),
    MANUAL("Manual")
}

class TodosViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val prefs = UserPreferences(app)

    val swipeToCompleteEnabled: StateFlow<Boolean> = prefs.swipeToCompleteEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _vaultOpen = MutableStateFlow(false)
    fun setVaultVisible(open: Boolean) { _vaultOpen.value = open }

    val buckets: StateFlow<Map<Long, BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val bucketList: StateFlow<List<BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _selectedPriority = MutableStateFlow<Priority?>(null)
    val selectedPriority: StateFlow<Priority?> = _selectedPriority

    private val _selectedBucketId = MutableStateFlow<Long?>(null)
    val selectedBucketId: StateFlow<Long?> = _selectedBucketId

    val sortMode: StateFlow<TodoSortMode> = prefs.todosSortMode
        .map { s -> TodoSortMode.entries.find { it.name == s } ?: TodoSortMode.PRIORITY }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TodoSortMode.PRIORITY)

    val todos: StateFlow<List<Todo>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.todoDao().getVisibleTodos()
            else db.todoDao().getVisibleNonVaultTodos()
        }
        .combine(_selectedPriority) { entities, priorityFilter ->
            val all = entities.map { it.toDomain() }
            if (priorityFilter == null) all else all.filter { it.priority == priorityFilter }
        }
        .combine(_selectedBucketId) { filtered, bucketFilter ->
            if (bucketFilter == null) filtered else filtered.filter { it.bucketId == bucketFilter }
        }
        .combine(sortMode) { filtered, mode ->
            val sorted = when (mode) {
                TodoSortMode.PRIORITY -> filtered.sortedWith(
                    compareBy({ it.isDone }, { it.priority.ordinal }, { it.dueDate ?: Long.MAX_VALUE })
                )
                TodoSortMode.DUE_DATE -> filtered.sortedWith(
                    compareBy({ it.isDone }, { it.dueDate ?: Long.MAX_VALUE }, { it.priority.ordinal })
                )
                TodoSortMode.CREATED -> filtered.sortedWith(
                    compareBy({ it.isDone }, { -it.createdAt })
                )
                TodoSortMode.ALPHABETICAL -> filtered.sortedWith(
                    compareBy({ it.isDone }, { it.title.lowercase() })
                )
                TodoSortMode.MANUAL -> filtered
            }
            sorted.sortedByDescending { it.isPinned }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Load all subtasks for currently visible todos
    val subtasksMap: StateFlow<Map<Long, List<SubtaskEntity>>> = todos
        .flatMapLatest { todoList ->
            val ids = todoList.map { it.id }
            if (ids.isEmpty()) {
                MutableStateFlow(emptyMap())
            } else {
                db.subtaskDao().getSubtasksForTodos(ids)
                    .map { subtasks -> subtasks.groupBy { it.todoId } }
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _prioritisationResult = MutableStateFlow<String?>(null)
    val prioritisationResult: StateFlow<String?> = _prioritisationResult.asStateFlow()

    private val _isPrioritising = MutableStateFlow(false)
    val isPrioritising: StateFlow<Boolean> = _isPrioritising.asStateFlow()

    fun dismissPrioritisationResult() { _prioritisationResult.value = null }

    fun prioritiseWithClaude() {
        viewModelScope.launch {
            val apiKey = CarlsBrainApp.userPreferences.anthropicApiKey.first()
            if (apiKey.isBlank()) { _prioritisationResult.value = "Add your Anthropic API key in Settings first."; return@launch }
            val currentTodos = todos.value.filter { !it.isDone }.take(30)
            if (currentTodos.isEmpty()) { _prioritisationResult.value = "No active to-dos to prioritise."; return@launch }
            _isPrioritising.value = true
            val list = currentTodos.joinToString("\n") { todo ->
                val priority = todo.priority.name
                val due = todo.dueDate?.let { " [due ${java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(it))}]" } ?: ""
                "- ${todo.title} ($priority)$due"
            }
            val prompt = "Review this to-do list and suggest a prioritised order for today, with a brief reason for your top 3 picks:\n\n$list"
            CarlsBrainApp.claudeClient.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You are Carl's Brain helping prioritise his day. Be concise and practical. Carl has ADHD.",
                model = ClaudeClient.HAIKU
            ).onSuccess { reply ->
                _prioritisationResult.value = reply
            }.onFailure { e ->
                _prioritisationResult.value = "Could not prioritise: ${e.message}"
            }
            _isPrioritising.value = false
        }
    }

    fun onPriorityFilterSelected(priority: Priority?) { _selectedPriority.value = priority }
    fun onBucketFilterSelected(bucketId: Long?) { _selectedBucketId.value = bucketId }

    fun setSortMode(mode: TodoSortMode) {
        viewModelScope.launch { prefs.setTodosSortMode(mode.name) }
    }

    fun toggleDone(todoId: Long, isDone: Boolean) {
        viewModelScope.launch {
            db.todoDao().setTodoDone(todoId, isDone)
            if (isDone) {
                val entity = db.todoDao().getTodoById(todoId) ?: return@launch
                val recurrence = Recurrence.fromStorageString(entity.recurrence)
                if (recurrence != Recurrence.None) spawnNextRecurrence(entity, recurrence)
            }
        }
    }

    fun toggleSubtask(subtaskId: Long, isDone: Boolean) {
        viewModelScope.launch {
            val target = subtasksMap.value.values.flatten().find { it.id == subtaskId } ?: return@launch
            db.subtaskDao().updateSubtask(target.copy(isDone = isDone))
        }
    }

    fun reorderTodo(fromIndex: Int, toIndex: Int) {
        viewModelScope.launch {
            val current = todos.value.toMutableList()
            if (fromIndex !in current.indices || toIndex !in current.indices) return@launch
            val moved = current.removeAt(fromIndex)
            current.add(toIndex, moved)
            current.forEachIndexed { index, todo ->
                db.todoDao().updateSortOrder(todo.id, index)
            }
        }
    }

    fun archiveTodo(todoId: Long) {
        viewModelScope.launch { db.todoDao().archiveTodo(todoId) }
    }

    fun pinTodo(todoId: Long, isPinned: Boolean) {
        viewModelScope.launch { db.todoDao().updateIsPinned(todoId, isPinned) }
    }

    private suspend fun spawnNextRecurrence(entity: TodoEntity, recurrence: Recurrence) {
        val nextDue = nextDateMs(entity.dueDate, recurrence) ?: return
        val intervalMs = nextDue - (entity.dueDate ?: System.currentTimeMillis())
        val nextReminder = entity.reminderAt?.let { it + intervalMs }
        val newId = db.todoDao().insertTodo(
            entity.copy(
                id = 0, dueDate = nextDue, reminderAt = nextReminder,
                isDone = false, isArchived = false, archivedAt = null,
                createdAt = System.currentTimeMillis(), updatedAt = System.currentTimeMillis(),
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
            is Recurrence.Fortnightly -> from + TimeUnit.DAYS.toMillis(14)
            is Recurrence.Monthly -> Calendar.getInstance().apply {
                timeInMillis = from; add(Calendar.MONTH, 1)
            }.timeInMillis
            is Recurrence.Custom -> from + TimeUnit.DAYS.toMillis(recurrence.intervalDays.toLong())
            else -> null
        }
    }
}
