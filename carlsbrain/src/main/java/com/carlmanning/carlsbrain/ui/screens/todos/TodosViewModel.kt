package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Todo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class TodosViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val buckets: StateFlow<Map<Long, BucketEntity>> = db.bucketDao()
        .getAllBuckets()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    private val _selectedPriority = MutableStateFlow<Priority?>(null)
    val selectedPriority: StateFlow<Priority?> = _selectedPriority

    val todos: StateFlow<List<Todo>> = combine(
        db.todoDao().getVisibleTodos(),
        _selectedPriority
    ) { entities, filter ->
        val all = entities.map { it.toDomain() }
        if (filter == null) all else all.filter { it.priority == filter }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onPriorityFilterSelected(priority: Priority?) {
        _selectedPriority.value = priority
    }

    fun toggleDone(todoId: Long, isDone: Boolean) {
        viewModelScope.launch {
            db.todoDao().setTodoDone(todoId, isDone)
        }
    }

    fun archiveTodo(todoId: Long) {
        viewModelScope.launch {
            db.todoDao().archiveTodo(todoId)
        }
    }
}
