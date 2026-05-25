package com.carlmanning.carlsbrain.ui.screens.notes

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.core.content.ContextCompat
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.material.icons.filled.FormatListBulleted
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.TaskAlt
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.MarkdownText
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteEditorScreen(
    noteId: Long,
    onNavigateBack: () -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    viewModel: NoteEditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val cachedPhotos by viewModel.cachedPhotos.collectAsStateWithLifecycle()
    val reminderAt = uiState.reminderAt
    val context = LocalContext.current

    val isListening = uiState.isListening
    val interimText = uiState.interimText

    var showDeleteDialog by remember { mutableStateOf(false) }
    var isPreviewMode by remember { mutableStateOf(false) }
    var bucketExpanded by remember { mutableStateOf(false) }
    var tagInput by remember { mutableStateOf("") }
    var viewingAttachment by remember { mutableStateOf<String?>(null) }

    // TextFieldValue for cursor-aware editing in the content field
    var contentFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(uiState.content) {
        if (contentFieldValue.text != uiState.content) {
            contentFieldValue = TextFieldValue(uiState.content, TextRange(uiState.content.length))
        }
    }

    // Reminder pickers
    var showReminderDatePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var pendingReminderDateMs by remember { mutableStateOf<Long?>(null) }
    val reminderDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = reminderAt ?: System.currentTimeMillis()
    )
    val reminderCal = if (reminderAt != null) Calendar.getInstance().apply { timeInMillis = reminderAt } else null
    val reminderTimeState = rememberTimePickerState(
        initialHour = reminderCal?.get(Calendar.HOUR_OF_DAY) ?: 9,
        initialMinute = reminderCal?.get(Calendar.MINUTE) ?: 0,
        is24Hour = true
    )

    val photoPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addPhoto(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addFile(uri)
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startListening() }

    LaunchedEffect(noteId) { viewModel.loadNote(noteId) }

    // Full-screen attachment viewer
    viewingAttachment?.let { fileId ->
        val bitmap = cachedPhotos[fileId]
        if (bitmap != null) {
            AlertDialog(
                onDismissRequest = { viewingAttachment = null },
                confirmButton = {
                    TextButton(onClick = { viewingAttachment = null }) { Text("Close") }
                },
                text = {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Attachment",
                        modifier = Modifier.fillMaxWidth(),
                        contentScale = ContentScale.FillWidth
                    )
                }
            )
        }
    }

    // Delete dialog
    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete note?") },
            text = { Text("This note will be permanently deleted.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(onNavigateBack) }) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) { Text("Cancel") }
            }
        )
    }

    // Reminder: date picker (step 1)
    if (showReminderDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showReminderDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingReminderDateMs = reminderDatePickerState.selectedDateMillis
                    showReminderDatePicker = false
                    showReminderTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showReminderDatePicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = reminderDatePickerState) }
    }

    // Reminder: time picker (step 2)
    if (showReminderTimePicker) {
        AlertDialog(
            onDismissRequest = { showReminderTimePicker = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = reminderTimeState) },
            confirmButton = {
                TextButton(onClick = {
                    val dateMs = pendingReminderDateMs ?: System.currentTimeMillis()
                    val combined = Calendar.getInstance().apply {
                        timeInMillis = dateMs
                        set(Calendar.HOUR_OF_DAY, reminderTimeState.hour)
                        set(Calendar.MINUTE, reminderTimeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    viewModel.onReminderChange(combined)
                    showReminderTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showReminderTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Note",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateBack = onNavigateBack,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    IconButton(onClick = {
                        val shareText = buildString {
                            if (uiState.title.isNotBlank()) appendLine("# ${uiState.title}\n")
                            append(uiState.content)
                        }
                        val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(android.content.Intent.EXTRA_TEXT, shareText)
                        }
                        context.startActivity(android.content.Intent.createChooser(intent, "Share note"))
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { isPreviewMode = !isPreviewMode }) {
                        Icon(
                            imageVector = if (isPreviewMode) Icons.Filled.Edit else Icons.Filled.Visibility,
                            contentDescription = if (isPreviewMode) "Edit" else "Preview"
                        )
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                    if (!isPreviewMode) {
                        IconButton(
                            onClick = { viewModel.save(onNavigateBack) },
                            enabled = uiState.content.isNotBlank()
                        ) {
                            Icon(Icons.Filled.Save, contentDescription = "Save")
                        }
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                // Scrollable body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp)
                ) {
                    // Title
                    if (isPreviewMode) {
                        if (uiState.title.isNotBlank()) {
                            Text(
                                text = uiState.title,
                                style = MaterialTheme.typography.titleLarge,
                                modifier = Modifier.padding(vertical = 8.dp)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = uiState.title,
                            onValueChange = viewModel::onTitleChange,
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text("Title (optional)") },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.titleLarge
                        )
                    }

                    // Bucket selector (edit mode only)
                    if (!isPreviewMode && buckets.isNotEmpty()) {
                        val selectedBucket = buckets.find { it.id == uiState.bucketId }
                            ?: buckets.first()
                        ExposedDropdownMenuBox(
                            expanded = bucketExpanded,
                            onExpandedChange = { bucketExpanded = it },
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = selectedBucket.name,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketExpanded)
                                },
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                textStyle = MaterialTheme.typography.labelLarge.copy(
                                    color = MaterialTheme.colorScheme.primary
                                ),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable)
                            )
                            ExposedDropdownMenu(
                                expanded = bucketExpanded,
                                onDismissRequest = { bucketExpanded = false }
                            ) {
                                buckets.forEach { bucket ->
                                    DropdownMenuItem(
                                        text = { Text(bucket.name) },
                                        onClick = {
                                            viewModel.onBucketChange(bucket.id)
                                            bucketExpanded = false
                                        }
                                    )
                                }
                            }
                        }
                    }

                    // Reminder row
                    if (!isPreviewMode) {
                        if (reminderAt != null) {
                            InputChip(
                                selected = true,
                                onClick = { showReminderDatePicker = true },
                                label = { Text(formatReminderDateTime(reminderAt)) },
                                leadingIcon = {
                                    Icon(Icons.Filled.Alarm, contentDescription = null,
                                        modifier = Modifier.size(14.dp))
                                },
                                trailingIcon = {
                                    IconButton(
                                        onClick = { viewModel.onReminderChange(null) },
                                        modifier = Modifier.size(18.dp)
                                    ) {
                                        Icon(Icons.Filled.Close, contentDescription = "Clear",
                                            modifier = Modifier.padding(2.dp))
                                    }
                                },
                                modifier = Modifier.padding(bottom = 4.dp)
                            )
                        } else {
                            TextButton(
                                onClick = { showReminderDatePicker = true },
                                modifier = Modifier.padding(bottom = 4.dp)
                            ) {
                                Icon(Icons.Filled.Alarm, contentDescription = null,
                                    modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Set reminder", style = MaterialTheme.typography.labelMedium)
                            }
                        }
                    }

                    // Tags section
                    if (!isPreviewMode) {
                        if (uiState.tags.isNotEmpty()) {
                            LazyRow(
                                modifier = Modifier.padding(bottom = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(uiState.tags) { tag ->
                                    InputChip(
                                        selected = false,
                                        onClick = {},
                                        label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) },
                                        trailingIcon = {
                                            IconButton(
                                                onClick = { viewModel.removeTag(tag) },
                                                modifier = Modifier.size(18.dp)
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove tag",
                                                    modifier = Modifier.padding(2.dp))
                                            }
                                        }
                                    )
                                }
                            }
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(bottom = 4.dp)
                        ) {
                            OutlinedTextField(
                                value = tagInput,
                                onValueChange = { tagInput = it.filter { c -> c.isLetterOrDigit() || c == '-' } },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("Add tag", style = MaterialTheme.typography.labelMedium) },
                                singleLine = true,
                                colors = OutlinedTextFieldDefaults.colors(
                                    focusedBorderColor = Color.Transparent,
                                    unfocusedBorderColor = Color.Transparent
                                ),
                                textStyle = MaterialTheme.typography.labelMedium,
                                keyboardOptions = KeyboardOptions(
                                    keyboardType = KeyboardType.Text,
                                    imeAction = ImeAction.Done
                                )
                            )
                            TextButton(
                                onClick = {
                                    viewModel.addTag(tagInput)
                                    tagInput = ""
                                },
                                enabled = tagInput.isNotBlank()
                            ) { Text("Add") }
                        }
                    } else if (uiState.tags.isNotEmpty()) {
                        LazyRow(
                            modifier = Modifier.padding(bottom = 4.dp),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            items(uiState.tags) { tag ->
                                InputChip(
                                    selected = false,
                                    onClick = {},
                                    label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                                )
                            }
                        }
                    }

                    // Attachments (photos + files)
                    if (uiState.attachments.isNotEmpty()) {
                        LazyRow(modifier = Modifier.padding(vertical = 4.dp)) {
                            items(uiState.attachments) { entry ->
                                val isFile = entry.startsWith("file:")
                                val driveId = if (isFile) entry.substringAfterLast(":") else entry
                                val fileName = if (isFile) {
                                    entry.removePrefix("file:").substringBeforeLast(":")
                                } else null
                                val bitmap = cachedPhotos[driveId]

                                if (isFile) {
                                    // File chip with name
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .height(72.dp)
                                            .widthIn(min = 72.dp, max = 120.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                    ) {
                                        Column(
                                            modifier = Modifier.fillMaxSize().padding(6.dp),
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            verticalArrangement = Arrangement.Center
                                        ) {
                                            Icon(
                                                Icons.Filled.InsertDriveFile,
                                                contentDescription = "File",
                                                modifier = Modifier.size(24.dp),
                                                tint = MaterialTheme.colorScheme.primary
                                            )
                                            Text(
                                                text = fileName ?: "file",
                                                style = MaterialTheme.typography.labelSmall,
                                                maxLines = 2,
                                                overflow = TextOverflow.Ellipsis,
                                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                            )
                                        }
                                        if (!isPreviewMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp)
                                                    .align(Alignment.TopEnd)
                                                    .padding(2.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.errorContainer)
                                                    .clickable { viewModel.removeAttachment(entry) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        }
                                    }
                                } else {
                                    // Image thumbnail
                                    Box(
                                        modifier = Modifier
                                            .padding(end = 8.dp)
                                            .size(72.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(MaterialTheme.colorScheme.surfaceVariant)
                                            .clickable(enabled = bitmap != null) { viewingAttachment = entry }
                                    ) {
                                        if (bitmap != null) {
                                            Image(
                                                bitmap = bitmap.asImageBitmap(),
                                                contentDescription = "Attachment",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                        } else {
                                            Icon(Icons.Filled.InsertDriveFile, contentDescription = "File",
                                                modifier = Modifier.size(32.dp).align(Alignment.Center),
                                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (!isPreviewMode) {
                                            Box(
                                                modifier = Modifier
                                                    .size(20.dp).align(Alignment.TopEnd).padding(2.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.errorContainer)
                                                    .clickable { viewModel.removeAttachment(entry) },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Icon(Icons.Filled.Close, contentDescription = "Remove",
                                                    modifier = Modifier.size(12.dp),
                                                    tint = MaterialTheme.colorScheme.onErrorContainer)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // Attachment buttons
                    if (!isPreviewMode) {
                        if (uiState.isUploadingPhoto) {
                            CircularProgressIndicator(modifier = Modifier.size(32.dp).padding(vertical = 4.dp))
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(
                                    onClick = { photoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
                                ) {
                                    Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Photo")
                                }
                                OutlinedButton(
                                    onClick = { filePicker.launch("*/*") }
                                ) {
                                    Icon(Icons.Filled.AttachFile, contentDescription = null,
                                        modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("File")
                                }
                            }
                        }
                    }

                    Spacer(Modifier.height(4.dp))

                    // Content area
                    if (isPreviewMode) {
                        MarkdownText(
                            text = uiState.content,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { isPreviewMode = false },
                            onCheckboxToggle = { lineIndex, nowChecked ->
                                val lines = uiState.content.lines().toMutableList()
                                if (lineIndex in lines.indices) {
                                    lines[lineIndex] = if (nowChecked)
                                        lines[lineIndex].replace(Regex("""^(- |\* )\[ \]"""), "$1[x]")
                                    else
                                        lines[lineIndex].replace(Regex("""^(- |\* )\[x\]""", RegexOption.IGNORE_CASE), "$1[ ]")
                                    viewModel.onContentChange(lines.joinToString("\n"))
                                }
                            }
                        )
                    } else {
                        OutlinedTextField(
                            value = contentFieldValue,
                            onValueChange = { newValue ->
                                contentFieldValue = newValue
                                viewModel.onContentChange(newValue.text)
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .defaultMinSize(minHeight = 240.dp),
                            placeholder = { Text("Write your note…") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color.Transparent,
                                unfocusedBorderColor = Color.Transparent
                            ),
                            textStyle = MaterialTheme.typography.bodyLarge,
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Text,
                                imeAction = ImeAction.Default,
                                capitalization = KeyboardCapitalization.Sentences
                            )
                        )
                    }

                    if (interimText.isNotBlank()) {
                        Text(
                            text = interimText,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                            modifier = Modifier.padding(horizontal = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(16.dp))
                }

                // Markdown toolbar — shown in edit mode, pinned above keyboard
                if (!isPreviewMode) {
                    HorizontalDivider()
                    MarkupToolbar(
                        isListening = isListening,
                        onInsert = { snippet ->
                            contentFieldValue = insertAtCursor(contentFieldValue, snippet)
                            viewModel.onContentChange(contentFieldValue.text)
                        },
                        onMicClick = {
                            if (isListening) {
                                viewModel.stopListening()
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startListening()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun MarkupToolbar(
    isListening: Boolean,
    onInsert: (String) -> Unit,
    onMicClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(0.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = { onMicClick() }) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop recording" else "Dictate",
                tint = if (isListening) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        IconButton(onClick = { onInsert("**bold**") }) {
            Icon(Icons.Filled.FormatBold, contentDescription = "Bold",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onInsert("*italic*") }) {
            Icon(Icons.Filled.FormatItalic, contentDescription = "Italic",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onInsert("\n- ") }) {
            Icon(Icons.Filled.FormatListBulleted, contentDescription = "Bullet list",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        IconButton(onClick = { onInsert("\n- [ ] ") }) {
            Icon(Icons.Filled.TaskAlt, contentDescription = "Checkbox",
                tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "H1",
            modifier = Modifier
                .clickable { onInsert("\n# ") }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "H2",
            modifier = Modifier
                .clickable { onInsert("\n## ") }
                .padding(horizontal = 12.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private fun insertAtCursor(fieldValue: TextFieldValue, text: String): TextFieldValue {
    val start = fieldValue.selection.start.coerceAtLeast(0)
    val end = fieldValue.selection.end.coerceAtLeast(0)
    val newText = buildString {
        append(fieldValue.text.substring(0, start.coerceAtMost(fieldValue.text.length)))
        append(text)
        append(fieldValue.text.substring(end.coerceAtMost(fieldValue.text.length)))
    }
    val newCursor = start + text.length
    return TextFieldValue(newText, TextRange(newCursor))
}

private fun formatReminderDateTime(ms: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val cal = Calendar.getInstance().apply { timeInMillis = ms }
    val cal0 = (cal.clone() as Calendar).apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val dateLabel = when (cal0.timeInMillis) {
        today.timeInMillis -> "Today"
        tomorrow.timeInMillis -> "Tomorrow"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(ms))
    }
    val timeLabel = SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(ms))
    return "$dateLabel $timeLabel"
}
