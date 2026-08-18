package com.carlmanning.carlsbrain.ui.screens.capture

import android.Manifest
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts.PickVisualMedia
import androidx.activity.result.contract.ActivityResultContracts.RequestPermission
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.TextFieldValue
import com.carlmanning.carlsbrain.domain.model.Recurrence
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.PermissionChecker
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.components.PulsingMicButton
import com.carlmanning.carlsbrain.util.NaturalDateParser
import com.carlmanning.carlsbrain.util.formatSmartDate
import com.carlmanning.carlsbrain.util.formatSmartDueDateTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Calendar

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun CaptureScreen(
    onDismiss: () -> Unit,
    initialType: CaptureType = CaptureType.TODO,
    startVoice: Boolean = false,
    isVaultVisible: Boolean = false,
    canStartVoice: Boolean = true,
    viewModel: CaptureViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val context = LocalContext.current

    // Local vals to avoid smart-cast failures on delegated properties
    val dueDate = uiState.dueDate
    val reminderAt = uiState.reminderAt
    val recurrence = uiState.recurrence
    val isListening = uiState.isListening
    val interimText = uiState.interimText

    var captureTextFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(uiState.text) {
        if (captureTextFieldValue.text != uiState.text) {
            captureTextFieldValue = TextFieldValue(uiState.text, TextRange(uiState.text.length))
        }
    }

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

    LaunchedEffect(Unit) { viewModel.onTypeSelected(initialType) }

    // Keyed on canStartVoice so a capture launched from the tile, headset button or a
    // notification while the app is locked starts listening the moment auth succeeds,
    // rather than firing underneath the lock and expiring on the silence timeout.
    var voiceAutoStarted by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(canStartVoice) {
        if (startVoice && canStartVoice && !voiceAutoStarted) {
            voiceAutoStarted = true
            onMicClick()
        }
    }

    var savedMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        viewModel.savedToBucket.collect { bucketName ->
            savedMessage = bucketName
            kotlinx.coroutines.delay(2500)
            savedMessage = null
        }
    }

    // Save failures and voice hints both surface through the Scaffold snackbar so they
    // can never be scrolled off screen or hidden behind the keyboard.
    val snackbarHostState = remember { SnackbarHostState() }
    val errorMessage = uiState.errorMessage
    LaunchedEffect(errorMessage) {
        if (errorMessage != null) {
            snackbarHostState.showSnackbar(errorMessage)
            viewModel.consumeError()
        }
    }
    val hintMessage = uiState.hintMessage
    LaunchedEffect(hintMessage) {
        if (hintMessage != null) {
            snackbarHostState.showSnackbar(hintMessage)
            viewModel.consumeHint()
        }
    }

    val suggestedBucket = uiState.suggestedBucket
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
    var repeatExpanded by remember { mutableStateOf(false) }
    var detailsExpanded by remember { mutableStateOf(false) }

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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quick Capture") },
                navigationIcon = {
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Filled.Close, contentDescription = "Close")
                    }
                }
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            // Pinned so Save, Cancel and the mic stay under the thumb no matter how far
            // the form scrolls or how much of the screen the keyboard eats.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedButton(
                    onClick = onDismiss,
                    enabled = !uiState.isSaving,
                    modifier = Modifier.weight(1f)
                ) {
                    // maxLines stops "Cancel" breaking mid-word when the row is tight. The
                    // width below is what makes it fit; this makes wrapping impossible at any
                    // font scale rather than merely unlikely at the current one.
                    Text("Cancel", maxLines = 1)
                }
                Button(
                    onClick = { viewModel.save(onDismiss) },
                    enabled = uiState.text.isNotBlank() && !uiState.isSaving,
                    // Was 2f. Save is the wider target by design, but it did not need twice
                    // Cancel's width, and the surplus is what squeezed Cancel into wrapping.
                    modifier = Modifier.weight(1.5f)
                ) {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                    } else {
                        Text("Save", maxLines = 1)
                    }
                }
                PulsingMicButton(
                    isListening = isListening,
                    onClick = ::onMicClick,
                    // 48.dp, down from 56.dp — the mic is rarely used from this screen, so it
                    // gives its width to the text buttons. Not reduced further: 48.dp is the
                    // minimum accessible touch target, and this is the whole hit area.
                    size = 48.dp
                )
            }
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Spacer(modifier = Modifier.height(4.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(11.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    listOf("To-Do" to CaptureType.TODO, "Note" to CaptureType.NOTE).forEach { (label, type) ->
                        val isSelected = uiState.captureType == type
                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(
                                    if (isSelected) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { viewModel.onTypeSelected(type) }
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = label,
                                style = MaterialTheme.typography.labelLarge,
                                color = if (isSelected) MaterialTheme.colorScheme.onPrimary
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
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
                    value = if (isListening && interimText.isNotEmpty()) TextFieldValue(interimText) else captureTextFieldValue,
                    onValueChange = { newValue ->
                        if (!isListening) {
                            captureTextFieldValue = newValue
                            viewModel.onTextChange(newValue.text)
                        }
                    },
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
                    keyboardOptions = KeyboardOptions(
                        capitalization = KeyboardCapitalization.Sentences,
                        imeAction = ImeAction.Default
                    )
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
                                        // 48dp touch target; the badge itself stays small.
                                        Box(
                                            modifier = Modifier
                                                .size(48.dp)
                                                .align(Alignment.TopEnd)
                                                .clickable { viewModel.removePendingPhoto(uri) },
                                            contentAlignment = Alignment.TopEnd
                                        ) {
                                            Box(
                                                modifier = Modifier
                                                    .padding(2.dp)
                                                    .size(20.dp)
                                                    .clip(CircleShape)
                                                    .background(MaterialTheme.colorScheme.errorContainer),
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

                // Bucket stays on the fast path — it is core, not a detail.
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
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable)
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

                // Smart bucket suggestion chip (Feature 10)
                if (suggestedBucket != null && uiState.selectedBucketId == null) {
                    SuggestionChip(
                        onClick = { viewModel.acceptSuggestedBucket() },
                        label = { Text("Suggested bucket: ${suggestedBucket.name} — tap to use") }
                    )
                }

                if (uiState.captureType == CaptureType.TODO) {
                    // Everything optional lives behind one closed accordion, so the fast
                    // path is: speak/type → bucket → Save.
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { detailsExpanded = !detailsExpanded }
                                    .padding(horizontal = 16.dp, vertical = 14.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        Icons.Filled.Tune,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text("Details", style = MaterialTheme.typography.titleSmall)
                                }
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    val summary = buildList {
                                        if (uiState.selectedPriority != Priority.NORMAL) {
                                            add(uiState.selectedPriority.displayName)
                                        }
                                        if (dueDate != null) add(formatDueDate(dueDate))
                                        if (reminderAt != null) add("Reminder")
                                        if (recurrence != Recurrence.None) add("Repeats")
                                    }.joinToString(" · ")
                                    if (summary.isNotBlank()) {
                                        Text(
                                            summary,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Icon(
                                        if (detailsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                        contentDescription = null
                                    )
                                }
                            }
                            AnimatedVisibility(visible = detailsExpanded) {
                                Column(
                                    modifier = Modifier
                                        .padding(horizontal = 16.dp)
                                        .padding(bottom = 12.dp),
                                    verticalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Text("Priority", style = MaterialTheme.typography.labelLarge)
                                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
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
                                        ClearableValueChip(
                                            icon = Icons.Filled.CalendarToday,
                                            label = formatDueDate(dueDate),
                                            onClick = { showDatePicker = true },
                                            onClear = { viewModel.onDueDateChange(null) },
                                            clearDescription = "Clear date"
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
                                            IconButton(
                                                onClick = { showDatePicker = true },
                                                modifier = Modifier.size(48.dp)
                                            ) {
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
                                        ClearableValueChip(
                                            icon = Icons.Filled.Alarm,
                                            label = formatReminderDateTime(reminderAt),
                                            onClick = { showReminderDatePicker = true },
                                            onClear = { viewModel.onReminderChange(null) },
                                            clearDescription = "Clear reminder"
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
                                            IconButton(
                                                onClick = { showReminderDatePicker = true },
                                                modifier = Modifier.size(48.dp)
                                            ) {
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
                                    Card(modifier = Modifier.fillMaxWidth()) {
                                        Column {
                                            Row(
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .clickable { repeatExpanded = !repeatExpanded }
                                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text("Repeat", style = MaterialTheme.typography.titleSmall)
                                                Row(
                                                    verticalAlignment = Alignment.CenterVertically,
                                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                                ) {
                                                    val currentLabel = when (recurrence) {
                                                        Recurrence.None -> "None"
                                                        Recurrence.Daily -> "Daily"
                                                        Recurrence.Weekly -> "Weekly"
                                                        Recurrence.Fortnightly -> "Fortnightly"
                                                        Recurrence.Monthly -> "Monthly"
                                                        // Recurrence is a sealed hierarchy, so this
                                                        // covers every case — an else branch here
                                                        // would be unreachable, and would also stop
                                                        // the compiler flagging a new variant that
                                                        // needs a label.
                                                        is Recurrence.Custom -> "Every ${recurrence.intervalDays}d"
                                                    }
                                                    if (currentLabel != "None" && currentLabel.isNotBlank()) {
                                                        Text(
                                                            currentLabel,
                                                            style = MaterialTheme.typography.bodySmall,
                                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                                        )
                                                    }
                                                    Icon(
                                                        if (repeatExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                                        contentDescription = null
                                                    )
                                                }
                                            }
                                            AnimatedVisibility(visible = repeatExpanded) {
                                                Column(
                                                    modifier = Modifier
                                                        .padding(horizontal = 16.dp)
                                                        .padding(bottom = 12.dp)
                                                ) {
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
                                                            keyboardOptions = KeyboardOptions(
                                                                keyboardType = androidx.compose.ui.text.input.KeyboardType.Number
                                                            ),
                                                            modifier = Modifier.fillMaxWidth()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                Text(
                    text = "Claude will auto-sort this into the right bucket in the background.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))
            } // end Column

            AnimatedVisibility(
                visible = savedMessage != null,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                enter = fadeIn() + slideInVertically(),
                exit = fadeOut() + slideOutVertically()
            ) {
                savedMessage?.let { bucket ->
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shadowElevation = 4.dp
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Filled.CheckCircle,
                                contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text(
                                text = "Saved to $bucket",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }
        } // end Box
    }
}

/**
 * A set value (due date / reminder) with a clear affordance whose touch target meets the
 * 48dp minimum, even though the icon inside it stays visually small.
 */
@Composable
private fun ClearableValueChip(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    onClear: () -> Unit,
    clearDescription: String
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.secondaryContainer
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Row(
                modifier = Modifier
                    .clickable(onClick = onClick)
                    .padding(start = 14.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
            IconButton(onClick = onClear, modifier = Modifier.size(48.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = clearDescription,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
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

private fun formatReminderDateTime(ms: Long): String = formatSmartDueDateTime(ms)

private fun formatDueDate(dateMs: Long): String = formatSmartDate(dateMs)
