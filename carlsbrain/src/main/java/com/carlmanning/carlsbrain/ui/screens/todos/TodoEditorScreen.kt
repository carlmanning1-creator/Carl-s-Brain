package com.carlmanning.carlsbrain.ui.screens.todos

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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.components.AiProgressLabel
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.ConfirmDeleteDialog
import com.carlmanning.carlsbrain.ui.components.PulsingMicButton
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.util.formatSmartDueDateTime
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditorScreen(
    todoId: Long,
    onNavigateBack: () -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    onOpenMeeting: (Long) -> Unit = {},
    viewModel: TodoEditorViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val subtasks by viewModel.subtasks.collectAsStateWithLifecycle()
    val cachedPhotos by viewModel.cachedPhotos.collectAsStateWithLifecycle()
    val context = LocalContext.current

    var viewingAttachment by remember { mutableStateOf<String?>(null) }

    // Local vals to avoid smart-cast failures on delegated properties
    val dueDate = uiState.dueDate
    val reminderAt = uiState.reminderAt
    val recurrence = uiState.recurrence

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startListening() }

    val attachmentPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addAttachment(uri)
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.addAttachment(uri)
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var showCalendarStartPicker by remember { mutableStateOf(false) }
    var showCalendarStartTimePicker by remember { mutableStateOf(false) }
    var showCalendarEndTimePicker by remember { mutableStateOf(false) }
    var pendingCalendarStartMs by remember { mutableStateOf<Long?>(null) }
    var pendingCalendarStartFull by remember { mutableStateOf<Long?>(null) }
    val calendarStartDateState = rememberDatePickerState(initialSelectedDateMillis = System.currentTimeMillis())
    val calendarStartTimeState = rememberTimePickerState(initialHour = 9, initialMinute = 0, is24Hour = true)
    val calendarEndTimeState = rememberTimePickerState(initialHour = 10, initialMinute = 0, is24Hour = true)
    var showDatePicker by remember { mutableStateOf(false) }
    var showDueTimePicker by remember { mutableStateOf(false) }
    var pendingDueDateMs by remember { mutableStateOf<Long?>(null) }
    val dueTimeState = rememberTimePickerState(
        initialHour = Calendar.getInstance().apply { timeInMillis = dueDate ?: System.currentTimeMillis() }.get(Calendar.HOUR_OF_DAY),
        initialMinute = Calendar.getInstance().apply { timeInMillis = dueDate ?: System.currentTimeMillis() }.get(Calendar.MINUTE),
        is24Hour = true
    )
    var customDaysText by remember { mutableStateOf(
        (recurrence as? Recurrence.Custom)?.intervalDays?.toString() ?: ""
    ) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate)

    // Reminder pickers: date first, then time
    var showReminderDatePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var pendingReminderDateMs by remember { mutableStateOf<Long?>(null) }
    val reminderDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = reminderAt ?: dueDate ?: System.currentTimeMillis()
    )
    val reminderCal = if (reminderAt != null) Calendar.getInstance().apply { timeInMillis = reminderAt } else null
    val reminderTimeState = rememberTimePickerState(
        initialHour = reminderCal?.get(Calendar.HOUR_OF_DAY) ?: 9,
        initialMinute = reminderCal?.get(Calendar.MINUTE) ?: 0,
        is24Hour = true
    )

    // "More" section expanded state
    var moreExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(todoId) { viewModel.loadTodo(todoId) }

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

    if (showDeleteDialog) {
        ConfirmDeleteDialog(
            itemType = "to-do",
            isRecoverable = true,
            onConfirm = { viewModel.delete(onNavigateBack) },
            onDismiss = { showDeleteDialog = false }
        )
    }

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingDueDateMs = datePickerState.selectedDateMillis
                    showDatePicker = false
                    showDueTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Due date: time picker (step 2)
    if (showDueTimePicker) {
        AlertDialog(
            onDismissRequest = { showDueTimePicker = false },
            title = { Text("Set time (optional)") },
            text = { TimePicker(state = dueTimeState) },
            confirmButton = {
                TextButton(onClick = {
                    val dateMs = pendingDueDateMs ?: System.currentTimeMillis()
                    val combined = Calendar.getInstance().apply {
                        timeInMillis = dateMs
                        set(Calendar.HOUR_OF_DAY, dueTimeState.hour)
                        set(Calendar.MINUTE, dueTimeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    viewModel.onDueDateChange(combined)
                    showDueTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = {
                    // Save date-only at midnight
                    viewModel.onDueDateChange(pendingDueDateMs)
                    showDueTimePicker = false
                }) { Text("No time") }
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
        ) {
            DatePicker(state = reminderDatePickerState)
        }
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

    // Add to Calendar: date picker
    if (showCalendarStartPicker) {
        DatePickerDialog(
            onDismissRequest = { showCalendarStartPicker = false },
            confirmButton = {
                TextButton(onClick = {
                    pendingCalendarStartMs = calendarStartDateState.selectedDateMillis
                    showCalendarStartPicker = false
                    showCalendarStartTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarStartPicker = false }) { Text("Cancel") }
            }
        ) { DatePicker(state = calendarStartDateState) }
    }

    // Add to Calendar: start time picker
    if (showCalendarStartTimePicker) {
        AlertDialog(
            onDismissRequest = { showCalendarStartTimePicker = false },
            title = { Text("Start time") },
            text = { TimePicker(state = calendarStartTimeState) },
            confirmButton = {
                TextButton(onClick = {
                    val dateMs = pendingCalendarStartMs ?: System.currentTimeMillis()
                    pendingCalendarStartFull = Calendar.getInstance().apply {
                        timeInMillis = dateMs
                        set(Calendar.HOUR_OF_DAY, calendarStartTimeState.hour)
                        set(Calendar.MINUTE, calendarStartTimeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    showCalendarStartTimePicker = false
                    showCalendarEndTimePicker = true
                }) { Text("Next") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarStartTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    // Add to Calendar: end time picker
    if (showCalendarEndTimePicker) {
        AlertDialog(
            onDismissRequest = { showCalendarEndTimePicker = false },
            title = { Text("End time") },
            text = { TimePicker(state = calendarEndTimeState) },
            confirmButton = {
                TextButton(onClick = {
                    val startMs = pendingCalendarStartFull ?: return@TextButton
                    val endMs = Calendar.getInstance().apply {
                        timeInMillis = startMs
                        set(Calendar.HOUR_OF_DAY, calendarEndTimeState.hour)
                        set(Calendar.MINUTE, calendarEndTimeState.minute)
                        set(Calendar.SECOND, 0)
                        set(Calendar.MILLISECOND, 0)
                    }.timeInMillis
                    viewModel.addToCalendar(startMs, endMs)
                    showCalendarEndTimePicker = false
                }) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { showCalendarEndTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Edit To-Do",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateBack = onNavigateBack,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(
                            Icons.Filled.Delete,
                            contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error
                        )
                    }
                },
                overflowMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Share") },
                        leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                        onClick = {
                            dismiss()
                            val shareText = buildString {
                                appendLine(uiState.title)
                                if (uiState.dueDate != null) appendLine("Due: ${formatDueDate(uiState.dueDate!!)}")
                                subtasks.forEach { s ->
                                    appendLine("  [${if (s.isDone) "x" else " "}] ${s.title}")
                                }
                            }
                            val intent = android.content.Intent(android.content.Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(android.content.Intent.EXTRA_TEXT, shareText.trim())
                            }
                            context.startActivity(android.content.Intent.createChooser(intent, "Share to-do"))
                        }
                    )
                }
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(
                    onClick = onNavigateBack,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Cancel")
                }
                Button(
                    onClick = { viewModel.save(onNavigateBack) },
                    modifier = Modifier.weight(2f)
                ) {
                    Text("Save")
                }
            }
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
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Spacer(Modifier.height(4.dp))

                val isListening = uiState.isListening
                val interimText = uiState.interimText
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    OutlinedTextField(
                        value = if (isListening && interimText.isNotBlank()) interimText else uiState.title,
                        onValueChange = viewModel::onTitleChange,
                        modifier = Modifier.weight(1f),
                        label = { Text("Title") },
                        singleLine = true,
                        placeholder = { if (isListening) Text("Listening…") }
                    )
                    Spacer(Modifier.width(8.dp))
                    PulsingMicButton(
                        isListening = isListening,
                        onClick = {
                            if (isListening) {
                                viewModel.stopListening()
                            } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                                == PackageManager.PERMISSION_GRANTED) {
                                viewModel.startListening()
                            } else {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            }
                        },
                        size = 40.dp
                    )
                }

                // Provenance — link back to the meeting this todo came from (item #10)
                val sourceMeetingId = uiState.sourceMeetingId
                val sourceMeetingTitle = uiState.sourceMeetingTitle
                if (sourceMeetingId != null && sourceMeetingTitle != null) {
                    SuggestionChip(
                        onClick = { onOpenMeeting(sourceMeetingId) },
                        label = { Text("From: $sourceMeetingTitle", style = MaterialTheme.typography.labelSmall) },
                        icon = { Icon(Icons.Filled.Link, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                }

                // Priority — segmented button row
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Priority", style = MaterialTheme.typography.labelLarge)
                    val priorityEntries = Priority.entries
                    SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                        priorityEntries.forEachIndexed { index, priority ->
                            SegmentedButton(
                                selected = uiState.priority == priority,
                                onClick = { viewModel.onPriorityChange(priority) },
                                shape = SegmentedButtonDefaults.itemShape(
                                    index = index,
                                    count = priorityEntries.size
                                ),
                                label = { Text(priority.displayName) }
                            )
                        }
                    }
                }

                // Schedule card — due date + reminder
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Due date row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.CalendarToday,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("Due", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (dueDate != null) {
                                InputChip(
                                    selected = true,
                                    onClick = { showDatePicker = true },
                                    label = { Text(formatDueDate(dueDate)) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { viewModel.onDueDateChange(null) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear",
                                                modifier = Modifier.padding(2.dp)
                                            )
                                        }
                                    }
                                )
                            } else {
                                TextButton(onClick = { showDatePicker = true }) {
                                    Text("Tap to set")
                                }
                            }
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        // Reminder row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Icon(
                                    Icons.Filled.Alarm,
                                    contentDescription = null,
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text("Reminder", style = MaterialTheme.typography.bodyMedium)
                            }
                            if (reminderAt != null) {
                                InputChip(
                                    selected = true,
                                    onClick = { showReminderDatePicker = true },
                                    label = { Text(formatReminderDateTime(reminderAt)) },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { viewModel.onReminderChange(null) },
                                            modifier = Modifier.size(18.dp)
                                        ) {
                                            Icon(
                                                Icons.Filled.Close,
                                                contentDescription = "Clear",
                                                modifier = Modifier.padding(2.dp)
                                            )
                                        }
                                    }
                                )
                            } else {
                                TextButton(onClick = { showReminderDatePicker = true }) {
                                    Text("Tap to set")
                                }
                            }
                        }
                    }
                }

                // Repeat card — recurrence chips + custom interval + lead days
                ElevatedCard(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("Repeat", style = MaterialTheme.typography.labelLarge)
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            listOf(
                                "None" to Recurrence.None,
                                "Daily" to Recurrence.Daily,
                                "Weekly" to Recurrence.Weekly,
                                "Fortnightly" to Recurrence.Fortnightly,
                                "Monthly" to Recurrence.Monthly
                            ).forEach { (label, value) ->
                                FilterChip(
                                    selected = recurrence == value,
                                    onClick = { viewModel.onRecurrenceChange(value) },
                                    label = { Text(label) }
                                )
                            }
                            FilterChip(
                                selected = recurrence is Recurrence.Custom,
                                onClick = {
                                    viewModel.onRecurrenceChange(
                                        Recurrence.Custom(customDaysText.toIntOrNull() ?: 1)
                                    )
                                },
                                label = { Text("Custom") }
                            )
                        }
                        if (recurrence is Recurrence.Custom) {
                            OutlinedTextField(
                                value = customDaysText,
                                onValueChange = { v ->
                                    customDaysText = v.filter { it.isDigit() }
                                    viewModel.onRecurrenceChange(
                                        Recurrence.Custom(customDaysText.toIntOrNull() ?: 1)
                                    )
                                },
                                label = { Text("Every N days") },
                                singleLine = true,
                                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                    keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                ),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        // Lead-day reminder — only shown when a recurrence is set
                        if (uiState.recurrence != Recurrence.None) {
                            val leadOptions = listOf(
                                0 to "On due date",
                                1 to "1 day before",
                                3 to "3 days before",
                                7 to "7 days before",
                                14 to "14 days before"
                            )
                            var leadExpanded by remember { mutableStateOf(false) }
                            val selectedLabel = leadOptions.firstOrNull { it.first == uiState.leadDays }?.second ?: "On due date"
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Remind me", style = MaterialTheme.typography.labelLarge)
                                ExposedDropdownMenuBox(
                                    expanded = leadExpanded,
                                    onExpandedChange = { leadExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = selectedLabel,
                                        onValueChange = {},
                                        readOnly = true,
                                        trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = leadExpanded) },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(MenuAnchorType.PrimaryNotEditable)
                                    )
                                    ExposedDropdownMenu(
                                        expanded = leadExpanded,
                                        onDismissRequest = { leadExpanded = false }
                                    ) {
                                        leadOptions.forEach { (days, label) ->
                                            DropdownMenuItem(
                                                text = { Text(label) },
                                                onClick = {
                                                    viewModel.onLeadDaysChange(days)
                                                    leadExpanded = false
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // Bucket — horizontal scrollable LazyRow of chips
                if (buckets.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bucket", style = MaterialTheme.typography.labelLarge)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            contentPadding = PaddingValues(horizontal = 2.dp)
                        ) {
                            items(buckets) { bucket ->
                                FilterChip(
                                    selected = uiState.selectedBucketId == bucket.id ||
                                        (uiState.selectedBucketId == 0L && buckets.firstOrNull()?.id == bucket.id),
                                    onClick = { viewModel.onBucketChange(bucket.id) },
                                    label = { Text(bucket.name) }
                                )
                            }
                        }
                    }
                }

                // Subtasks
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Subtasks", style = MaterialTheme.typography.labelLarge)

                    // Task decomposition — the ADHD "can't start" escape hatch.
                    if (uiState.isDecomposing) {
                        AiProgressLabel(
                            label = "Claude is breaking this down…",
                            modifier = Modifier.padding(vertical = 4.dp)
                        )
                    } else {
                        OutlinedButton(
                            onClick = { viewModel.decomposeWithClaude() },
                            enabled = uiState.id != 0L && uiState.title.isNotBlank()
                        ) {
                            Icon(
                                Icons.Filled.AutoAwesome,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(if (subtasks.isEmpty()) "Break into steps" else "Suggest more steps")
                        }
                    }
                    uiState.decomposeMessage?.let { message ->
                        LaunchedEffect(message) {
                            kotlinx.coroutines.delay(4000)
                            viewModel.clearDecomposeMessage()
                        }
                        Text(
                            text = message,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (message.startsWith("Added"))
                                MaterialTheme.colorScheme.primary
                            else
                                MaterialTheme.colorScheme.error
                        )
                    }

                    subtasks.forEach { subtask ->
                        SubtaskRow(
                            subtask = subtask,
                            onToggle = { viewModel.toggleSubtask(subtask) },
                            onDelete = { viewModel.deleteSubtask(subtask) }
                        )
                    }
                    var newSubtaskText by remember { mutableStateOf("") }
                    OutlinedTextField(
                        value = newSubtaskText,
                        onValueChange = { newSubtaskText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Add subtask…") },
                        singleLine = true,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    viewModel.addSubtask(newSubtaskText)
                                    newSubtaskText = ""
                                },
                                enabled = newSubtaskText.isNotBlank() && uiState.id != 0L
                            ) {
                                Icon(Icons.Filled.Add, contentDescription = "Add")
                            }
                        },
                        supportingText = if (uiState.id == 0L) {
                            { Text("Save the to-do first to add subtasks") }
                        } else null
                    )
                }

                // "More" expandable section — Attachments + Add to Calendar
                Column(verticalArrangement = Arrangement.spacedBy(0.dp)) {
                    // Header row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { moreExpanded = !moreExpanded }
                            .padding(vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            "More",
                            style = MaterialTheme.typography.labelLarge,
                            modifier = Modifier.weight(1f)
                        )
                        // Collapsed summary: estimate first, it's the more useful signal.
                        val collapsedSummary = when {
                            uiState.estimateMinutes != null ->
                                formatEstimate(uiState.estimateMinutes!!)
                            uiState.attachments.isNotEmpty() ->
                                "${uiState.attachments.size} attachment${if (uiState.attachments.size == 1) "" else "s"}"
                            else -> null
                        }
                        if (!moreExpanded && collapsedSummary != null) {
                            Text(
                                collapsedSummary,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(end = 4.dp)
                            )
                        }
                        Icon(
                            imageVector = if (moreExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = if (moreExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    if (moreExpanded) {
                        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            // How long — quick picks only, so there's no deciding between 22 and 25.
                            // Feeds the Dashboard's "what fits right now" section.
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("How long will it take?", style = MaterialTheme.typography.labelLarge)
                                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    FilterChip(
                                        selected = uiState.estimateMinutes == null,
                                        onClick = { viewModel.onEstimateChange(null) },
                                        label = { Text("None") }
                                    )
                                    ESTIMATE_OPTIONS.forEach { minutes ->
                                        FilterChip(
                                            selected = uiState.estimateMinutes == minutes,
                                            onClick = { viewModel.onEstimateChange(minutes) },
                                            label = { Text(formatEstimate(minutes)) }
                                        )
                                    }
                                }
                            }

                            // Attachments
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Attachments", style = MaterialTheme.typography.labelLarge)
                                if (uiState.attachments.isNotEmpty()) {
                                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        items(uiState.attachments) { fileId ->
                                            val bitmap = cachedPhotos[fileId]
                                            Box(
                                                modifier = Modifier
                                                    .size(80.dp)
                                                    .clip(RoundedCornerShape(8.dp))
                                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                                                    .clickable(enabled = bitmap != null) { viewingAttachment = fileId }
                                            ) {
                                                if (bitmap != null) {
                                                    Image(
                                                        bitmap = bitmap.asImageBitmap(),
                                                        contentDescription = "Attachment",
                                                        contentScale = ContentScale.Crop,
                                                        modifier = Modifier.fillMaxSize()
                                                    )
                                                } else {
                                                    Icon(
                                                        Icons.AutoMirrored.Filled.InsertDriveFile,
                                                        contentDescription = "File",
                                                        modifier = Modifier
                                                            .size(32.dp)
                                                            .align(Alignment.Center),
                                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                }
                                                Box(
                                                    modifier = Modifier
                                                        .size(22.dp)
                                                        .align(Alignment.TopEnd)
                                                        .padding(2.dp)
                                                        .clip(CircleShape)
                                                        .background(MaterialTheme.colorScheme.errorContainer)
                                                        .clickable { viewModel.removeAttachment(fileId) },
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Icon(
                                                        Icons.Filled.Close,
                                                        contentDescription = "Remove",
                                                        modifier = Modifier.size(12.dp),
                                                        tint = MaterialTheme.colorScheme.onErrorContainer
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                                if (uiState.isUploadingAttachment) {
                                    CircularProgressIndicator(modifier = Modifier.size(32.dp))
                                } else {
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        OutlinedButton(
                                            onClick = { attachmentPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) },
                                            enabled = uiState.id != 0L
                                        ) {
                                            Icon(
                                                Icons.Filled.AddPhotoAlternate,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("Photo")
                                        }
                                        OutlinedButton(
                                            onClick = { filePicker.launch("*/*") },
                                            enabled = uiState.id != 0L
                                        ) {
                                            Icon(
                                                Icons.Filled.AttachFile,
                                                contentDescription = null,
                                                modifier = Modifier.size(18.dp)
                                            )
                                            Spacer(Modifier.width(6.dp))
                                            Text("File")
                                        }
                                    }
                                }
                            }

                            // Calendar
                            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("Calendar", style = MaterialTheme.typography.labelLarge)
                                OutlinedButton(onClick = { showCalendarStartPicker = true }) {
                                    Icon(
                                        Icons.Filled.CalendarToday,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(Modifier.width(6.dp))
                                    Text("Add to Calendar")
                                }
                                uiState.calendarResult?.let { result ->
                                    LaunchedEffect(result) {
                                        kotlinx.coroutines.delay(3000)
                                        viewModel.clearCalendarResult()
                                    }
                                    Text(
                                        text = result,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (result.startsWith("Failed"))
                                            MaterialTheme.colorScheme.error
                                        else
                                            MaterialTheme.colorScheme.primary
                                    )
                                }
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun SubtaskRow(
    subtask: SubtaskEntity,
    onToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onToggle, modifier = Modifier.size(36.dp)) {
            Icon(
                imageVector = if (subtask.isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                contentDescription = null,
                tint = if (subtask.isDone) MaterialTheme.colorScheme.primary
                       else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
        Text(
            text = subtask.title,
            style = MaterialTheme.typography.bodyMedium,
            textDecoration = if (subtask.isDone) TextDecoration.LineThrough else TextDecoration.None,
            color = if (subtask.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                    else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "Delete subtask",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Quick-pick estimate buckets, in minutes. Deliberately coarse — precision isn't the point. */
internal val ESTIMATE_OPTIONS = listOf(5, 15, 30, 60, 120, 240)

/** "5 min" / "1 hr" / "1 hr 30 min". Shared with the Dashboard's "what fits right now" rows. */
internal fun formatEstimate(minutes: Int): String {
    if (minutes < 60) return "$minutes min"
    val hours = minutes / 60
    val rest = minutes % 60
    val hoursLabel = "$hours hr"
    return if (rest == 0) hoursLabel else "$hoursLabel $rest min"
}

private fun formatReminderDateTime(ms: Long): String = formatSmartDueDateTime(ms)

private fun formatDueDate(dateMs: Long): String = formatSmartDueDateTime(dateMs)
