package com.carlmanning.carlsbrain.ui.screens.settings

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
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
import androidx.compose.material.icons.filled.Download
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
import androidx.compose.material3.DropdownMenuItem
// NOTE: ExposedDropdownMenu is deliberately NOT imported. In the material3 version this BOM
// resolves it exists only as a member of ExposedDropdownMenuBoxScope, and scope members resolve
// without an import — adding one fails with "Unresolved reference 'ExposedDropdownMenu'". Later
// material3 versions add a top-level extension with the same name, which WOULD need the import,
// so if that error ever reappears after a BOM bump, add it back rather than changing the call.
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Slider
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
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.BuildConfig
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.worker.SmartNotificationWorker
import com.carlmanning.carlsbrain.data.remote.AvailableCalendar
import com.carlmanning.carlsbrain.data.audio.AmbientBuffer
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.voice.WakeWordModel
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import kotlinx.coroutines.launch
import java.util.Locale

/** Which end of the quiet-hours window a picker is editing. */
private enum class QuietBound { START, END }

/** Minutes-since-midnight as `HH:mm`. Fixed 24-hour, matching the other time pickers here. */
private fun formatMinuteOfDay(min: Int): String =
    String.format(Locale.US, "%02d:%02d", (min / 60) % 24, min % 60)

/**
 * Whether the app is exempt from Doze / battery optimisation.
 *
 * The microphone foreground service survives ordinary background limits, but Doze — and OEM
 * battery managers on top of it — can still throttle or kill it once the screen has been off
 * for a while. That presents as "Hey Brain worked all day then stopped overnight", so the
 * exemption is what makes always-listening actually always.
 */
private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    runCatching {
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) == true
    }.getOrDefault(false)

/**
 * Opens the system exemption dialog, falling back to the full battery-optimisation list.
 *
 * The direct request action is the one-tap path, but some OEM builds and managed profiles
 * refuse to resolve it. Falling back to the settings list keeps the button useful instead of
 * doing nothing at all, and the caller re-checks the real state on resume either way, so a
 * dialog Carl dismisses cannot leave the UI claiming success.
 */
private fun requestBatteryOptimizationExemption(context: Context) {
    val direct = Intent(
        Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
        Uri.parse("package:${context.packageName}")
    )
    if (runCatching { context.startActivity(direct); true }.getOrDefault(false)) return
    runCatching {
        context.startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
    }
}

/**
 * Plain-English label for a stored trigger source.
 *
 * Entries logged before the sherpa-onnx swap carry the old `PORCUPINE` source string, so that
 * value is still translated rather than falling through to the raw text — the log survives the
 * engine change and old entries stay readable.
 */
