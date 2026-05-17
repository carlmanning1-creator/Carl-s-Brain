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
        }
    }

    fun archiveTodo(todoId: Long) {
        viewModelScope.launch {
            db.todoDao().archiveTodo(todoId)
        }
    }
}
