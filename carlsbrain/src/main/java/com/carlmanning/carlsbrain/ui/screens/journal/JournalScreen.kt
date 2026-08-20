package com.carlmanning.carlsbrain.ui.screens.journal

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDateTime

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun JournalScreen(
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: (() -> Unit)? = null,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    onOpenTemplateEntry: (templateId: Long?, entryId: Long?) -> Unit = { _, _ -> },
    onManageTemplates: () -> Unit = {},
    viewModel: JournalViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val entries by viewModel.entries.collectAsStateWithLifecycle()
    val hiddenCount by viewModel.hiddenPrivateCount.collectAsStateWithLifecycle()
    val cachedPhotos by viewModel.cachedPhotos.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addPhoto(it) } }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addFile(it) } }

    // Thumbnails for saved entries are fetched lazily rather than with the list query, so the
    // journal still renders instantly offline and photos fill in behind it.
    LaunchedEffect(entries) { viewModel.loadPhotosFor(entries) }

    // Sharing a private entry is allowed but never accidental — see shareEntry.
    var pendingPrivateShare by remember { mutableStateOf<JournalEntryEntity?>(null) }
    pendingPrivateShare?.let { entry ->
        AlertDialog(
            onDismissRequest = { pendingPrivateShare = null },
            title = { Text("Share a private entry?") },
            text = {
                Text(
                    "This entry is marked private. Sharing sends its text out of the app, " +
                        "which is the one thing private entries are otherwise never used for."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingPrivateShare = null
                    shareEntry(context, entry)
                }) { Text("Share anyway") }
            },
            dismissButton = {
                TextButton(onClick = { pendingPrivateShare = null }) { Text("Cancel") }
            }
        )
    }

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

                        // Templates now open their own screen rather than pasting text in
                        // here: five anchored scales and four option lists do not belong in a
                        // card, and a screen of its own is what lets a draft be kept on back.
                        if (templates.isNotEmpty()) {
                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                templates.forEach { template ->
                                    AssistChip(
                                        onClick = { onOpenTemplateEntry(template.id, null) },
                                        label = { Text(template.name) }
                                    )
                                }
                                AssistChip(
                                    onClick = onManageTemplates,
                                    label = { Text("Manage") },
                                    leadingIcon = {
                                        Icon(
                                            Icons.Filled.Tune,
                                            contentDescription = null,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                )
                            }
                        }

                        // Attachments on the draft
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextButton(
                                onClick = { photoPicker.launch("image/*") },
                                enabled = !uiState.isUploadingAttachment
                            ) {
                                Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("Photo")
                            }
                            TextButton(
                                onClick = { filePicker.launch("*/*") },
                                enabled = !uiState.isUploadingAttachment
                            ) {
                                Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.size(6.dp))
                                Text("File")
                            }
                            if (uiState.isUploadingAttachment) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp))
                            }
                        }
                        if (uiState.attachments.isNotEmpty()) {
                            AttachmentStrip(
                                attachments = uiState.attachments,
                                photos = cachedPhotos,
                                onRemove = viewModel::removeAttachment
                            )
                        }

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
                        photos = cachedPhotos,
                        onOpen = if (entry.templateId != null || entry.isDraft) {
                            { onOpenTemplateEntry(entry.templateId, entry.id) }
                        } else null,
                        onTogglePrivate = { viewModel.togglePrivate(entry) },
                        onDelete = { viewModel.deleteEntry(entry.id) },
                        onShare = {
                            if (entry.isPrivate) pendingPrivateShare = entry
                            else shareEntry(context, entry)
                        }
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
    photos: Map<String, android.graphics.Bitmap>,
    onOpen: (() -> Unit)?,
    onTogglePrivate: () -> Unit,
    onDelete: () -> Unit,
    onShare: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (onOpen != null) Modifier.clickable(onClick = onOpen) else Modifier),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Unfinished entries sit in the list as Carl asked, but must never be
                    // mistakable for a finished one at a glance.
                    if (entry.isDraft) {
                        Text(
                            text = "DRAFT",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(Modifier.size(8.dp))
                    }
                    Text(
                        text = formatSmartDateTime(entry.createdAt),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

            val attachments = entry.attachments.split(",").map { it.trim() }.filter { it.isNotBlank() }
            if (attachments.isNotEmpty()) {
                AttachmentStrip(attachments = attachments, photos = photos, onRemove = null)
            }

            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                TextButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Share")
                }
            }
        }
    }
}

/**
 * Thumbnails for a set of attachments, with an optional remove button.
 *
 * [onRemove] is null for a saved entry: removing an attachment after the fact would mean
 * editing the entry and re-syncing it, which is a separate piece of work. Nothing here
 * pretends to offer it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AttachmentStrip(
    attachments: List<String>,
    photos: Map<String, android.graphics.Bitmap>,
    onRemove: ((String) -> Unit)?
) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        attachments.forEach { entry ->
            val isFile = entry.startsWith("file:")
            val driveId = if (isFile) entry.substringAfterLast(":") else entry
            val label = if (isFile) entry.removePrefix("file:").substringBeforeLast(":") else null
            val bitmap = photos[driveId]
            Box {
                if (!isFile && bitmap != null) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Attachment",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.size(72.dp).clip(RoundedCornerShape(8.dp))
                    )
                } else {
                    AssistChip(
                        onClick = {},
                        label = { Text(label ?: "Photo", maxLines = 1) },
                        leadingIcon = {
                            Icon(
                                if (isFile) Icons.Filled.AttachFile else Icons.Filled.Image,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    )
                }
                if (onRemove != null) {
                    IconButton(
                        onClick = { onRemove(entry) },
                        modifier = Modifier.align(Alignment.TopEnd).size(20.dp)
                    ) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Remove attachment",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * Shares an entry's text through the system share sheet.
 *
 * Private entries are shareable. That is deliberate and matches Carl's decision on
 * attachments: "private" in this app means hidden from ordinary views, Claude and search — it
 * has never meant encrypted, and refusing to share one would imply a protection that does not
 * exist. The confirmation below is there so it is always a decision rather than a slip.
 *
 * Attachments are not included: the share sheet takes text here, and silently sending photos
 * with it is the kind of surprise this feature should not have.
 */
private fun shareEntry(context: android.content.Context, entry: JournalEntryEntity) {
    val body = buildString {
        appendLine(formatSmartDateTime(entry.createdAt))
        if (entry.prompt.isNotBlank()) {
            appendLine(entry.prompt)
        }
        appendLine()
        append(entry.content)
    }
    val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(android.content.Intent.EXTRA_TEXT, body)
        putExtra(android.content.Intent.EXTRA_SUBJECT, "Journal entry")
    }
    runCatching {
        context.startActivity(android.content.Intent.createChooser(intent, "Share entry"))
    }.onFailure {
        android.widget.Toast
            .makeText(context, "No app available to share to", android.widget.Toast.LENGTH_SHORT)
            .show()
    }
}
