package com.carlmanning.carlsbrain.ui.screens.chat

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val content: String,
    val isFromUser: Boolean
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

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var memoryMd: String = DriveRepository.INITIAL_MEMORY
    private val apiHistory = mutableListOf<ApiMessage>()

    init {
        loadMemory()
    }

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

        val userMessage = ChatMessage(content = text, isFromUser = true)
        apiHistory.add(ApiMessage(role = "user", content = text))

        _uiState.update { state ->
            state.copy(messages = state.messages + userMessage, inputText = "", isLoading = true)
        }

        viewModelScope.launch {
            claude.chat(
                messages = apiHistory.toList(),
                systemPrompt = buildSystemPrompt()
            ).fold(
                onSuccess = { reply ->
                    apiHistory.add(ApiMessage(role = "assistant", content = reply))
                    val assistantMessage = ChatMessage(content = reply, isFromUser = false)
                    _uiState.update { state ->
                        state.copy(messages = state.messages + assistantMessage, isLoading = false)
                    }
                },
                onFailure = { e ->
                    val errorMessage = ChatMessage(
                        content = "Error: ${e.message}",
                        isFromUser = false
                    )
                    apiHistory.removeLastOrNull()
                    _uiState.update { state ->
                        state.copy(messages = state.messages + errorMessage, isLoading = false)
                    }
                }
            )
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

        ## Carl's Memory
        $memoryMd
    """.trimIndent()
}
