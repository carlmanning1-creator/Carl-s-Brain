package com.carlmanning.carlsbrain.ui.screens.chat

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(
    val id: Long,
    val content: String,
    val isFromUser: Boolean
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false
)

class ChatViewModel : ViewModel() {

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    // Monotonic ID counter. Replaces System.currentTimeMillis() which collides whenever two
    // messages land in the same millisecond (very plausible for an instant Claude reply right
    // after a user send) and made LazyColumn(key = it.id) throw IllegalStateException.
    private val nextMessageId = AtomicLong(0L)

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank()) return

        val userMessage = ChatMessage(
            id = nextMessageId.incrementAndGet(),
            content = text,
            isFromUser = true
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + userMessage,
                inputText = "",
                isLoading = true
            )
        }

        // Claude API call will be wired up in a follow-up.
        val placeholderReply = ChatMessage(
            id = nextMessageId.incrementAndGet(),
            content = "Claude API integration coming soon.",
            isFromUser = false
        )
        _uiState.update { state ->
            state.copy(
                messages = state.messages + placeholderReply,
                isLoading = false
            )
        }
    }
}
