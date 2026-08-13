package com.carlmanning.carlsbrain.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.foundation.layout.height
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
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Category
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Memory
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Restore
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import com.carlmanning.carlsbrain.BuildConfig
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import kotlinx.coroutines.launch

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
    val savedOpenaiKey by viewModel.openaiApiKey.collectAsStateWithLifecycle()
    val savedPicovoiceKey by viewModel.picovoiceAccessKey.collectAsStateWithLifecycle()
    val savedFirefliesKey by viewModel.firefliesApiKey.collectAsStateWithLifecycle()
    val isGoogleConnected by viewModel.isGoogleConnected.collectAsStateWithLifecycle()
    val savedDigestHour by viewModel.morningDigestHour.collectAsStateWithLifecycle()
    val savedDigestMinute by viewModel.morningDigestMinute.collectAsStateWithLifecycle()
    val swipeToCompleteEnabled by viewModel.swipeToCompleteEnabled.collectAsStateWithLifecycle()
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val vaultPinHash by viewModel.vaultPinHash.collectAsStateWithLifecycle()
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
    val digestEnabled by viewModel.digestEnabled.collectAsStateWithLifecycle()
    val remindersEnabled by viewModel.remindersEnabled.collectAsStateWithLifecycle()
    val weeklyReviewEnabled by viewModel.weeklyReviewEnabled.collectAsStateWithLifecycle()

    // Digest preview
    val digestPreview by viewModel.digestPreview.collectAsStateWithLifecycle()
    val isPreviewLoading by viewModel.isPreviewLoading.collectAsStateWithLifecycle()
    var previewSlot by remember { mutableStateOf<SmartNotificationWorker.Slot?>(null) }
    val previewSheetState = rememberModalBottomSheetState()

    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var openaiKey by remember(savedOpenaiKey) { mutableStateOf(savedOpenaiKey) }
    var openaiKeyVisible by remember { mutableStateOf(false) }
    var picovoiceKey by remember(savedPicovoiceKey) { mutableStateOf(savedPicovoiceKey) }
    var picovoiceKeyVisible by remember { mutableStateOf(false) }
    var firefliesKey by remember(savedFirefliesKey) { mutableStateOf(savedFirefliesKey) }
    var firefliesKeyVisible by remember { mutableStateOf(false) }
    // Morning digest time picker. Same rule as the slot pickers below: the picker
    // state is built inside the dialog, never here.
    var showDigestTimePicker by remember { mutableStateOf(false) }
    var showAddBucketDialog by remember { mutableStateOf(false) }
    var editingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    var deletingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    var showVaultPinDialog by remember { mutableStateOf<com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode?>(null) }

    // Smart notification slot time pickers.
    // The picker state is deliberately NOT created here: rememberTimePickerState
    // takes no keys, so a state created at screen level would latch the
    // stateIn() placeholder default that composes before DataStore emits, and
    // every OK tap would write that default back over Carl's saved time.
    // It is created inside the dialog instead, from the current flow value.
    var showNotifTimePicker by remember { mutableStateOf<SmartNotificationWorker.Slot?>(null) }

    // Accordion expanded states
    var aiVoiceExpanded by remember { mutableStateOf(false) }
    var googleExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var behaviourExpanded by remember { mutableStateOf(false) }
    var vaultPinExpanded by remember { mutableStateOf(false) }
    var bucketsExpanded by remember { mutableStateOf(true) }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

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

    // Vault PIN dialog (screen-level)
    showVaultPinDialog?.let { mode ->
        com.carlmanning.carlsbrain.ui.components.VaultPinDialog(
            mode = mode,
            storedPinHash = vaultPinHash,
            onSuccess = { pin ->
                if (mode != com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode.ENTER) {
                    viewModel.saveVaultPin(pin)
                }
                showVaultPinDialog = null
            },
            onDismiss = { showVaultPinDialog = null }
        )
    }

    // Morning digest time picker dialog (screen-level)
    if (showDigestTimePicker) {
        // key() on the persisted values: rememberTimePickerState takes no keys, so a
        // state built before DataStore emits would latch the placeholder default and
        // OK would write it back over Carl's saved time.
        val digestPickerState = key(savedDigestHour, savedDigestMinute) {
            rememberTimePickerState(
                initialHour = savedDigestHour,
                initialMinute = savedDigestMinute,
                is24Hour = true
            )
        }
        AlertDialog(
            onDismissRequest = { showDigestTimePicker = false },
            title = { Text("Daily digest time") },
            text = { TimePicker(state = digestPickerState) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.saveDigestTime(digestPickerState.hour, digestPickerState.minute)
                    showDigestTimePicker = false
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showDigestTimePicker = false }) { Text("Cancel") }
            }
        )
    }

    // Notification time picker dialog (screen-level)
    val slotBeingEdited = showNotifTimePicker
    if (slotBeingEdited != null) {
        // Same flow values the row displays, so dialog and row can never disagree.
        val currentHour = when (slotBeingEdited) {
            SmartNotificationWorker.Slot.MORNING -> notifMorningHour
            SmartNotificationWorker.Slot.MIDDAY -> notifMiddayHour
            SmartNotificationWorker.Slot.AFTERNOON -> notifAfternoonHour
            SmartNotificationWorker.Slot.EVENING -> notifEveningHour
        }
        val currentMinute = when (slotBeingEdited) {
            SmartNotificationWorker.Slot.MORNING -> notifMorningMinute
            SmartNotificationWorker.Slot.MIDDAY -> notifMiddayMinute
            SmartNotificationWorker.Slot.AFTERNOON -> notifAfternoonMinute
            SmartNotificationWorker.Slot.EVENING -> notifEveningMinute
        }
        // key() re-creates the picker if the persisted value arrives (or changes)
        // while the dialog is open — rememberTimePickerState itself has no keys.
        val pickerState = key(slotBeingEdited, currentHour, currentMinute) {
            rememberTimePickerState(
                initialHour = currentHour,
                initialMinute = currentMinute,
                is24Hour = true
            )
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                .verticalScroll(rememberScrollState())
                .padding(vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {

            // ── Account header ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Filled.AccountCircle,
                    contentDescription = null,
                    modifier = Modifier.size(32.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Column(modifier = Modifier.weight(1f)) {
                    if (isGoogleConnected) {
                        Text(
                            text = "Google account connected",
                            style = MaterialTheme.typography.bodyLarge
                        )
                        Text(
                            text = "Drive & Calendar sync active",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        Text(
                            text = "Not signed in",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // ── 1. AI & Voice ──────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { aiVoiceExpanded = !aiVoiceExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Memory,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("AI & Voice", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "API keys, wake word",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (aiVoiceExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = aiVoiceExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Anthropic API
                            Text(
                                text = "Anthropic API",
                                style = MaterialTheme.typography.titleMedium
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

                            // OpenAI API
                            Text(
                                text = "OpenAI API",
                                style = MaterialTheme.typography.titleMedium
                            )
                            OutlinedTextField(
                                value = openaiKey,
                                onValueChange = { openaiKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("OpenAI API Key (Whisper transcription)") },
                                placeholder = { Text("sk-…") },
                                visualTransformation = if (openaiKeyVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { openaiKeyVisible = !openaiKeyVisible }) {
                                        Icon(
                                            imageVector = if (openaiKeyVisible) Icons.Filled.Visibility
                                            else Icons.Filled.VisibilityOff,
                                            contentDescription = if (openaiKeyVisible) "Hide" else "Show"
                                        )
                                    }
                                },
                                singleLine = true
                            )
                            Button(
                                onClick = { viewModel.saveOpenaiApiKey(openaiKey) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = openaiKey != savedOpenaiKey
                            ) {
                                Text("Save OpenAI Key")
                            }

                            HorizontalDivider()

                            // Fireflies API
                            Text(
                                text = "Fireflies.ai",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Sync your Fireflies meeting transcripts and action items into Carl's Brain. Get your API key from fireflies.ai → Settings → Integrations.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedTextField(
                                value = firefliesKey,
                                onValueChange = { firefliesKey = it },
                                modifier = Modifier.fillMaxWidth(),
                                label = { Text("Fireflies API Key") },
                                placeholder = { Text("your-fireflies-api-key") },
                                visualTransformation = if (firefliesKeyVisible) VisualTransformation.None
                                else PasswordVisualTransformation(),
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                                trailingIcon = {
                                    IconButton(onClick = { firefliesKeyVisible = !firefliesKeyVisible }) {
                                        Icon(
                                            imageVector = if (firefliesKeyVisible) Icons.Filled.Visibility
                                            else Icons.Filled.VisibilityOff,
                                            contentDescription = if (firefliesKeyVisible) "Hide" else "Show"
                                        )
                                    }
                                },
                                singleLine = true
                            )
                            Button(
                                onClick = { viewModel.saveFirefliesApiKey(firefliesKey) },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = firefliesKey != savedFirefliesKey
                            ) {
                                Text("Save Fireflies Key")
                            }

                            HorizontalDivider()

                            // Hey Brain / Wake word
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
                        }
                    }
                }
            }

            // ── 2. Google Account ──────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { googleExpanded = !googleExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Sync,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Google Account", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (isGoogleConnected) "Connected" else "Drive & Calendar sync",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (isGoogleConnected) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (googleExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = googleExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
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
                        }
                    }
                }
            }

            // ── 3. Notifications ───────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { notificationsExpanded = !notificationsExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Notifications", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Digest, check-ins, reminders, weekly review",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (notificationsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = notificationsExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // ── Daily digest ──────────────────────────────
                            NotifSubHeader("Daily digest")
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Morning digest",
                                        style = MaterialTheme.typography.bodyLarge
                                    )
                                    Text(
                                        text = "Your briefing: today's events and priority tasks",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (digestEnabled) {
                                    TextButton(onClick = { showDigestTimePicker = true }) {
                                        Icon(
                                            imageVector = Icons.Filled.AccessTime,
                                            contentDescription = null,
                                            modifier = Modifier
                                                .padding(end = 4.dp)
                                                .size(16.dp)
                                        )
                                        Text(text = "%02d:%02d".format(savedDigestHour, savedDigestMinute))
                                    }
                                }
                                Switch(
                                    checked = digestEnabled,
                                    onCheckedChange = { viewModel.setDigestEnabled(it) }
                                )
                            }

                            HorizontalDivider()

                            // ── Extra check-ins ───────────────────────────
                            NotifSubHeader("Extra check-ins")

                            // Morning slot
                            NotifSlotRow(
                                label = "Morning",
                                description = "Off by default — the morning digest covers this",
                                enabled = notifMorningEnabled,
                                hour = notifMorningHour,
                                minute = notifMorningMinute,
                                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.MORNING, it, notifMorningHour, notifMorningMinute) },
                                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.MORNING },
                                onPreview = {
                                    previewSlot = SmartNotificationWorker.Slot.MORNING
                                    viewModel.generateDigestPreview(SmartNotificationWorker.Slot.MORNING)
                                }
                            )

                            // Midday slot
                            NotifSlotRow(
                                label = "Midday",
                                description = "Quick check-in: what's urgent?",
                                enabled = notifMiddayEnabled,
                                hour = notifMiddayHour,
                                minute = notifMiddayMinute,
                                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.MIDDAY, it, notifMiddayHour, notifMiddayMinute) },
                                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.MIDDAY },
                                onPreview = {
                                    previewSlot = SmartNotificationWorker.Slot.MIDDAY
                                    viewModel.generateDigestPreview(SmartNotificationWorker.Slot.MIDDAY)
                                }
                            )

                            // Afternoon slot
                            NotifSlotRow(
                                label = "Afternoon",
                                description = "Urgent todos with Done buttons",
                                enabled = notifAfternoonEnabled,
                                hour = notifAfternoonHour,
                                minute = notifAfternoonMinute,
                                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.AFTERNOON, it, notifAfternoonHour, notifAfternoonMinute) },
                                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.AFTERNOON },
                                onPreview = {
                                    previewSlot = SmartNotificationWorker.Slot.AFTERNOON
                                    viewModel.generateDigestPreview(SmartNotificationWorker.Slot.AFTERNOON)
                                }
                            )

                            // Evening slot
                            NotifSlotRow(
                                label = "Evening",
                                description = "Tomorrow prep + incomplete items",
                                enabled = notifEveningEnabled,
                                hour = notifEveningHour,
                                minute = notifEveningMinute,
                                onToggle = { viewModel.saveNotifSlot(SmartNotificationWorker.Slot.EVENING, it, notifEveningHour, notifEveningMinute) },
                                onPickTime = { showNotifTimePicker = SmartNotificationWorker.Slot.EVENING },
                                onPreview = {
                                    previewSlot = SmartNotificationWorker.Slot.EVENING
                                    viewModel.generateDigestPreview(SmartNotificationWorker.Slot.EVENING)
                                }
                            )

                            HorizontalDivider()

                            // ── Reminders ─────────────────────────────────
                            NotifSubHeader("Reminders")
                            NotifToggleRow(
                                label = "To-do reminders",
                                description = "Alerts at the reminder time you set on a to-do",
                                checked = remindersEnabled,
                                onCheckedChange = { viewModel.setRemindersEnabled(it) }
                            )

                            HorizontalDivider()

                            // ── Weekly review ─────────────────────────────
                            NotifSubHeader("Weekly review")
                            NotifToggleRow(
                                label = "Friday weekly review",
                                description = "Friday 17:00 nudge to review the week in chat",
                                checked = weeklyReviewEnabled,
                                onCheckedChange = { viewModel.setWeeklyReviewEnabled(it) }
                            )

                            HorizontalDivider()

                            // ── AI summaries ──────────────────────────────
                            NotifSubHeader("AI summaries")
                            NotifToggleRow(
                                label = "AI-generated summaries",
                                description = "Uses Claude Haiku to write the digest and check-in text. " +
                                    "Turn off to fall back to plain non-AI text. Requires API key.",
                                checked = notifAiEnabled,
                                onCheckedChange = { viewModel.setNotifAiEnabled(it) }
                            )

                            Text(
                                text = "Recording and voice-capture notifications aren't listed here — " +
                                    "Android requires them while those features are running, so they " +
                                    "can't be turned off.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // ── 4. Behaviour ───────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { behaviourExpanded = !behaviourExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Settings,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Behaviour", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "Swipe gestures, biometric lock",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (behaviourExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = behaviourExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
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
                        }
                    }
                }
            }

            // ── 5. Vault PIN ───────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { vaultPinExpanded = !vaultPinExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Lock,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Vault PIN", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    if (vaultPinHash.isBlank()) "No PIN set" else "PIN configured",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Icon(
                            if (vaultPinExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    AnimatedVisibility(visible = vaultPinExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = "Optional PIN fallback for vault access when biometrics are unavailable.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            androidx.compose.foundation.layout.Spacer(Modifier.height(4.dp))
                            if (vaultPinHash.isBlank()) {
                                Button(
                                    onClick = {
                                        showVaultPinDialog = com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode.SET
                                    },
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = null,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    androidx.compose.foundation.layout.Spacer(Modifier.width(8.dp))
                                    Text("Set Vault PIN")
                                }
                            } else {
                                androidx.compose.foundation.layout.Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Button(
                                        onClick = {
                                            showVaultPinDialog = com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode.CHANGE
                                        },
                                        modifier = Modifier.weight(1f)
                                    ) { Text("Change PIN") }
                                    OutlinedButton(
                                        onClick = { viewModel.clearVaultPin() },
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text("Remove PIN", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // ── 6. Buckets ─────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { bucketsExpanded = !bucketsExpanded }
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                Icons.Filled.Category,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Column {
                                Text("Buckets", style = MaterialTheme.typography.titleSmall)
                                Text(
                                    "${buckets.filter { isVaultVisible || !it.isVault }.size} buckets",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(onClick = { showAddBucketDialog = true }) {
                                Icon(Icons.Filled.Add, contentDescription = "Add bucket")
                            }
                            Icon(
                                if (bucketsExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null
                            )
                        }
                    }
                    AnimatedVisibility(visible = bucketsExpanded) {
                        Column(
                            modifier = Modifier
                                .padding(horizontal = 16.dp)
                                .padding(bottom = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            buckets.filter { isVaultVisible || !it.isVault }.forEach { bucket ->
                                BucketRow(
                                    bucket = bucket,
                                    onEdit = { editingBucket = bucket },
                                    onVaultToggle = { viewModel.setBucketVault(bucket, !bucket.isVault) },
                                    onDelete = { deletingBucket = bucket }
                                )
                            }
                        }
                    }
                }
            }

            // ── About footer ───────────────────────────────────────────
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                TextButton(onClick = {
                    val intent = Intent(Intent.ACTION_SENDTO).apply {
                        data = Uri.parse("mailto:carlmanning1@gmail.com")
                        putExtra(Intent.EXTRA_SUBJECT, "Carl's Brain feedback (v${BuildConfig.VERSION_NAME})")
                    }
                    runCatching {
                        context.startActivity(Intent.createChooser(intent, "Send feedback"))
                    }.onFailure {
                        snackbarScope.launch {
                            snackbarHostState.showSnackbar(
                                "No email app found — send feedback to carlmanning1@gmail.com"
                            )
                        }
                    }
                }) {
                    Text(
                        text = "Send feedback",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            // bottom padding
            androidx.compose.foundation.layout.Spacer(
                modifier = Modifier.padding(bottom = 8.dp)
            )
        }
    }

    // ── Digest preview sheet (screen-level) ────────────────────────────
    val previewingSlot = previewSlot
    if (previewingSlot != null) {
        ModalBottomSheet(
            onDismissRequest = {
                previewSlot = null
                viewModel.clearDigestPreview()
            },
            sheetState = previewSheetState
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "${previewingSlot.name.lowercase().replaceFirstChar { it.uppercase() }} digest preview",
                    style = MaterialTheme.typography.titleMedium
                )
                // Only accept a result stamped with the slot this sheet was
                // opened for; anything else is a late arrival from a previous tap.
                val preview = digestPreview?.takeIf { it.slot == previewingSlot }?.text
                when {
                    isPreviewLoading || preview == null -> Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp))
                        Text(
                            text = "Generating…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    preview.isBlank() -> Text(
                        text = "Nothing to report for this slot right now.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Text(
                        text = preview,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Text(
                    text = "Vault items are never included in notifications.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // ── Add bucket dialog (screen-level) ───────────────────────────────
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

    // ── Edit bucket dialog (screen-level) ──────────────────────────────
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

    // ── Delete confirmation dialog (screen-level) ──────────────────────
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
private fun NotifSubHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun NotifToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
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
        Switch(checked = checked, onCheckedChange = onCheckedChange)
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
    onPickTime: () -> Unit,
    onPreview: () -> Unit
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
        TextButton(
            onClick = onPreview,
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 8.dp)
        ) {
            Text(text = "Preview", style = MaterialTheme.typography.labelMedium)
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
