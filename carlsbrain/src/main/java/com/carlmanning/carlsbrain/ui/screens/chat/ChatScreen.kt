package com.carlmanning.carlsbrain.ui.screens.chat

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.LinkifyText
import com.carlmanning.carlsbrain.ui.components.MarkdownText

@Composable
fun ChatScreen(
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    chatViewModel: ChatViewModel = viewModel()
) {
    val uiState by chatViewModel.uiState.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) chatViewModel.startListening() }

    fun onMicClick() {
        if (uiState.isListening) {
            chatViewModel.stopListening()
        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            chatViewModel.startListening()
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    LaunchedEffect(uiState.messages.size) {
        if (uiState.messages.isNotEmpty()) {
            listState.animateScrollToItem(uiState.messages.lastIndex)
        }
    }

    LaunchedEffect(uiState.voiceError) {
        val msg = uiState.voiceError
        if (msg != null) {
            snackbarHostState.showSnackbar(msg)
            chatViewModel.consumeVoiceError()
        }
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Chat",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding()
        ) {
            if (uiState.messages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Ask me anything about your notes, todos, or plans",
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.padding(24.dp)
                    )
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(uiState.messages, key = { it.id }) { message ->
                        ChatBubble(message = message)
                    }
                }
            }

            val isListening = uiState.isListening
            val isSpeakingEnabled = uiState.isSpeakingEnabled
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = when {
                        isListening && uiState.partialText.isNotBlank() -> uiState.partialText
                        isListening -> "Listening…"
                        else -> uiState.inputText
                    },
                    onValueChange = chatViewModel::onInputTextChange,
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Claude…") },
                    maxLines = 4,
                    enabled = !isListening,
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    )
                )
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(
                    onClick = { chatViewModel.toggleSpeaking() },
                    enabled = !uiState.isLoading
                ) {
                    Icon(
                        imageVector = if (isSpeakingEnabled) Icons.Filled.VolumeUp else Icons.Filled.VolumeOff,
                        contentDescription = if (isSpeakingEnabled) "Voice responses on" else "Voice responses off",
                        tint = if (isSpeakingEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = { onMicClick() },
                    enabled = !uiState.isLoading
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (isListening) "Stop listening" else "Voice input",
                        tint = if (isListening) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(
                    onClick = chatViewModel::sendMessage,
                    enabled = uiState.inputText.isNotBlank() && !uiState.isLoading && !isListening
                ) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
                }
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isFromUser) Arrangement.End else Arrangement.Start
    ) {
        Column(horizontalAlignment = if (message.isFromUser) Alignment.End else Alignment.Start) {
            Card(
                modifier = Modifier.widthIn(max = 300.dp),
                colors = CardDefaults.cardColors(
                    containerColor = if (message.isFromUser) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                if (message.isFromUser) {
                    LinkifyText(
                        text = message.content,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                } else {
                    MarkdownText(
                        text = message.content,
                        modifier = Modifier.padding(12.dp)
                    )
                }
            }
            if (message.createdTodoTitles.isNotEmpty()) {
                val count = message.createdTodoTitles.size
                Text(
                    text = "✓ Added $count to-do${if (count > 1) "s" else ""} to your list",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
            if (message.createdNoteTitles.isNotEmpty()) {
                val count = message.createdNoteTitles.size
                Text(
                    text = "✓ Saved $count note${if (count > 1) "s" else ""}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
            if (message.completedTodoTitles.isNotEmpty()) {
                val count = message.completedTodoTitles.size
                Text(
                    text = "✓ Marked $count to-do${if (count > 1) "s" else ""} done",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.tertiary,
                    modifier = Modifier.padding(start = 4.dp, top = 2.dp)
                )
            }
        }
    }
}
