package com.carlmanning.carlsbrain.ui.screens.capture

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

data class CaptureUiState(
    val text: String = "",
    val selectedBucketId: Long? = null,
    val selectedPriority: Priority = Priority.NORMAL,
    val isSaving: Boolean = false
)

class CaptureViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val claude = ClaudeClient(app)
    private val tagJson = Json { ignoreUnknownKeys = true }

    val buckets: StateFlow<List<BucketEntity>> = db.bucketDao()
        .getNonVaultBuckets()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _uiState = MutableStateFlow(CaptureUiState())
    val uiState: StateFlow<CaptureUiState> = _uiState.asStateFlow()

    fun onTextChange(text: String) = _uiState.update { it.copy(text = text) }

    fun onBucketSelected(bucketId: Long) = _uiState.update { it.copy(selectedBucketId = bucketId) }

    fun onPrioritySelected(priority: Priority) = _uiState.update { it.copy(selectedPriority = priority) }

    fun save(onComplete: () -> Unit) {
        val state = _uiState.value
        val text = state.text.trim()
        if (text.isBlank()) return

        viewModelScope.launch {
            _uiState.update { it.copy(isSaving = true) }

            val bucketList = buckets.value
            val bucketId = state.selectedBucketId
                ?: bucketList.find { it.name == "Other" }?.id
                ?: bucketList.lastOrNull()?.id
                ?: return@launch

            val todoId = db.todoDao().insertTodo(
                TodoEntity(
                    title = text,
                    bucketId = bucketId,
                    priority = state.selectedPriority.name
                )
            )

            _uiState.update { it.copy(text = "", selectedBucketId = null, selectedPriority = Priority.NORMAL, isSaving = false) }
            onComplete()

            autoTag(todoId, text, bucketList)
        }
    }

    private fun autoTag(todoId: Long, text: String, bucketList: List<BucketEntity>) {
        viewModelScope.launch {
            val bucketNames = bucketList.joinToString("|") { it.name }
            val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>","priority":"<one of: URGENT|HIGH|NORMAL|SOMEDAY>"}
Classify this capture: "$text""""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You classify tasks. Return only valid JSON, nothing else."
            ).onSuccess { response ->
                runCatching {
                    val tag = tagJson.decodeFromString<AutoTag>(response.trim())
                    val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                    val priority = Priority.entries.find { it.name == tag.priority.uppercase() }
                    if (bucket != null && priority != null) {
                        db.todoDao().getTodoById(todoId)?.let { existing ->
                            db.todoDao().updateTodo(
                                existing.copy(
                                    bucketId = bucket.id,
                                    priority = priority.name,
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        }
    }

    @Serializable
    private data class AutoTag(
        val bucket: String = "",
        val priority: String = "NORMAL"
    )
}