private fun describeTriggerSource(source: String): String = when (source) {
    UserPreferences.TRIGGER_SOURCE_KWS, "PORCUPINE" -> "Wake phrase heard"
    UserPreferences.TRIGGER_SOURCE_NOTIFICATION_ACTION -> "Notification tapped"
    UserPreferences.TRIGGER_SOURCE_RESUME -> "Resumed within the follow-up window"
    UserPreferences.TRIGGER_SOURCE_EXTERNAL_INTENT -> "Started by another app or a stray intent"
    else -> source
}

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
    val wakeKeyword by viewModel.wakeKeyword.collectAsStateWithLifecycle()
    val wakeThreshold by viewModel.wakeThreshold.collectAsStateWithLifecycle()
    val wakeTriggerLog by viewModel.wakeTriggerLog.collectAsStateWithLifecycle()
    val wakeResumeWindowSec by viewModel.wakeResumeWindowSec.collectAsStateWithLifecycle()
    val wakeQuietEnabled by viewModel.wakeQuietEnabled.collectAsStateWithLifecycle()
    val wakeQuietStartMin by viewModel.wakeQuietStartMin.collectAsStateWithLifecycle()
    val wakeQuietEndMin by viewModel.wakeQuietEndMin.collectAsStateWithLifecycle()
    val conversationEndTone by viewModel.conversationEndTone.collectAsStateWithLifecycle()
    val savedJournalPrompt by viewModel.journalPrompt.collectAsStateWithLifecycle()
    val savedFirefliesKey by viewModel.firefliesApiKey.collectAsStateWithLifecycle()
    val isGoogleConnected by viewModel.isGoogleConnected.collectAsStateWithLifecycle()
    val pendingBucketDeletion by viewModel.pendingBucketDeletion.collectAsStateWithLifecycle()
    val savedDigestHour by viewModel.morningDigestHour.collectAsStateWithLifecycle()
    val savedDigestMinute by viewModel.morningDigestMinute.collectAsStateWithLifecycle()
    val swipeToCompleteEnabled by viewModel.swipeToCompleteEnabled.collectAsStateWithLifecycle()
    val biometricLockEnabled by viewModel.biometricLockEnabled.collectAsStateWithLifecycle()
    val vaultPinHash by viewModel.vaultPinHash.collectAsStateWithLifecycle()
    val wakeWordEnabled by viewModel.wakeWordEnabled.collectAsStateWithLifecycle()
    val ambientBufferEnabled by viewModel.ambientBufferEnabled.collectAsStateWithLifecycle()
    val ambientBufferMinutes by viewModel.ambientBufferMinutes.collectAsStateWithLifecycle()
    val meetingAutoCutoffEnabled by viewModel.meetingAutoCutoffEnabled.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val restoreState by viewModel.restoreState.collectAsStateWithLifecycle()
    val calendarListState by viewModel.calendarListState.collectAsStateWithLifecycle()
    val excludedCalendarIds by viewModel.excludedCalendarIds.collectAsStateWithLifecycle()
    val exportState by viewModel.exportState.collectAsStateWithLifecycle()
    val hasVaultBuckets by viewModel.hasVaultBuckets.collectAsStateWithLifecycle()

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
    var wakeKeywordMenuExpanded by remember { mutableStateOf(false) }
    // Local slider position so dragging is smooth; committed to DataStore on release only,
    // because every commit restarts the wake-word loop.
    var wakeThresholdSlider by remember(wakeThreshold) { mutableStateOf(wakeThreshold) }
    var firefliesKey by remember(savedFirefliesKey) { mutableStateOf(savedFirefliesKey) }
    var firefliesKeyVisible by remember { mutableStateOf(false) }
    // Morning digest time picker. Same rule as the slot pickers below: the picker
    // state is built inside the dialog, never here.
    var showDigestTimePicker by remember { mutableStateOf(false) }
    var showQuietPicker by remember { mutableStateOf<QuietBound?>(null) }
    // Keyed on the saved value so an edit elsewhere refreshes the field rather than being
    // overwritten by stale local state.
    var journalPromptDraft by remember(savedJournalPrompt) { mutableStateOf(savedJournalPrompt) }
    var bufferMinutesDraft by remember(ambientBufferMinutes) {
        mutableStateOf(ambientBufferMinutes.toFloat())
    }
    // Local slider position, committed on release only — every commit recycles the wake-word
    // loop, so committing on every drag frame would thrash the microphone.
    var resumeWindowSlider by remember(wakeResumeWindowSec) {
        mutableStateOf(wakeResumeWindowSec.toFloat())
    }
    var showAddBucketDialog by remember { mutableStateOf(false) }
    var editingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    var showVaultPinDialog by remember { mutableStateOf<com.carlmanning.carlsbrain.ui.components.VaultPinDialogMode?>(null) }

    // Smart notification slot time pickers.
    // The picker state is deliberately NOT created here: rememberTimePickerState
    // takes no keys, so a state created at screen level would latch the
    // stateIn() placeholder default that composes before DataStore emits, and
    // every OK tap would write that default back over Carl's saved time.
    // It is created inside the dialog instead, from the current flow value.
    var showNotifTimePicker by remember { mutableStateOf<SmartNotificationWorker.Slot?>(null) }

    // Export everything. The choice is made in the dialog BEFORE the file picker opens,
    // so what is about to be written is settled before Carl names the file.
    var showExportDialog by remember { mutableStateOf(false) }
    var exportIncludeVault by remember { mutableStateOf(false) }

    // Accordion expanded states
    var aiVoiceExpanded by remember { mutableStateOf(false) }
    var googleExpanded by remember { mutableStateOf(false) }
    var notificationsExpanded by remember { mutableStateOf(false) }
    var behaviourExpanded by remember { mutableStateOf(false) }
    var vaultPinExpanded by remember { mutableStateOf(false) }
    var bucketsExpanded by remember { mutableStateOf(true) }

    // Fetch the calendar list only when the section is actually open and there is an
    // account to fetch it for — no network call on every Settings visit.
    LaunchedEffect(googleExpanded, isGoogleConnected) {
        if (googleExpanded && isGoogleConnected) viewModel.loadCalendars()
    }

    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    // Doze exemption state. Re-read on every resume rather than once at composition, because
    // it is changed outside the app — in the system dialog this screen launches, or in Android
    // settings directly — and it can be revoked as well as granted. Reading it once would
    // leave the row asserting a state that is no longer true.
    var batteryUnrestricted by remember { mutableStateOf(isIgnoringBatteryOptimizations(context)) }
    LifecycleResumeEffect(context) {
        batteryUnrestricted = isIgnoringBatteryOptimizations(context)
        onPauseOrDispose {}
    }

    val googleAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleAuthResult(result.data)
        }
    }

    // Carl picks the destination — the zip is written straight into that document, so
    // nothing lands in app-private storage and no storage permission is needed.
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        // Null means he backed out of the picker: not an error, just nothing to do.
        if (uri != null) {
            viewModel.exportEverything(
                uri = uri,
                includeVault = exportIncludeVault,
                // Live gate state, re-checked in the ViewModel.
                vaultUnlocked = isVaultVisible
            )
        }
        exportIncludeVault = false
    }

    val recordAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.setWakeWordEnabled(true)
    }

    // Separate from recordAudioLauncher: that one enables the wake word on grant, and the two
    // switches must not turn each other on.
    val bufferAudioLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        viewModel.setAmbientBufferEnabled(granted)
    }

    LaunchedEffect(Unit) {
        viewModel.googleAuthIntent.collect { pendingIntent ->
            googleAuthLauncher.launch(
                IntentSenderRequest.Builder(pendingIntent.intentSender).build()
            )
        }
    }

    LaunchedEffect(Unit) {
        viewModel.errorMessage.collect { message ->
            snackbarHostState.showSnackbar(message)
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

    // Quiet-hours pickers. Same key() guard as the digest picker above: without it a state
    // built before DataStore emits would latch the placeholder default, and OK would write
    // that back over the saved window.
    if (showQuietPicker != null) {
        val editingStart = showQuietPicker == QuietBound.START
        val initialMin = if (editingStart) wakeQuietStartMin else wakeQuietEndMin
        val quietPickerState = key(initialMin) {
            rememberTimePickerState(
                initialHour = initialMin / 60,
                initialMinute = initialMin % 60,
                is24Hour = true
            )
        }
        AlertDialog(
            onDismissRequest = { showQuietPicker = null },
            title = { Text(if (editingStart) "Stop listening at" else "Start listening at") },
            text = { TimePicker(state = quietPickerState) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = quietPickerState.hour * 60 + quietPickerState.minute
                    viewModel.setWakeQuietHours(
                        enabled = wakeQuietEnabled,
                        startMin = if (editingStart) picked else wakeQuietStartMin,
                        endMin = if (editingStart) wakeQuietEndMin else picked
                    )
                    showQuietPicker = null
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = { showQuietPicker = null }) { Text("Cancel") }
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
                                // Phrase and threshold are applied by rewriting keywords.txt and
                                // bouncing the listening loop, so A/B testing a phrase needs no
                                // rebuild. Only validated tokenisations are offered — see
                                // docs/wake-word.md.
                                Text(
                                    text = "Wake phrase",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                ExposedDropdownMenuBox(
                                    expanded = wakeKeywordMenuExpanded,
                                    onExpandedChange = { wakeKeywordMenuExpanded = it }
                                ) {
                                    OutlinedTextField(
                                        value = wakeKeyword,
                                        onValueChange = {},
                                        readOnly = true,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .menuAnchor(
                                                MenuAnchorType.PrimaryNotEditable,
                                                enabled = true
                                            ),
                                        label = { Text("Phrase") },
                                        trailingIcon = {
                                            ExposedDropdownMenuDefaults.TrailingIcon(
                                                expanded = wakeKeywordMenuExpanded
                                            )
                                        },
                                        singleLine = true
                                    )
                                    ExposedDropdownMenu(
                                        expanded = wakeKeywordMenuExpanded,
                                        onDismissRequest = { wakeKeywordMenuExpanded = false }
                                    ) {
                                        WakeWordModel.WAKE_KEYWORDS.forEach { keyword ->
                                            DropdownMenuItem(
                                                text = { Text(keyword.displayName) },
                                                onClick = {
                                                    wakeKeywordMenuExpanded = false
                                                    if (keyword.displayName != wakeKeyword) {
                                                        viewModel.setWakeKeyword(keyword.displayName)
                                                    }
                                                }
                                            )
                                        }
                                    }
                                }
                                Text(
                                    text = if (wakeThresholdSlider <= 0f) {
                                        "Trigger threshold: model default"
                                    } else {
                                        "Trigger threshold: %.2f".format(
                                            Locale.US, wakeThresholdSlider
                                        )
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = wakeThresholdSlider,
                                    onValueChange = { wakeThresholdSlider = it },
                                    onValueChangeFinished = {
                                        // Snap anything below the usable range back to 0 so the
                                        // model default is used rather than a hair-trigger value.
                                        val committed =
                                            if (wakeThresholdSlider < 0.1f) 0f else wakeThresholdSlider
                                        wakeThresholdSlider = committed
                                        if (committed != wakeThreshold) {
                                            viewModel.setWakeThreshold(committed)
                                        }
                                    },
                                    valueRange = 0f..0.6f,
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "Higher means fewer false triggers but more misses. " +
                                            "Useful range is 0.10–0.60; 0 uses the model default. " +
                                            "Changing the phrase or threshold restarts listening " +
                                            "straight away.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Follow-up window. 0 disables resuming entirely, so every
                                // wake starts a fresh conversation with no prior history.
                                HorizontalDivider()
                                Text(
                                    text = if (resumeWindowSlider < 1f) {
                                        "Follow-up window: off"
                                    } else {
                                        "Follow-up window: ${resumeWindowSlider.toInt()} s"
                                    },
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                Slider(
                                    value = resumeWindowSlider,
                                    onValueChange = { resumeWindowSlider = it },
                                    onValueChangeFinished = {
                                        val committed = resumeWindowSlider.toInt()
                                        if (committed != wakeResumeWindowSec) {
                                            viewModel.setWakeResumeWindowSec(committed)
                                        }
                                    },
                                    valueRange = 0f..UserPreferences.MAX_RESUME_WINDOW_SEC.toFloat(),
                                    modifier = Modifier.fillMaxWidth()
                                )
                                Text(
                                    text = "If a conversation ends because you went quiet, " +
                                            "saying the wake phrase again within this window " +
                                            "carries on where you left off instead of starting " +
                                            "over. Set to off to always start fresh.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )

                                // Quiet hours.
                                HorizontalDivider()
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "Quiet hours",
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                        Text(
                                            text = "Stop listening overnight",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    Switch(
                                        checked = wakeQuietEnabled,
                                        onCheckedChange = {
                                            viewModel.setWakeQuietHours(
                                                enabled = it,
                                                startMin = wakeQuietStartMin,
                                                endMin = wakeQuietEndMin
                                            )
                                        }
                                    )
                                }
                                if (wakeQuietEnabled) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "Stop at ${formatMinuteOfDay(wakeQuietStartMin)}, " +
                                                    "resume at ${formatMinuteOfDay(wakeQuietEndMin)}",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(onClick = { showQuietPicker = QuietBound.START }) {
                                            Text("Stop")
                                        }
                                        TextButton(onClick = { showQuietPicker = QuietBound.END }) {
                                            Text("Resume")
                                        }
                                    }
                                    if (wakeQuietStartMin == wakeQuietEndMin) {
                                        // An empty window rather than all-day, so say so instead
                                        // of letting Carl believe listening is being paused.
                                        Text(
                                            text = "Stop and resume are the same time, so quiet " +
                                                    "hours are not being applied. Set different " +
                                                    "times to pause listening.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                    }
                                }

                                // Doze exemption. Only surfaced while the wake word is on,
                                // since it is the always-listening service that needs it, and
                                // only as a prompt — never requested automatically on launch.
                                HorizontalDivider()
                                if (batteryUnrestricted) {
                                    Text(
                                        text = "Battery: unrestricted ✓",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Listening will not be interrupted while the " +
                                                "screen is off.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    Text(
                                        text = "Battery: restricted",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Text(
                                        text = "Android may stop the wake word once the screen " +
                                                "has been off for a while — it works all day, " +
                                                "then goes quiet overnight. Allowing unrestricted " +
                                                "battery use fixes that.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Button(
                                        onClick = { requestBatteryOptimizationExemption(context) },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Allow unrestricted battery use")
                                    }
                                    Text(
                                        text = "On Samsung, also check Settings → Battery → " +
                                                "Background usage limits and make sure Carl's " +
                                                "Brain is not listed under Sleeping apps.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }

                                // Recent activations. This is the whole reason the trigger log
                                // exists: an unexplained wake-up is only diagnosable if the
                                // source is visible. NOTIFICATION_ACTION here means a pocket
                                // tap on the notification, not a spoken phrase.
                                HorizontalDivider()
                                Text(
                                    text = "Recent activations",
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (wakeTriggerLog.isEmpty()) {
                                    Text(
                                        text = "No activations recorded yet.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                } else {
                                    wakeTriggerLog.forEach { entry ->
                                        Text(
                                            text = buildString {
                                                append(formatSmartDateTime(entry.at))
                                                append(" · ")
                                                append(describeTriggerSource(entry.source))
                                                if (entry.rms >= 0) append(" · level ${entry.rms}")
                                            },
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    OutlinedButton(
                                        onClick = { viewModel.clearWakeTriggerLog() },
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Text("Clear activation log")
                                    }
                                }
                            }

                            // Outside the wakeWordEnabled block on purpose: a conversation can
                            // also be started from the notification or the Quick Settings tile,
                            // so the end tone is not exclusive to the wake word.
                            HorizontalDivider()
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "Conversation end tone",
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                    Text(
                                        text = "Beep when a voice conversation finishes",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Switch(
                                    checked = conversationEndTone,
                                    onCheckedChange = { viewModel.setConversationEndTone(it) }
                                )
                            }
                        }
                    }
                }
            }

            // ── Ambient buffer ─────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Ambient buffer", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "Keeps the last few minutes of what the microphone hears, so you " +
                                "can start a recording after the conversation has already " +
                                "started. Nothing is saved anywhere until you tap Record.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Rolling buffer",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Listens continuously while switched on",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = ambientBufferEnabled,
                            onCheckedChange = { on ->
                                if (on) {
                                    // Same permission gate as the wake word — the switch is
                                    // meaningless without the microphone, and asking here is
                                    // clearer than failing silently in the service. The switch
                                    // is flipped by the launcher's result, so a denied
                                    // permission leaves it off rather than lying.
                                    bufferAudioLauncher.launch(Manifest.permission.RECORD_AUDIO)
                                } else {
                                    viewModel.setAmbientBufferEnabled(false)
                                }
                            }
                        )
                    }

                    if (ambientBufferEnabled) {
                        HorizontalDivider()
                        Text(
                            text = "Keep the last ${bufferMinutesDraft.toInt()} minutes",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        // Committed on release, not on every drag frame: each commit rewrites
                        // DataStore and resizes the ring, which discards what is buffered.
                        // Saving mid-drag would wipe the buffer a dozen times per adjustment.
                        Slider(
                            value = bufferMinutesDraft,
                            onValueChange = { bufferMinutesDraft = it },
                            onValueChangeFinished = {
                                viewModel.setAmbientBufferMinutes(bufferMinutesDraft.toInt())
                            },
                            valueRange = AmbientBuffer.MIN_MINUTES.toFloat()..
                                    AmbientBuffer.MAX_MINUTES.toFloat(),
                            steps = AmbientBuffer.MAX_MINUTES - AmbientBuffer.MIN_MINUTES - 1
                        )
                        Text(
                            text = "About ${bufferMinutesDraft.toInt() * 2} MB of cache while running. " +
                                    "If the wake word is on it shares the same microphone, so " +
                                    "the buffer pauses during quiet hours and conversations.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    HorizontalDivider()
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "Stop recordings after 90 minutes",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = "Turn off for a genuinely long session",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = meetingAutoCutoffEnabled,
                            onCheckedChange = { viewModel.setMeetingAutoCutoffEnabled(it) }
                        )
                    }
                }
            }

            // ── Journal ────────────────────────────────────────────────
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Journal", style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = "The prompt shown when you open Journal. Leave it empty for a " +
                                "blank page. You can always ask Claude for a different prompt " +
                                "from the Journal screen itself.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    OutlinedTextField(
                        value = journalPromptDraft,
                        onValueChange = { journalPromptDraft = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = { Text("Journal prompt") },
                        placeholder = { Text("No prompt — blank page") },
                        singleLine = false,
                        minLines = 2
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = {
                                journalPromptDraft = UserPreferences.DEFAULT_JOURNAL_PROMPT
                                viewModel.setJournalPrompt(UserPreferences.DEFAULT_JOURNAL_PROMPT)
                            },
                            modifier = Modifier.weight(1f)
                        ) { Text("Reset", maxLines = 1) }
                        Button(
                            onClick = { viewModel.setJournalPrompt(journalPromptDraft.trim()) },
                            modifier = Modifier.weight(1.5f),
                            enabled = journalPromptDraft.trim() != savedJournalPrompt
                        ) { Text("Save prompt", maxLines = 1) }
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
                                NotifSubHeader("Calendars")
                                Text(
                                    text = "Turn off a calendar to keep it out of briefings and your schedule.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                when (val cs = calendarListState) {
                                    is CalendarListState.Loading -> Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                        Text(
                                            text = "Loading your calendars…",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )
                                    }
                                    is CalendarListState.Ready -> {
                                        if (cs.calendars.isEmpty()) {
                                            Text(
                                                text = "No calendars found on this Google account.",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        } else {
                                            cs.calendars.forEach { cal ->
                                                key(cal.id) {
                                                    CalendarToggleRow(
                                                        calendar = cal,
                                                        // Primary is always on, whatever the stored set says.
                                                        included = cal.isPrimary || cal.id !in excludedCalendarIds,
                                                        onToggle = { on ->
                                                            viewModel.setCalendarExcluded(cal, !on)
                                                        }
                                                    )
                                                }
                                            }
                                        }
                                    }
                                    is CalendarListState.Error -> {
                                        Text(
                                            text = cs.message,
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.error
                                        )
                                        TextButton(onClick = { viewModel.loadCalendars() }) {
                                            Text("Retry")
                                        }
                                    }
                                    is CalendarListState.NotConnected -> Text(
                                        text = "Connect your Google account to choose calendars.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
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

                            // Deliberately outside the isGoogleConnected branch: the export
                            // reads the local database, so it works with or without an account.
                            // Only memory.md needs Drive, and it is skipped if unreachable.
                            NotifSubHeader("Your data")
                            Text(
                                text = "Save everything as a zip of Markdown and CSV files you " +
                                    "can open anywhere — no app needed to read it.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            OutlinedButton(
                                onClick = { showExportDialog = true },
                                modifier = Modifier.fillMaxWidth(),
                                enabled = exportState !is ExportState.Running
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Download,
                                    contentDescription = null,
                                    modifier = Modifier.padding(end = 8.dp)
                                )
                                Text("Export everything")
                            }
                            when (val es = exportState) {
                                is ExportState.Running -> Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                                    Text(
                                        text = es.step,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                is ExportState.Success -> Text(
                                    text = es.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                is ExportState.Error -> Text(
                                    text = es.message,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                                is ExportState.Idle -> Unit
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
                                    onDelete = { viewModel.requestBucketDeletion(bucket) }
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
    val deleting = pendingBucketDeletion
    if (deleting != null) {
        BucketDeleteDialog(
            info = deleting,
            onDismiss = { viewModel.cancelBucketDeletion() },
            onDeleteEmpty = { viewModel.deleteEmptyBucket() },
            onMove = { destId -> viewModel.moveContentsAndDeleteBucket(destId) },
            onDeleteContents = { typedName -> viewModel.deleteBucketAndContents(typedName) }
        )
    }

    // ── Export choice dialog (screen-level) ────────────────────────────
    if (showExportDialog) {
        ExportChoiceDialog(
            // The include-Vault option only exists when there is a vault AND it is
            // currently unlocked. A locked vault gets the plain export with a line
            // saying so — the export is never a way around the vault gate.
            hasVault = hasVaultBuckets,
            vaultUnlocked = isVaultVisible,
            onExport = { includeVault ->
                exportIncludeVault = includeVault
                showExportDialog = false
                viewModel.dismissExportState()
                exportLauncher.launch(viewModel.suggestedExportFileName())
            },
            onDismiss = { showExportDialog = false }
        )
    }
}

/**
 * Asks what goes in the export before the file picker opens.
 *
 * "Skip Vault items" is the default and always available. "Everything, including Vault"
 * appears only when a vault exists and is currently unlocked, and it says plainly that
 * the resulting zip is unencrypted.
 */
@Composable
private fun ExportChoiceDialog(
    hasVault: Boolean,
    vaultUnlocked: Boolean,
    onExport: (includeVault: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val canIncludeVault = hasVault && vaultUnlocked
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Filled.Download, contentDescription = null) },
        title = { Text("Export everything") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    text = "A zip of your notes, to-dos, meetings, calendar and buckets as " +
                        "Markdown and CSV. It's a snapshot to read or keep — it can't be " +
                        "loaded back into the app.",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "Meeting audio isn't included — those recordings stay in Google Drive.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                when {
                    canIncludeVault -> {
                        Text(
                            text = "Including the Vault writes it in plain text. The zip is not " +
                                "encrypted — anything that can open a zip can read it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        OutlinedButton(
                            onClick = { onExport(true) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Everything, including Vault")
                        }
                    }
                    hasVault -> Text(
                        text = "Your Vault is locked, so Vault items will be left out. Unlock " +
                            "the Vault first if you want them included.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    else -> Unit
                }
            }
        },
        confirmButton = {
            // The default: Vault stays out unless Carl deliberately chose otherwise above.
            TextButton(onClick = { onExport(false) }) {
                Text(if (hasVault) "Skip Vault items" else "Export")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}

/**
 * Bucket deletion confirmation. Three shapes, chosen by what is actually inside the
 * bucket — the counts are read from the database before this is shown, never guessed:
 *
 *  - blocked   → deletion refused (last bucket standing); nothing destructive offered.
 *  - empty     → plain one-tap confirm, exactly as before.
 *  - populated → move-to-another-bucket (default) or delete-the-contents, the latter
 *                gated behind typing the bucket's name.
 */
@Composable
private fun BucketDeleteDialog(
    info: SettingsViewModel.BucketDeletionInfo,
    onDismiss: () -> Unit,
    onDeleteEmpty: () -> Unit,
    onMove: (Long) -> Unit,
    onDeleteContents: (String) -> Unit
) {
    val bucket = info.bucket

    // Blocked — items must be able to live somewhere.
    if (info.blockedReason != null) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Can't delete \"${bucket.name}\"") },
            text = { Text(info.blockedReason) },
            confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } }
        )
        return
    }

    // Empty — nothing to lose, keep the simple confirmation.
    if (info.isEmpty) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete \"${bucket.name}\"?") },
            text = { Text("This bucket is empty. Nothing will be lost.") },
            confirmButton = {
                TextButton(onClick = onDeleteEmpty) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
        )
        return
    }

    // Populated — offer the safe path first.
    var destructive by remember(bucket.id) { mutableStateOf(false) }
    var destId by remember(bucket.id) { mutableStateOf(info.moveTargets.firstOrNull()?.id) }
    var typedName by remember(bucket.id) { mutableStateOf("") }

    val contents = buildList {
        if (info.todoCount > 0) add("${info.todoCount} to-do${if (info.todoCount == 1) "" else "s"}")
        if (info.noteCount > 0) add("${info.noteCount} note${if (info.noteCount == 1) "" else "s"}")
        if (info.meetingCount > 0) add("${info.meetingCount} meeting${if (info.meetingCount == 1) "" else "s"}")
    }.let { parts ->
        when (parts.size) {
            1 -> parts[0]
            2 -> "${parts[0]} and ${parts[1]}"
            else -> "${parts.dropLast(1).joinToString(", ")} and ${parts.last()}"
        }
    }

    val typedMatches = typedName.trim().equals(bucket.name.trim(), ignoreCase = true)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Delete \"${bucket.name}\"?") },
        text = {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text("This bucket has $contents.")

                DeleteOptionRow(
                    selected = !destructive,
                    title = "Move them to another bucket",
                    subtitle = "Recommended — nothing is deleted",
                    onClick = { destructive = false }
                )
                if (!destructive) {
                    Column(
                        modifier = Modifier.padding(start = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        info.moveTargets.forEach { target ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { destId = target.id }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                RadioButton(
                                    selected = destId == target.id,
                                    onClick = { destId = target.id }
                                )
                                if (target.isVault) {
                                    Icon(
                                        Icons.Filled.Lock,
                                        contentDescription = "Vault bucket",
                                        tint = MaterialTheme.colorScheme.primary
                                    )
                                }
                                Text(target.name, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }

                DeleteOptionRow(
                    selected = destructive,
                    title = "Delete the bucket and everything in it",
                    subtitle = "Items go to Recently Deleted for 90 days",
                    onClick = { destructive = true }
                )
                if (destructive) {
                    Column(
                        modifier = Modifier.padding(start = 32.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "Type \"${bucket.name}\" to confirm.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        OutlinedTextField(
                            value = typedName,
                            onValueChange = { typedName = it },
                            singleLine = true,
                            label = { Text("Bucket name") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (destructive) {
                TextButton(
                    onClick = { onDeleteContents(typedName) },
                    enabled = typedMatches
                ) { Text("Delete everything", color = MaterialTheme.colorScheme.error) }
            } else {
                TextButton(
                    onClick = { destId?.let(onMove) },
                    enabled = destId != null
                ) { Text("Move & delete bucket") }
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun DeleteOptionRow(
    selected: Boolean,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
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

/**
 * One calendar with its include switch. The primary calendar's switch is disabled and
 * pinned on — excluding it would hide Carl's own events.
 */
@Composable
private fun CalendarToggleRow(
    calendar: AvailableCalendar,
    included: Boolean,
    onToggle: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(text = calendar.name, style = MaterialTheme.typography.bodyLarge)
            if (calendar.isPrimary) {
                Text(
                    text = "Your main calendar — always included",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        Switch(
            checked = included,
            onCheckedChange = onToggle,
            enabled = !calendar.isPrimary
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
