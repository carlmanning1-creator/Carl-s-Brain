package com.carlmanning.carlsbrain.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker

private val BUCKET_COLORS = listOf(
    "#1565C0", "#2E7D32", "#E65100", "#6750A4",
    "#880E4F", "#37474F", "#B71C1C", "#F57F17",
    "#00695C", "#4527A0", "#283593", "#1B5E20",
    "#E64A19", "#AD1457", "#00838F", "#558B2F"
)

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    onNavigateToMemory: () -> Unit = {},
    onNavigateToRecentlyDeleted: () -> Unit = {},
    isVaultVisible: Boolean = false,
    viewModel: SettingsViewModel = viewModel()
) {
    val savedApiKey by viewModel.anthropicApiKey.collectAsStateWithLifecycle()
    val savedPicovoiceKey by viewModel.picovoiceAccessKey.collectAsStateWithLifecycle()
    val isGoogleConnected by viewModel.isGoogleConnected.collectAsStateWithLifecycle()
    val savedDigestHour by viewModel.morningDigestHour.collectAsStateWithLifecycle()
    val savedDigestMinute by viewModel.morningDigestMinute.collectAsStateWithLifecycle()
    val swipeToCompleteEnabled by viewModel.swipeToCompleteEnabled.collectAsStateWithLifecycle()
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()

    // Smart notification settings
    val notifMorningEnabled by viewModel.notifMorningEnabled.collectAsStateWithLifecycle()
    val notifMorningHour by viewModel.notifMorningHour.collectAsStateWithLifecycle()
    val notifMorningMinute by viewModel.notifMorningMinute.collectAsStateWithLifecycle()
    val notifMiddayEnabled by viewModel.notifMiddayEnabled.collectAsStateWithLifecycle()
    val notifMiddayHour by viewModel.notifMiddayHour.collectAsStateWithLifecycle()
    val notifMiddayMinute by viewModel.notifMiddayMinute.collectAsStateWithLifecycle()
    val notifAfternoonEnabled by viewModel.notifAfternoonEnabled.collectAsStateWithLifecycle()
    val notifAfternoonHour by viewModel.notifAfternoonHour.collectAsStateWithLifecycle()
    val notifAfternoonMinute by viewModel.notifAfternoonMinute.collectAsStateWithLifecycle()
    val notifEveningEnabled by viewModel.notifEveningEnabled.collectAsStateWithLifecycle()
    val notifEveningHour by viewModel.notifEveningHour.collectAsStateWithLifecycle()
    val notifEveningMinute by viewModel.notifEveningMinute.collectAsStateWithLifecycle()
    val notifAiEnabled by viewModel.notifAiEnabled.collectAsStateWithLifecycle()

    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var picovoiceKey by remember(savedPicovoiceKey) { mutableStateOf(savedPicovoiceKey) }
    var picovoiceKeyVisible by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAddBucketDialog by remember { mutableStateOf(false) }
    var editingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    var deletingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    val timePickerState = rememberTimePickerState(
        initialHour = savedDigestHour,
        initialMinute = savedDigestMinute,
        is24Hour = true
    )

    // Smart notification slot time pickers
    var showNotifTimePicker by remember { mutableStateOf<SmartNotificationWorker.Slot?>(null) }
    val notifMorningPickerState = rememberTimePickerState(initialHour = notifMorningHour, initialMinute = notifMorningMinute, is24Hour = true)
    val notifMiddayPickerState = rememberTimePickerState(initialHour = notifMiddayHour, initialMinute = notifMiddayMinute, is24Hour = true)
    val notifAfternoonPickerState = rememberTimePickerState(initialHour = notifAfternoonHour, initialMinute = notifAfternoonMinute, is24Hour = true)
    val notifEveningPickerState = rememberTimePickerState(initialHour = notifEveningHour, initialMinute = notifEveningMinute, is24Hour = true)

    val context = LocalContext.current

    val googleAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleAuthResult(result.data)
        }
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setWakeWordEnabled(true)
    }

    LaunchedEffect(Unit) {
        viewModel.googleAuthIntent.collect { pendingIntent ->
            googleAuthLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                        Text("Settings")
                        if (isVaultVisible) {
                            androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                            Box(
                                modifier = Modifier
                                    .size(8.dp)
                                    .background(Color(0xFFFFB300), CircleShape)
                            )
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Anthropic API ──────────────────────────────────────
            Text(
                text = "Anthropic API",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(top = 8.dp)
            )

            OutlinedTextField(
                value = apiKey,
                onValueChange = { apiKey = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Anthropic API Key") },
                placeholder = { Text("sk-ant-…") },
                visualTransformation = if (apiKeyVisible) VisualTransformation.None
                else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { apiKeyVisible = !apiKeyVisible }) {
                        Icon(
                            imageVector = if (apiKeyVisible) Icons.Filled.Visibility
                            else Icons.Filled.VisibilityOff,
                            contentDescription = if (apiKeyVisible) "Hide" else "Show"
                        )
                    }
                },
                singleLine = true
            )

            Button(
                onClick = { viewModel.saveApiKey(apiKey) },
                modifier = Modifier.fillMaxWidth(),
                enabled = apiKey != savedApiKey
            ) {
                Text("Save API Key")
            }

            HorizontalDivider()

            // ── Google Account ─────────────────────────────────────
            Text(
                text = "Google Account",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Required for Drive (notes/todos) and Calendar sync.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (isGoogleConnected) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Filled.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Text(
                        text = "Connected",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                Button(
                    onClick = { viewModel.syncFromDrive() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Sync from Drive")
                }

                OutlinedButton(
                    onClick = { viewModel.forceResyncNotes() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Sync,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Force Re-upload All Notes to Drive")
                }

                OutlinedButton(
                    onClick = { viewModel.restoreFromDrive() },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = restoreState !is RestoreState.Loading
                ) {
                    Icon(
                        imageVector = Icons.Filled.Restore,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Restore from Drive")
                }

                when (val rs = restoreState) {
                    is RestoreState.Loading -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                        Text(
                            text = "Restoring…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    is RestoreState.Success -> Text(
                        text = rs.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                    is RestoreState.Error -> Text(
                        text = rs.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    else -> Text(
                        text = "Restore notes, todos, and API key from your Drive backup.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                OutlinedButton(
                    onClick = onNavigateToMemory,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.Memory,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Edit Memory (memory.md)")
                }

                OutlinedButton(
                    onClick = onNavigateToRecentlyDeleted,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Filled.DeleteForever,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    Text("Recently Deleted (Bin)")
                }

                OutlinedButton(
                    onClick = { viewModel.disconnectGoogle() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Disconnect Google Account")
                }
            } else {
                Button(
                    onClick = { viewModel.connectGoogle() },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Connect Google Account")
                }
            }

            HorizontalDivider()

            // ── Notifications ──────────────────────────────────────
            Text(
                text = "Notifications",
                style = MaterialTheme.typography.titleMedium
            )

            // AI-generated notifications toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "AI-generated summaries",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Uses Claude Haiku to write a brief summary. Requires API key.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = notifAiEnabled,
                    onCheckedChange = { viewModel.setNotifAiEnabled(it) }
                )
            }

            // Morning slot
            NotifSlotRow(
                label = "Morning",
                description = "Top priorities + today's events",
                enabled = notifMorningEnabled,
                hour = notifMorningHour,
                minute = notifMorningMinute,
                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.MORNING, it, notifMorningHour, notifMorningMinute) },
                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.MORNING }
            )

            // Midday slot
            NotifSlotRow(
                label = "Midday",
                description = "Quick check-in: what's urgent?",
                enabled = notifMiddayEnabled,
                hour = notifMiddayHour,
                minute = notifMiddayMinute,
                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.MIDDAY, it, notifMiddayHour, notifMiddayMinute) },
                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.MIDDAY }
            )

            // Afternoon slot
            NotifSlotRow(
                label = "Afternoon",
                description = "Urgent todos with Done buttons",
                enabled = notifAfternoonEnabled,
                hour = notifAfternoonHour,
                minute = notifAfternoonMinute,
                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.AFTERNOON, it, notifAfternoonHour, notifAfternoonMinute) },
                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.AFTERNOON }
            )

            // Evening slot
            NotifSlotRow(
                label = "Evening",
                description = "Tomorrow prep + incomplete items",
                enabled = notifEveningEnabled,
                hour = notifEveningHour,
                minute = notifEveningMinute,
                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.EVENING, it, notifEveningHour, notifEveningMinute) },
                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.EVENING }
            )

            // Time picker dialogs for each slot
            val slotBeingEdited = showNotifTimePicker
            if (slotBeingEdited != null) {
                val pickerState = when (slotBeingEdited) {
                    SmartNotificationWorker.Slot.MORNING -> notifMorningPickerState
                    SmartNotificationWorker.Slot.MIDDAY -> notifMiddayPickerState
                    SmartNotificationWorker.Slot.AFTERNOON -> notifAfternoonPickerState
                    SmartNotificationWorker.Slot.EVENING -> notifEveningPickerState
                }
                val currentEnabled = when (slotBeingEdited) {
                    SmartNotificationWorker.Slot.MORNING -> notifMorningEnabled
                    SmartNotificationWorker.Slot.MIDDAY -> notifMiddayEnabled
                    SmartNotificationWorker.Slot.AFTERNOON -> notifAfternoonEnabled
                    SmartNotificationWorker.Slot.EVENING -> notifEveningEnabled
                }
                AlertDialog(
                    onDismissRequest = { showNotifTimePicker = null },
                    title = { Text("${slotBeingEdited.name.lowercase().replaceFirstChar { it.uppercase() }} notification time") },
                    text = { TimePicker(state = pickerState) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.saveNotifSlot(slotBeingEdited, currentEnabled, pickerState.hour, pickerState.minute)
                            showNotifTimePicker = null
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showNotifTimePicker = null }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider()

            // ── Behaviour ──────────────────────────────────────────
            Text(
                text = "Behaviour",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Swipe to complete / archive",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Swipe right = done  ·  Swipe left = archive",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = swipeToCompleteEnabled,
                    onCheckedChange = { viewModel.setSwipeToCompleteEnabled(it) }
                )
            }

            HorizontalDivider()

            // Biometric lock
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Biometric lock",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "Re-prompt fingerprint/face when you leave the app for more than a few seconds",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = biometricLockEnabled,
                    onCheckedChange = { viewModel.setBiometricLockEnabled(it) }
                )
            }

            HorizontalDivider()

            // ── Hey Brain ─────────────────────────────────────────
            Text(
                text = "Hey Brain",
                style = MaterialTheme.typography.titleMedium
            )

            Text(
                text = "Tap 🎤 on the widget to start a voice conversation. " +
                        "Or enable the wake word to go fully hands-free.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "\"Hey Brain\" wake word",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                    Text(
                        text = "Always-listening in background. Shows a minimal notification (Android requirement).",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(
                    checked = wakeWordEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled) {
                            viewModel.setWakeWordEnabled(false)
                        } else if (ContextCompat.checkSelfPermission(
                                context, Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED
                        ) {
                            viewModel.setWakeWordEnabled(true)
                        } else {
                            recordAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    }
                )
            }

            if (wakeWordEnabled) {
                Text(
                    text = "Picovoice access key required for wake word. Get one free at console.picovoice.ai.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                OutlinedTextField(
                    value = picovoiceKey,
                    onValueChange = { picovoiceKey = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Picovoice Access Key") },
                    placeholder = { Text("Enter access key…") },
                    visualTransformation = if (picovoiceKeyVisible) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = {
                        IconButton(onClick = { picovoiceKeyVisible = !picovoiceKeyVisible }) {
                            Icon(
                                imageVector = if (picovoiceKeyVisible) Icons.Filled.Visibility
                                else Icons.Filled.VisibilityOff,
                                contentDescription = if (picovoiceKeyVisible) "Hide" else "Show"
                            )
                        }
                    },
                    singleLine = true
                )

                Button(
                    onClick = { viewModel.savePicovoiceAccessKey(picovoiceKey) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = picovoiceKey != savedPicovoiceKey
                ) {
                    Text("Save Picovoice Key")
                }
            }

            HorizontalDivider()

            // ── Buckets ────────────────────────────────────────────
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Buckets",
                    style = MaterialTheme.typography.titleMedium
                )
                IconButton(onClick = { showAddBucketDialog = true }) {
                    Icon(Icons.Filled.Add, contentDescription = "Add bucket")
                }
            }

            buckets.filter { isVaultVisible || !it.isVault }.forEach { bucket ->
                BucketRow(
                    bucket = bucket,
                    onEdit = { editingBucket = bucket },
                    onVaultToggle = { viewModel.setBucketVault(bucket, !bucket.isVault) },
                    onDelete = { deletingBucket = bucket }
                )
            }

            // bottom padding
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    // ── Add bucket dialog ──────────────────────────────────────────
    if (showAddBucketDialog) {
        BucketDialog(
            title = "New bucket",
            initialName = "",
            initialIsVault = false,
            initialColor = "#6750A4",
            onConfirm = { name, isVault, color ->
                viewModel.createBucket(name, isVault, color)
                showAddBucketDialog = false
            },
            onDismiss = { showAddBucketDialog = false }
        )
    }

    // ── Edit bucket dialog ─────────────────────────────────────────
    val editing = editingBucket
    if (editing != null) {
        BucketDialog(
            title = "Edit bucket",
            initialName = editing.name,
            initialIsVault = editing.isVault,
            initialColor = editing.colorHex,
            onConfirm = { name, isVault, color ->
                viewModel.renameBucket(editing, name, color)
                if (isVault != editing.isVault) viewModel.setBucketVault(editing, isVault)
                editingBucket = null
            },
            onDismiss = { editingBucket = null }
        )
    }

    // ── Delete confirmation dialog ─────────────────────────────────
    val deleting = deletingBucket
    if (deleting != null) {
        AlertDialog(
            onDismissRequest = { deletingBucket = null },
            title = { Text("Delete \"${deleting.name}\"?") },
            text = {
                Text("All notes and to-dos in this bucket will also be permanently deleted. This cannot be undone.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.deleteBucket(deleting)
                        deletingBucket = null
                    }
                ) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deletingBucket = null }) { Text("Cancel") }
            }
        )
    }
}

