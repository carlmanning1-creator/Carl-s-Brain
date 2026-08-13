package com.carlmanning.carlsbrain.ui.screens.meetings

import android.content.Intent
import android.widget.Toast
import com.carlmanning.carlsbrain.data.remote.ActionItem
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.RadioButtonUnchecked
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Button
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.MarkdownText
import kotlinx.coroutines.delay
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun MeetingDetailScreen(
    meetingId: Long,
    onNavigateBack: () -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    onOpenTodo: (Long) -> Unit = {},
    viewModel: MeetingDetailViewModel = viewModel(),
    meetingViewModel: MeetingViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val meetingTodos by viewModel.meetingTodos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // A meeting filed into a vault bucket may only be exported while the vault is open.
    val canShare = !uiState.isVaultBucket || isVaultVisible

    var isEditingTitle by remember { mutableStateOf(false) }
    var showDeleteDialog by remember { mutableStateOf(false) }
    var transcriptExpanded by remember { mutableStateOf(false) }
    var showTranscriptInputDialog by remember { mutableStateOf(false) }
    var manualTranscript by remember { mutableStateOf("") }
    var isEditingTranscript by remember { mutableStateOf(false) }
    var editedTranscript by remember { mutableStateOf("") }

    // Initial load
    LaunchedEffect(meetingId) {
        viewModel.loadMeeting(meetingId)
    }

    // Poll while processing — AUDIO_ONLY, DONE, and ERROR are all terminal states
    LaunchedEffect(uiState.status) {
        if (uiState.status == "PROCESSING") {
            while (true) {
                delay(3_000)
                viewModel.loadMeeting(meetingId)
                if (viewModel.uiState.value.status != "PROCESSING") break
            }
        }
    }

    // Transcript input/re-process dialog (used for both AUDIO_ONLY entry and DONE re-process)
    if (showTranscriptInputDialog) {
        AlertDialog(
            onDismissRequest = { showTranscriptInputDialog = false },
            title = { Text(if (uiState.status == "DONE") "Re-process meeting" else "Enter transcript") },
            text = {
                OutlinedTextField(
                    value = manualTranscript,
                    onValueChange = { manualTranscript = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Paste or type transcript") },
                    minLines = 6,
                    maxLines = 12
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showTranscriptInputDialog = false
                        meetingViewModel.submitManualTranscript(uiState.id, manualTranscript)
                        manualTranscript = ""
                    },
                    enabled = manualTranscript.isNotBlank()
                ) { Text("Analyse with Claude") }
            },
            dismissButton = {
                TextButton(onClick = { showTranscriptInputDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Delete confirmation dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete meeting?") },
            text = { Text("This meeting and its recording will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    viewModel.deleteMeeting(onNavigateBack)
                }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                titleContent = {
                    if (isEditingTitle) {
                        OutlinedTextField(
                            value = uiState.editableTitle,
                            onValueChange = viewModel::onTitleChange,
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.titleLarge,
                            keyboardOptions = KeyboardOptions.Default.copy(
                                imeAction = ImeAction.Done
                            )
                        )
                    } else {
                        Text(
                            text = uiState.title.ifBlank { "Meeting" },
                            maxLines = 1,
                            style = MaterialTheme.typography.titleLarge
                        )
                    }
                },
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateBack = onNavigateBack,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    if (isEditingTitle) {
                        IconButton(onClick = {
                            viewModel.saveTitle()
                            isEditingTitle = false
                        }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Save title")
                        }
                    } else {
                        // Re-process button — available on DONE and ERROR states
                        if (uiState.status == "DONE" || uiState.status == "ERROR") {
                            IconButton(onClick = {
                                manualTranscript = uiState.transcript
                                showTranscriptInputDialog = true
                            }) {
                                Icon(
                                    Icons.Filled.Refresh,
                                    contentDescription = "Re-process with Claude"
                                )
                            }
                        }
                        if (canShare) {
                            IconButton(onClick = {
                                val shareText = buildMeetingShareText(uiState, meetingTodos)
                                val intent = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, shareText)
                                    putExtra(Intent.EXTRA_SUBJECT, uiState.title.ifBlank { "Meeting summary" })
                                }
                                val launched = runCatching {
                                    context.startActivity(
                                        Intent.createChooser(intent, "Share meeting summary")
                                    )
                                }
                                if (launched.isFailure) {
                                    Toast.makeText(context, "No app available to share to", Toast.LENGTH_SHORT).show()
                                }
                            }) {
                                Icon(
                                    imageVector = Icons.Filled.Share,
                                    contentDescription = "Share meeting summary"
                                )
                            }
                        }
                        IconButton(onClick = { isEditingTitle = true }) {
                            Icon(
                                imageVector = Icons.Filled.Edit,
                                contentDescription = "Edit title"
                            )
                        }
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        if (uiState.status == "ERROR") {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Analysis failed", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text("Could not generate summary. The transcript is saved.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Button(onClick = { meetingViewModel.retryAnalysis(uiState.id) }) { Text("Retry Analysis") }
                }
            }
            return@Scaffold
        }

        if (uiState.status == "PROCESSING") {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(
                        text = "Analysing…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            return@Scaffold
        }

        if (uiState.status == "AUDIO_ONLY") {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    modifier = Modifier.padding(horizontal = 32.dp)
                ) {
                    Text(
                        text = "Transcription unavailable",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = "Live transcription didn't capture any speech, but your audio was saved. Add a transcript to generate the summary and action items.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                    Button(onClick = { showTranscriptInputDialog = true }) {
                        Text("Enter transcript")
                    }
                }
            }
            return@Scaffold
        }

        // DONE or ERROR content
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Metadata row
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = formatRecordedAt(uiState.recordedAt),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (uiState.durationMs > 0) {
                    Text(
                        text = "·",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatDurationDetail(uiState.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider()

            // --- Action Items Section ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(title = "Action Items")
                if (uiState.pendingActionItems.size > 1) {
                    TextButton(onClick = {
                        // Approve all items in reverse order so indices stay valid
                        uiState.pendingActionItems.indices.reversed().forEach { idx ->
                            viewModel.approveActionItem(idx)
                        }
                    }) {
                        Text("Add All")
                    }
                }
            }
            if (uiState.pendingActionItems.isEmpty()) {
                Text(
                    text = "No pending action items",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    uiState.pendingActionItems.forEachIndexed { index, item ->
                        ActionItemCard(
                            item = item,
                            onApprove = { viewModel.approveActionItem(index) },
                            onReject = { viewModel.rejectActionItem(index) }
                        )
                    }
                }
            }

            // --- Actions from this meeting (to-dos created from its action items) ---
            if (meetingTodos.isNotEmpty()) {
                HorizontalDivider()
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    SectionHeader(title = "Actions from this meeting")
                    Text(
                        text = "${meetingTodos.count { it.isDone }} of ${meetingTodos.size} done",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    meetingTodos.forEach { todo ->
                        MeetingTodoCard(todo = todo, onClick = { onOpenTodo(todo.id) })
                    }
                }
            }

            HorizontalDivider()

            // --- Summary Section ---
            SectionHeader(title = "Summary")
            if (uiState.summary.isBlank()) {
                Text(
                    text = "No summary available",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                MarkdownText(
                    text = uiState.summary,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            HorizontalDivider()

            // --- Transcript Section (collapsible, editable) ---
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                SectionHeader(title = "Transcript")
                Row {
                    if (isEditingTranscript) {
                        IconButton(onClick = {
                            viewModel.saveTranscriptOnly(editedTranscript)
                            isEditingTranscript = false
                        }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Save transcript")
                        }
                        IconButton(onClick = { isEditingTranscript = false }) {
                            Icon(Icons.Filled.Close, contentDescription = "Cancel")
                        }
                    } else {
                        IconButton(onClick = {
                            editedTranscript = uiState.transcript
                            isEditingTranscript = true
                            transcriptExpanded = true
                        }) {
                            Icon(Icons.Filled.Edit, contentDescription = "Edit transcript")
                        }
                        IconButton(onClick = { transcriptExpanded = !transcriptExpanded }) {
                            Icon(
                                imageVector = if (transcriptExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = if (transcriptExpanded) "Collapse" else "Expand"
                            )
                        }
                    }
                }
            }
            if (transcriptExpanded) {
                if (isEditingTranscript) {
                    OutlinedTextField(
                        value = editedTranscript,
                        onValueChange = { editedTranscript = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Transcript") },
                        minLines = 6,
                        maxLines = 20,
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace)
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                viewModel.saveTranscriptOnly(editedTranscript)
                                isEditingTranscript = false
                            },
                            enabled = editedTranscript != uiState.transcript
                        ) { Text("Save") }
                        OutlinedButton(
                            onClick = {
                                manualTranscript = editedTranscript
                                isEditingTranscript = false
                                showTranscriptInputDialog = true
                            },
                            enabled = editedTranscript.isNotBlank()
                        ) { Text("Save & Re-process") }
                    }
                } else if (uiState.transcript.isBlank()) {
                    Text(
                        text = "No transcript recorded",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    Text(
                        text = uiState.transcript,
                        style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                    )
                }
            }

            // --- Share Section ---
            // Hidden entirely for a vault-bucketed meeting while the vault is closed,
            // so no vault content can leave the app through an export.
            if (canShare) {
                HorizontalDivider()
                SectionHeader(title = "Share")
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, uiState.summary)
                                putExtra(Intent.EXTRA_SUBJECT, uiState.title)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share summary"))
                        },
                        enabled = uiState.summary.isNotBlank()
                    ) {
                        Text("Summary")
                    }

                    OutlinedButton(
                        onClick = {
                            val shareText = buildString {
                                appendLine("## Action Items — ${uiState.title}")
                                appendLine()
                                appendLine(
                                    buildActionChecklist(uiState.pendingActionItems, meetingTodos)
                                )
                            }.trim()
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, shareText)
                                putExtra(Intent.EXTRA_SUBJECT, "Action Items — ${uiState.title}")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share action items"))
                        }
                    ) {
                        Text("Action items")
                    }

                    OutlinedButton(
                        onClick = {
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, uiState.transcript)
                                putExtra(Intent.EXTRA_SUBJECT, "${uiState.title} — Transcript")
                            }
                            context.startActivity(Intent.createChooser(intent, "Share transcript"))
                        },
                        enabled = uiState.transcript.isNotBlank()
                    ) {
                        Text("Transcript")
                    }

                    OutlinedButton(
                        onClick = {
                            if (uiState.localAudioPath.isBlank()) {
                                Toast.makeText(context, "No audio file available", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            val audioFile = File(uiState.localAudioPath)
                            if (!audioFile.exists()) {
                                Toast.makeText(context, "Audio file not found", Toast.LENGTH_SHORT).show()
                                return@OutlinedButton
                            }
                            val uri = FileProvider.getUriForFile(
                                context,
                                "${context.packageName}.fileprovider",
                                audioFile
                            )
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "audio/*"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share audio"))
                        },
                        enabled = uiState.localAudioPath.isNotBlank()
                    ) {
                        Text("Audio")
                    }

                    OutlinedButton(
                        onClick = { viewModel.shareMeetingToDrive() },
                        enabled = uiState.driveFolderId.isNotBlank() && !uiState.isSharing && uiState.summary.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Link,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (uiState.isSharing) "Sharing…" else "Drive link")
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ActionItemCard(
    item: ActionItem,
    onApprove: () -> Unit,
    onReject: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(Modifier.height(4.dp))
                AssistChip(
                    onClick = {},
                    label = {
                        Text(
                            text = item.bucket,
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                )
            }
            Row {
                IconButton(onClick = onApprove) {
                    Icon(
                        Icons.Filled.CheckCircle,
                        contentDescription = "Approve",
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(24.dp)
                    )
                }
                IconButton(onClick = onReject) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = "Reject",
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun MeetingTodoCard(
    todo: TodoEntity,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = if (todo.isDone) Icons.Filled.CheckCircle else Icons.Filled.RadioButtonUnchecked,
                contentDescription = if (todo.isDone) "Done" else "Not done",
                tint = if (todo.isDone) Color(0xFF2E7D32) else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Spacer(Modifier.width(12.dp))
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyMedium,
                color = if (todo.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                        else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * Single source of truth for the action-item checklist used by every share path:
 * pending (unapproved) items plus the to-dos already created from this meeting.
 * [todos] is already vault-filtered by the ViewModel.
 */
private fun buildActionChecklist(
    pending: List<ActionItem>,
    todos: List<TodoEntity>
): String {
    if (pending.isEmpty() && todos.isEmpty()) return "No action items"
    return buildString {
        todos.forEach { todo ->
            appendLine("- [${if (todo.isDone) "x" else " "}] ${todo.title}")
        }
        pending.forEach { item ->
            appendLine("- [ ] ${item.title} | ${item.bucket}")
        }
    }.trim()
}

/** Plain-text meeting summary for ACTION_SEND: title, date, overview, action checklist. */
private fun buildMeetingShareText(
    state: MeetingDetailUiState,
    todos: List<TodoEntity>
): String = buildString {
    appendLine(state.title.ifBlank { "Meeting" })
    appendLine(formatRecordedAt(state.recordedAt))
    appendLine()
    appendLine(state.summary.ifBlank { "No summary available" }.trim())
    appendLine()
    appendLine("## Action Items")
    appendLine(buildActionChecklist(state.pendingActionItems, todos))
}.trim()

private fun formatRecordedAt(ms: Long): String =
    SimpleDateFormat("d MMM yyyy, h:mm a", Locale.getDefault()).format(Date(ms))

private fun formatDurationDetail(ms: Long): String {
    val totalSeconds = ms / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val hours = minutes / 60
    val mins = minutes % 60
    return when {
        hours > 0 -> "${hours}h ${mins}m"
        minutes > 0 -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}
