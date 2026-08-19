package com.carlmanning.carlsbrain.ui.screens.journal

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun JournalScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: (() -> Unit)? = null,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    viewModel: JournalViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenPrivateCount.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    // Surfaces a failed prompt generation rather than leaving the button looking inert.
    LaunchedEffect(uiState.promptError) {
        uiState.promptError?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearPromptError()
        }
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Journal",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) { Snackbar(it) } }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { Spacer(Modifier.height(4.dp)) }

            // ── Composer ──────────────────────────────────────────────
            item {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        if (uiState.prompt.isNotBlank()) {
                            Text(
                                text = uiState.prompt,
                                style = MaterialTheme.typography.titleMedium,
                                fontStyle = if (uiState.isClaudePrompt) FontStyle.Italic
                                            else FontStyle.Normal
                            )
                        }

                        OutlinedTextField(
                            value = if (uiState.isListening && uiState.interimText.isNotBlank())
                                uiState.interimText else uiState.text,
                            onValueChange = viewModel::onTextChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Write whatever is there.") },
                            minLines = 4
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = if (uiState.isPrivate) Icons.Filled.Lock
                                                  else Icons.Filled.LockOpen,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(Modifier.size(6.dp))
                                Text(
                                    text = "Private",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Switch(
                                    checked = uiState.isPrivate,
                                    onCheckedChange = viewModel::onPrivateChange,
                                    modifier = Modifier.padding(start = 8.dp)
                                )
                            }
                            FilledTonalButton(
                                onClick = { viewModel.save {} },
                                enabled = uiState.text.isNotBlank() && !uiState.isSaving
                            ) { Text("Save entry") }
                        }

                        // Prompt controls. Kept below the field: the prompt is an offer, not a
                        // requirement, and Carl can ignore both and simply write.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { viewModel.generateClaudePrompt() },
                                enabled = !uiState.isGeneratingPrompt
                            ) {
                                if (uiState.isGeneratingPrompt) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                } else {
                                    Icon(
                                        Icons.Filled.AutoAwesome,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                }
                                Spacer(Modifier.size(6.dp))
                                Text("Ask Claude for a prompt")
                            }
                            if (uiState.isClaudePrompt) {
                                TextButton(onClick = { viewModel.useOwnPrompt() }) {
                                    Text("Use my prompt")
                                }
                            }
                        }
                    }
                }
            }

            // ── Past entries ──────────────────────────────────────────
            if (entries.isEmpty()) {
                item {
                    // Fixed height: EmptyState calls fillMaxSize internally, which has no
                    // bound inside a LazyColumn item and would throw at measure time.
                    EmptyState(
                        icon = Icons.Filled.AutoAwesome,
                        title = "Nothing written yet",
                        subtitle = "Entries you save appear here.",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(220.dp)
                    )
                }
            } else {
                items(entries, key = { it.id }) { entry ->
                    JournalEntryCard(
                        entry = entry,
                        onTogglePrivate = { viewModel.togglePrivate(entry) },
                        onDelete = { viewModel.deleteEntry(entry.id) }
                    )
                }
            }

            // Count only — never the entries themselves.
            if (!isVaultVisible && hiddenCount > 0) {
                item {
                    Text(
                        text = "$hiddenCount private ${if (hiddenCount == 1) "entry" else "entries"} hidden",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onVaultToggle() }
                            .padding(vertical = 12.dp)
                    )
                }
            }

            item { Spacer(Modifier.height(88.dp)) }
        }
    }
}

@Composable
private fun JournalEntryCard(
    entry: JournalEntryEntity,
    onTogglePrivate: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatSmartDateTime(entry.createdAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Row {
                    IconButton(onClick = onTogglePrivate) {
                        Icon(
                            imageVector = if (entry.isPrivate) Icons.Filled.Lock
                                          else Icons.Filled.LockOpen,
                            contentDescription = if (entry.isPrivate) "Make visible"
                                                 else "Make private",
                            modifier = Modifier.size(18.dp),
                            tint = if (entry.isPrivate) MaterialTheme.colorScheme.primary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    IconButton(onClick = onDelete) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete entry",
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // The prompt the entry was written against, kept so it still reads correctly after
            // the prompt in Settings is changed.
            if (entry.prompt.isNotBlank()) {
                Text(
                    text = entry.prompt,
                    style = MaterialTheme.typography.labelSmall,
                    fontStyle = FontStyle.Italic,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Text(text = entry.content, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
