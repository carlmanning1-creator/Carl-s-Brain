package com.carlmanning.carlsbrain.ui.screens.todos

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.domain.model.Todo
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)

    val buckets: StateFlow<Map<Long, BucketEntity>> = db.bucketDao()
        .getAllBuckets()
        .map { list -> list.associateBy { it.id } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyMap())

    val archivedTodos: StateFlow<List<Todo>> = db.todoDao()
        .getArchivedTodos()
        .map { list -> list.map { it.toDomain() } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(todoId: Long) {
        viewModelScope.launch { db.todoDao().restoreTodo(todoId) }
    }

    fun deleteForever(todo: Todo) {
        viewModelScope.launch {
            db.todoDao().getTodoById(todo.id)?.let { db.todoDao().deleteTodo(it) }
        }
    }
}
