package com.carlmanning.carlsbrain.ui.screens.capture

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import com.carlmanning.carlsbrain.domain.model.Recurrence
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
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.util.NaturalDateParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    onDismiss: () -> Unit,
    initialType: CaptureType = CaptureType.TODO,
    startVoice: Boolean = false,
    viewModel: CaptureViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local vals to avoid smart-cast failures on delegated properties
    val dueDate = uiState.dueDate
    val reminderAt = uiState.reminderAt
    val recurrence = uiState.recurrence
    val isListening = uiState.isListening
    val interimText = uiState.interimText

    // Runtime permission for RECORD_AUDIO
    val audioPermissionLauncher = rememberLauncherForActivityResult(RequestPermission()) { granted ->
        if (granted) viewModel.startListening()
    }

    fun onMicClick() {
        if (isListening) {
            viewModel.stopListening()
        } else {
            val permissionStatus = ContextCompat.checkSelfPermission(
                context, Manifest.permission.RECORD_AUDIO
            )
            if (permissionStatus == PermissionChecker.PERMISSION_GRANTED) {
                viewModel.startListening()
            } else {
                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.onTypeSelected(initialType)
        if (startVoice) onMicClick()
    }

    val selectedBucket = buckets.find { it.id == uiState.selectedBucketId }
        ?: buckets.find { it.name == "Other" }
        ?: buckets.lastOrNull()

    var bucketExpanded by remember { mutableStateOf(false) }
    var showDatePicker by remember { mutableStateOf(false) }
    val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dueDate)

    var showReminderDatePicker by remember { mutableStateOf(false) }
    var showReminderTimePicker by remember { mutableStateOf(false) }
    var pendingReminderDateMs by remember { mutableStateOf<Long?>(null) }
    val reminderDatePickerState = rememberDatePickerState(
        initialSelectedDateMillis = reminderAt ?: dueDate ?: System.currentTimeMillis()
    )
    val reminderCal = reminderAt?.let { java.util.Calendar.getInstance().apply { timeInMillis = it } }
    val reminderTimeState = rememberTimePickerState(
        initialHour = reminderCal?.get(java.util.Calendar.HOUR_OF_DAY) ?: 9,
        initialMinute = reminderCal?.get(java.util.Calendar.MINUTE) ?: 0,
        is24Hour = true
    )

    var customDaysText by remember { mutableStateOf("") }
    var nlDueDateText by remember { mutableStateOf("") }
    var nlReminderText by remember { mutableStateOf("") }

    val photoPicker = rememberLauncherForActivityResult(PickVisualMedia()) { uri ->
        if (uri != null) viewModel.addPendingPhoto(uri)
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

    if (showReminderTimePicker) {
        AlertDialog(
            onDismissRequest = { showReminderTimePicker = false },
            title = { Text("Reminder time") },
            text = { TimePicker(state = reminderTimeState) },
            confirmButton = {
                TextButton(onClick = {
                    val dateMs = pendingReminderDateMs ?: System.currentTimeMillis()
                    val combined = java.util.Calendar.getInstance().apply {
                        timeInMillis = dateMs
                        set(java.util.Calendar.HOUR_OF_DAY, reminderTimeState.hour)
                        set(java.util.Calendar.MINUTE, reminderTimeState.minute)
                        set(java.util.Calendar.SECOND, 0)
                        set(java.util.Calendar.MILLISECOND, 0)
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

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(text = "Quick Capture", style = MaterialTheme.typography.titleLarge)

        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
            SegmentedButton(
                selected = uiState.captureType == CaptureType.TODO,
                onClick = { viewModel.onTypeSelected(CaptureType.TODO) },
                shape = SegmentedButtonDefaults.itemShape(index = 0, count = 2),
                label = { Text("To-Do") }
            )
            SegmentedButton(
                selected = uiState.captureType == CaptureType.NOTE,
                onClick = { viewModel.onTypeSelected(CaptureType.NOTE) },
                shape = SegmentedButtonDefaults.itemShape(index = 1, count = 2),
                label = { Text("Note") }
            )
        }

        if (uiState.captureType == CaptureType.NOTE) {
            OutlinedTextField(
                value = uiState.title,
                onValueChange = viewModel::onTitleChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Title (optional)") },
                singleLine = true
            )
        }

        OutlinedTextField(
            value = if (isListening && interimText.isNotEmpty()) interimText else uiState.text,
            onValueChange = { if (!isListening) viewModel.onTextChange(it) },
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    when {
                        isListening -> "Listening…"
                        uiState.captureType == CaptureType.NOTE -> "Write your note…"
                        else -> "What’s on your mind?"
                    }
                )
            },
            minLines = 3,
            trailingIcon = {
                MicButton(isListening = isListening, onMicClick = ::onMicClick)
            }
        )

        // Photo picker — notes only
        if (uiState.captureType == CaptureType.NOTE) {
            if (uiState.pendingPhotoUris.isNotEmpty()) {
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(uiState.pendingPhotoUris) { uri ->
                        LocalUriBitmap(uri = uri, context = context) { bitmap ->
                            Box(
                                modifier = Modifier
                                    .size(72.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(MaterialTheme.colorScheme.surfaceVariant)
                            ) {
                                if (bitmap != null) {
                                    Image(
                                        bitmap = bitmap.asImageBitmap(),
                                        contentDescription = "Photo",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.matchParentSize()
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .size(20.dp)
                                        .align(Alignment.TopEnd)
                                        .padding(2.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.errorContainer)
                                        .clickable { viewModel.removePendingPhoto(uri) },
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
            }
            OutlinedButton(
                onClick = { photoPicker.launch(PickVisualMediaRequest(PickVisualMedia.ImageOnly)) }
            ) {
                Icon(Icons.Filled.AddPhotoAlternate, contentDescription = null,
                    modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Add photo")
            }
        }

        if (buckets.isNotEmpty()) {
            ExposedDropdownMenuBox(
                expanded = bucketExpanded,
                onExpandedChange = { bucketExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedBucket?.name ?: "Other",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Bucket") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bucketExpanded) },
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
                                viewModel.onBucketSelected(bucket.id)
                                bucketExpanded = false
                            }
                        )
                    }
                }
            }
        }

        if (uiState.captureType == CaptureType.TODO) {
            Text("Priority", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Priority.entries.forEach { priority ->
                    FilterChip(
                        selected = uiState.selectedPriority == priority,
                        onClick = { viewModel.onPrioritySelected(priority) },
                        label = { Text(priority.displayName) }
                    )
                }
            }

            // Due date
            if (dueDate != null) {
                InputChip(
                    selected = true,
                    onClick = { showDatePicker = true },
                    label = { Text(formatDueDate(dueDate)) },
                    leadingIcon = {
                        Icon(Icons.Filled.CalendarToday, contentDescription = null,
                            modifier = Modifier.size(14.dp))
                    },
                    trailingIcon = {
                        IconButton(
                            onClick = { viewModel.onDueDateChange(null) },
                            modifier = Modifier.size(18.dp)
                        ) {
                            Icon(Icons.Filled.Close, contentDescription = "Clear date",
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
                        label = { Text("Due date") },
                        placeholder = { Text("tomorrow, next friday…") },
                        singleLine = true
                    )
                    IconButton(onClick = { showDatePicker = true }) {
                        Icon(Icons.Filled.CalendarToday, contentDescription = "Calendar picker",
                            modifier = Modifier.size(20.dp))
                    }
                }
                if (nlParsedDate != null) {
                    SuggestionChip(
                        onClick = {
                            viewModel.onDueDateChange(nlParsedDate)
                            nlDueDateText = ""
                        },
                        label = { Text("Set to: ${formatDueDate(nlParsedDate)}") }
                    )
                }
            }

            // Reminder
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
                            Icon(Icons.Filled.Close, contentDescription = "Clear reminder",
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
                        label = { Text("Reminder") },
                        placeholder = { Text("tomorrow, next friday…") },
                        singleLine = true
                    )
                    IconButton(onClick = { showReminderDatePicker = true }) {
                        Icon(Icons.Filled.Alarm, contentDescription = "Reminder picker",
                            modifier = Modifier.size(20.dp))
                    }
                }
                if (nlParsedReminder != null) {
                    SuggestionChip(
                        onClick = {
                            val combined = java.util.Calendar.getInstance().apply {
                                timeInMillis = nlParsedReminder
                                set(java.util.Calendar.HOUR_OF_DAY, 9)
                                set(java.util.Calendar.MINUTE, 0)
                                set(java.util.Calendar.SECOND, 0)
                                set(java.util.Calendar.MILLISECOND, 0)
                            }.timeInMillis
                            viewModel.onReminderChange(combined)
                            nlReminderText = ""
                        },
                        label = { Text("Remind: ${formatDueDate(nlParsedReminder)} 09:00") }
                    )
                }
            }

            // Recurrence
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
                    onClick = { viewModel.onRecurrenceChange(Recurrence.Custom(customDaysText.toIntOrNull() ?: 1)) },
                    label = { Text("Custom") }
                )
            }
            if (recurrence is Recurrence.Custom) {
                OutlinedTextField(
                    value = customDaysText,
                    onValueChange = { v ->
                        customDaysText = v.filter { it.isDigit() }
                        val days = customDaysText.toIntOrNull() ?: 1
                        viewModel.onRecurrenceChange(Recurrence.Custom(days))
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

        Text(
            text = "Claude will auto-sort this into the right bucket in the background.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onDismiss, enabled = !uiState.isSaving) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { viewModel.save(onDismiss) },
                enabled = uiState.text.isNotBlank() && !uiState.isSaving
            ) {
                if (uiState.isSaving) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp))
                } else {
                    Text("Save")
                }
            }
        }
    }
}

@Composable
private fun MicButton(isListening: Boolean, onMicClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center) {
        if (isListening) {
            val infiniteTransition = rememberInfiniteTransition(label = "mic_pulse")
            val alpha by infiniteTransition.animateFloat(
                initialValue = 0.3f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 600),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "mic_alpha"
            )
            CircularProgressIndicator(
                modifier = Modifier.size(36.dp),
                color = MaterialTheme.colorScheme.error.copy(alpha = alpha),
                strokeWidth = 2.dp
            )
        }
        IconButton(onClick = onMicClick) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Voice input",
                tint = if (isListening) MaterialTheme.colorScheme.error
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun LocalUriBitmap(
    uri: Uri,
    context: android.content.Context,
    content: @Composable (android.graphics.Bitmap?) -> Unit
) {
    var bitmap by remember(uri) { mutableStateOf<android.graphics.Bitmap?>(null) }
    LaunchedEffect(uri) {
        bitmap = withContext(Dispatchers.IO) {
            context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
        }
    }
    content(bitmap)
}

private fun formatReminderDateTime(ms: Long): String {
    val today = java.util.Calendar.getInstance().apply {
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
    }
    val tomorrow = (today.clone() as java.util.Calendar).apply { add(java.util.Calendar.DAY_OF_YEAR, 1) }
    val cal0 = java.util.Calendar.getInstance().apply {
        timeInMillis = ms
        set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
        set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
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
