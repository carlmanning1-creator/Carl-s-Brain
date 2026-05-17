package com.carlmanning.carlsbrain.ui.screens.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val content: String,
    val isFromUser: Boolean,
    val createdTodoTitles: List<String> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val memoryLoaded: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val claude = ClaudeClient(app)
    private val drive = DriveRepository(app)
    private val db = AppDatabase.getInstance(app)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var memoryMd: String = DriveRepository.INITIAL_MEMORY
    private val apiHistory = mutableListOf<ApiMessage>()

    private val todoRegex = Regex("""\[TODO:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)

    init { loadMemory() }

    private fun loadMemory() {
        viewModelScope.launch {
            val stored = drive.getMemoryMd()
            if (stored != null) {
                memoryMd = stored
            } else {
                drive.updateMemoryMd(DriveRepository.INITIAL_MEMORY)
            }
            _uiState.update { it.copy(memoryLoaded = true) }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isLoading) return

        apiHistory.add(ApiMessage(role = "user", content = text))
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(content = text, isFromUser = true),
                inputText = "",
                isLoading = true
            )
        }

        viewModelScope.launch {
            claude.chat(
                messages = apiHistory.toList(),
                systemPrompt = buildSystemPrompt()
            ).fold(
                onSuccess = { reply ->
                    val createdTitles = parseAndCreateTodos(reply)
                    val displayReply = todoRegex.replace(reply, "").trim()

                    apiHistory.add(ApiMessage(role = "assistant", content = displayReply))
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + ChatMessage(
                                content = displayReply,
                                isFromUser = false,
                                createdTodoTitles = createdTitles
                            ),
                            isLoading = false
                        )
                    }
                    maybeUpdateMemory(userMsg = text, assistantReply = displayReply)
                },
                onFailure = { e ->
                    apiHistory.removeLastOrNull()
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + ChatMessage(
                                content = "Error: ${e.message}",
                                isFromUser = false
                            ),
                            isLoading = false
                        )
                    }
                }
            )
        }
    }

    private suspend fun parseAndCreateTodos(response: String): List<String> {
        val matches = todoRegex.findAll(response)
        val created = mutableListOf<String>()

        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
            ?: return emptyList()

        for (match in matches) {
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { continue }
            val bucketName = parts.getOrElse(1) { "Other" }
            val priorityStr = parts.getOrElse(2) { "NORMAL" }.uppercase()

            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) }
                ?: defaultBucket
            val priority = runCatching { Priority.valueOf(priorityStr) }
                .getOrDefault(Priority.NORMAL)

            db.todoDao().insertTodo(
                TodoEntity(title = title, bucketId = bucket.id, priority = priority.name)
            )
            created.add(title)
        }
        return created
    }

    private fun maybeUpdateMemory(userMsg: String, assistantReply: String) {
        viewModelScope.launch {
            val prompt = """Review this conversation exchange. Determine if it revealed new, genuinely important facts about Carl that should be permanently remembered (preferences, decisions, key life context, recurring patterns, important events).

User said: "$userMsg"
Assistant replied: "${assistantReply.take(500)}"

Current memory (tail): ...${memoryMd.takeLast(300)}

If there is something new and important to add, write it as 1-2 concise sentences.
If nothing new was revealed, respond with exactly: NONE"""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You maintain Carl's memory file. Be very selective — only capture truly important new facts. Avoid repeating what is already in memory.",
                model = ClaudeClient.HAIKU
            ).onSuccess { response ->
                val trimmed = response.trim()
                if (trimmed != "NONE" && trimmed.isNotBlank() && !trimmed.startsWith("Error")) {
                    val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    memoryMd += "\n- [$date] $trimmed"
                    drive.updateMemoryMd(memoryMd)
                }
            }
        }
    }

    fun clearConversation() {
        apiHistory.clear()
        _uiState.update { it.copy(messages = emptyList()) }
    }

    private fun buildSystemPrompt(): String = """
        You are Carl's Brain — Carl's personal AI assistant and second brain.
        You help Carl capture thoughts, manage tasks, and plan his life.
        Keep responses concise and practical. Carl has ADHD so structured,
        actionable answers work best.

        ## Creating To-Dos
        When Carl asks you to add, create, or remind him about a task, include a marker at the end of your response:
        [TODO: title | bucket | URGENT/HIGH/NORMAL/SOMEDAY]
        Valid buckets: SES, Family, Work, Personal, Other (or any bucket Carl mentions).
        You may add multiple TODO markers. They are processed silently — do not explain or mention them.

        ## Carl's Memory
        $memoryMd
    """.trimIndent()
}
