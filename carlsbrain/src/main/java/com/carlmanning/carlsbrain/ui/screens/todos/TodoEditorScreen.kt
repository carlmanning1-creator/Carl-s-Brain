package com.carlmanning.carlsbrain.ui.screens.todos

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Alarm
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.util.NaturalDateParser
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TodoEditorScreen(
    todoId: Long,
    onNavigateBack: () -> Unit,
    viewModel: TodoEditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val subtasks by viewModel.subtasks.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local vals to avoid smart-cast failures on delegated properties
    val dueDate = uiState.dueDate
    val reminderAt = uiState.reminderAt
    val recurrence = uiState.recurrence

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startListening() }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var bucketExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    var nlDueDateText by remember { mutableStateOf("") }
    var nlReminderText by remember { mutableStateOf("") }
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

    LaunchedEffect(todoId) { viewModel.loadTodo(todoId) }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete to-do?") },
            text = { Text("This to-do will be permanently deleted.") },
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

    if (showDatePicker) {
        DatePickerDialog(
            onDismissRequest = { showDatePicker = false },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.onDueDateChange(datePickerState.selectedDateMillis)
                    showDatePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(uiState.title.ifBlank { "Edit To-Do" }) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = {
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
                    }) {
                        Icon(Icons.Filled.Share, contentDescription = "Share")
                    }
                    IconButton(onClick = { showDeleteDialog = true }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Delete",
                            tint = MaterialTheme.colorScheme.error)
                    }
                    IconButton(
                        onClick = { viewModel.save(onNavigateBack) },
                        enabled = uiState.title.isNotBlank()
                    ) {
                        Icon(Icons.Filled.Save, contentDescription = "Save")
                    }
                }
            )
        }
    ) { innerPadding ->
        if (uiState.isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentAlignment = Alignment.Center) {
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
                    IconButton(onClick = {
                        if (isListening) {
                            viewModel.stopListening()
                        } else if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO)
                            == PackageManager.PERMISSION_GRANTED) {
                            viewModel.startListening()
                        } else {
                            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }) {
                        Icon(
                            imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                            contentDescription = if (isListening) "Stop recording" else "Dictate title",
                            tint = if (isListening) MaterialTheme.colorScheme.error
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                // Priority
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Priority", style = MaterialTheme.typography.labelLarge)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Priority.entries.forEach { priority ->
                            FilterChip(
                                selected = uiState.priority == priority,
                                onClick = { viewModel.onPriorityChange(priority) },
                                label = { Text(priority.displayName) }
                            )
                        }
                    }
                }

                // Due date
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Due date", style = MaterialTheme.typography.labelLarge)
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
                                    Icon(Icons.Filled.Close, contentDescription = "Clear",
                                        modifier = Modifier.padding(2.dp))
                                }
                            }
                        )
                    } else {
                        val nlParsedDate = NaturalDateParser.parse(nlDueDateText)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = nlDueDateText,
                                onValueChange = { nlDueDateText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("tomorrow, next friday, 27 june…") },
                                singleLine = true
                            )
                            IconButton(onClick = { showDatePicker = true }) {
                                Icon(Icons.Filled.CalendarToday, contentDescription = "Open calendar",
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        if (nlParsedDate != null) {
                            androidx.compose.material3.SuggestionChip(
                                onClick = {
                                    viewModel.onDueDateChange(nlParsedDate)
                                    nlDueDateText = ""
                                },
                                label = { Text("Set to: ${formatDueDate(nlParsedDate)}") }
                            )
                        }
                    }
                }

                // Reminder
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Reminder", style = MaterialTheme.typography.labelLarge)
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
                                    Icon(Icons.Filled.Close, contentDescription = "Clear",
                                        modifier = Modifier.padding(2.dp))
                                }
                            }
                        )
                    } else {
                        val nlParsedReminder = NaturalDateParser.parse(nlReminderText)
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            OutlinedTextField(
                                value = nlReminderText,
                                onValueChange = { nlReminderText = it },
                                modifier = Modifier.weight(1f),
                                placeholder = { Text("tomorrow, next friday, 27 june…") },
                                singleLine = true
                            )
                            IconButton(onClick = { showReminderDatePicker = true }) {
                                Icon(Icons.Filled.Alarm, contentDescription = "Open date picker",
                                    modifier = Modifier.size(20.dp))
                            }
                        }
                        if (nlParsedReminder != null) {
                            androidx.compose.material3.SuggestionChip(
                                onClick = {
                                    // Default to 9:00 AM on the parsed date
                                    val combined = Calendar.getInstance().apply {
                                        timeInMillis = nlParsedReminder
                                        set(Calendar.HOUR_OF_DAY, 9)
                                        set(Calendar.MINUTE, 0)
                                        set(Calendar.SECOND, 0)
                                        set(Calendar.MILLISECOND, 0)
                                    }.timeInMillis
                                    viewModel.onReminderChange(combined)
                                    nlReminderText = ""
                                },
                                label = { Text("Remind: ${formatDueDate(nlParsedReminder)} 09:00") }
                            )
                        }
                    }
                }

                // Recurrence
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
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
                }

                // Bucket
                if (buckets.isNotEmpty()) {
                    val selectedBucket = buckets.find { it.id == uiState.selectedBucketId }
                        ?: buckets.first()
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Bucket", style = MaterialTheme.typography.labelLarge)
                        ExposedDropdownMenuBox(
                            expanded = bucketExpanded,
                            onExpandedChange = { bucketExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = selectedBucket.name,
                                onValueChange = {},
                                readOnly = true,
                                trailingIcon = {
                                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketExpanded)
                                },
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
                }

                // Subtasks
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Subtasks", style = MaterialTheme.typography.labelLarge)
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

                Spacer(Modifier.height(8.dp))
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

private fun formatDueDate(dateMs: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val due = Calendar.getInstance().apply {
        timeInMillis = dateMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (due.timeInMillis) {
        today.timeInMillis -> "Today"
        tomorrow.timeInMillis -> "Tomorrow"
        else -> SimpleDateFormat("EEE d MMM", Locale.getDefault()).format(Date(dateMs))
    }
}
