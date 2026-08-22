package com.carlmanning.carlsbrain.ui.screens.chat

import android.app.Application
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.ChatThreadEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import kotlinx.coroutines.launch

class ChatThreadListViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val chatDao = db.chatDao()
    private val drive = DriveRepository(app)

    val threads = chatDao.getAllThreads()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createThread(onCreated: (Long) -> Unit) {
        viewModelScope.launch {
            val id = chatDao.insertThread(
                ChatThreadEntity(title = "New conversation")
            )
            onCreated(id)
        }
    }

    /**
     * Deletes a conversation here and on Drive.
     *
     * Runs on the app scope, not [viewModelScope]: deleting the last thread pops this screen,
     * and the pop is what cancels viewModelScope — the Drive call would die at its first
     * suspension point and the thread would come back on the next sync.
     *
     * The tombstone is written first and unconditionally. If the Drive delete fails (offline,
     * which is the normal case for a delete made on the move), the file survives, and the
     * tombstone is the only thing that stops the pull re-creating the thread from it.
     */
    fun deleteThread(threadId: Long) {
        CarlsBrainApp.appScope.launch {
            db.tombstoneDao().insert(
                TombstoneEntity(id = threadId, type = TombstoneEntity.TYPE_CHAT)
            )
            chatDao.deleteThread(threadId)
            drive.deleteChatThread(threadId)
        }
    }
}

@Composable
fun ChatThreadListScreen(
    onOpenThread: (Long) -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: ChatThreadListViewModel = viewModel()
) {
    val threads by viewModel.threads.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Conversations",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { viewModel.createThread(onOpenThread) }) {
                Icon(Icons.Filled.Add, contentDescription = "New conversation")
            }
        }
    ) { innerPadding ->
        if (threads.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        Icons.AutoMirrored.Filled.Chat,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "No conversations yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Tap + to start a new one",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(threads, key = { it.id }) { thread ->
                    Card(
                        onClick = { onOpenThread(thread.id) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = thread.title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = formatSmartDateTime(thread.updatedAt),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.deleteThread(thread.id) }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
