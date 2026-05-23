package com.carlmanning.carlsbrain.ui.screens.settings

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
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.ui.components.BrainTopBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecentlyDeletedScreen(
    onNavigateBack: () -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    viewModel: RecentlyDeletedViewModel = viewModel()
) {
    val items by viewModel.deletedItems.collectAsStateWithLifecycle()
    var showEmptyBinDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Recently Deleted",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateBack = onNavigateBack,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    if (items.isNotEmpty()) {
                        TextButton(onClick = { showEmptyBinDialog = true }) {
                            Text("Empty Bin", color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (items.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Nothing in the bin",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
            ) {
                item {
                    Text(
                        text = "Items are permanently deleted after 90 days.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                    HorizontalDivider()
                }
                items(items, key = { item ->
                    when (item) {
                        is DeletedItem.DeletedNote -> "note-${item.entity.id}"
                        is DeletedItem.DeletedTodo -> "todo-${item.entity.id}"
                        is DeletedItem.DeletedMeeting -> "meeting-${item.entity.id}"
                    }
                }) { item ->
                    DeletedItemRow(
                        item = item,
                        daysRemaining = viewModel.daysRemaining(item.deletedAt),
                        onRestore = { viewModel.restore(item) },
                        onDeletePermanently = { viewModel.deletePermanently(item) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showEmptyBinDialog) {
        AlertDialog(
            onDismissRequest = { showEmptyBinDialog = false },
            title = { Text("Empty Bin?") },
            text = { Text("All items in the bin will be permanently deleted. This cannot be undone.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.emptyBin()
                        showEmptyBinDialog = false
                    }
                ) {
                    Text("Empty Bin", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showEmptyBinDialog = false }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun DeletedItemRow(
    item: DeletedItem,
    daysRemaining: Int,
    onRestore: () -> Unit,
    onDeletePermanently: () -> Unit
) {
    val (typeLabel, chipColor) = when (item) {
        is DeletedItem.DeletedNote -> "Note" to MaterialTheme.colorScheme.primaryContainer
        is DeletedItem.DeletedTodo -> "Todo" to MaterialTheme.colorScheme.secondaryContainer
        is DeletedItem.DeletedMeeting -> "Meeting" to MaterialTheme.colorScheme.tertiaryContainer
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            AssistChip(
                onClick = {},
                label = { Text(typeLabel, style = MaterialTheme.typography.labelSmall) },
                colors = AssistChipDefaults.assistChipColors(containerColor = chipColor)
            )
            Text(
                text = item.title.ifBlank { "(no title)" },
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = "$daysRemaining days left",
                style = MaterialTheme.typography.labelSmall,
                color = if (daysRemaining <= 7) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(onClick = onRestore) {
                Text("Restore")
            }
            OutlinedButton(
                onClick = onDeletePermanently,
                border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    MaterialTheme.colorScheme.error
                )
            ) {
                Icon(
                    Icons.Filled.DeleteForever,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(end = 4.dp)
                )
                Text("Delete Permanently", color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