@Composable
private fun NotifSlotRow(
    label: String,
    description: String,
    enabled: Boolean,
    hour: Int,
    minute: Int,
    onToggle: (Boolean) -> Unit,
    onPickTime: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = label, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (enabled) {
            androidx.compose.material3.TextButton(onClick = onPickTime) {
                Icon(
                    imageVector = Icons.Filled.AccessTime,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(end = 4.dp)
                        .size(16.dp)
                )
                Text(text = "%02d:%02d".format(hour, minute))
            }
        }
        Switch(checked = enabled, onCheckedChange = onToggle)
    }
}

@Composable
private fun BucketRow(
    bucket: BucketEntity,
    onEdit: () -> Unit,
    onVaultToggle: () -> Unit,
    onDelete: () -> Unit
) {
    val dotColor = try {
        Color(android.graphics.Color.parseColor(bucket.colorHex))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(12.dp)
                .clip(CircleShape)
                .background(dotColor)
        )
        if (bucket.isVault) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Vault bucket",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 4.dp)
            )
        }
        Text(
            text = bucket.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f).padding(start = 4.dp)
        )
        IconButton(onClick = onEdit) {
            Icon(Icons.Filled.Edit, contentDescription = "Edit")
        }
        if (bucket.isUserCreated) {
            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Filled.Delete,
                    contentDescription = "Delete",
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BucketDialog(
    title: String,
    initialName: String,
    initialIsVault: Boolean,
    initialColor: String,
    onConfirm: (name: String, isVault: Boolean, color: String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var isVault by remember { mutableStateOf(initialIsVault) }
    var selectedColor by remember { mutableStateOf(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Bucket name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Colour", style = MaterialTheme.typography.labelMedium)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    BUCKET_COLORS.forEach { hex ->
                        val color = try {
                            Color(android.graphics.Color.parseColor(hex))
                        } catch (e: Exception) {
                            Color.Gray
                        }
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(CircleShape)
                                .background(color)
                                .border(
                                    width = if (selectedColor == hex) 2.dp else 0.dp,
                                    color = if (selectedColor == hex) MaterialTheme.colorScheme.onSurface
                                            else Color.Transparent,
                                    shape = CircleShape
                                )
                                .clickable { selectedColor = hex }
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(Icons.Filled.Lock, contentDescription = null)
                        Text("Vault (hidden by default)", style = MaterialTheme.typography.bodyMedium)
                    }
                    Switch(checked = isVault, onCheckedChange = { isVault = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(name, isVault, selectedColor) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
