package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Activity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateBack: () -> Unit,
    viewModel: SettingsViewModel = viewModel()
) {
    val savedApiKey by viewModel.anthropicApiKey.collectAsStateWithLifecycle()
    val isGoogleConnected by viewModel.isGoogleConnected.collectAsStateWithLifecycle()
    val savedDigestHour by viewModel.morningDigestHour.collectAsStateWithLifecycle()
    val savedDigestMinute by viewModel.morningDigestMinute.collectAsStateWithLifecycle()
    val showVaultInDashboard by viewModel.showVaultInDashboard.collectAsStateWithLifecycle()
    val showVaultInNotifications by viewModel.showVaultInNotifications.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()

    var apiKey by remember(savedApiKey) { mutableStateOf(savedApiKey) }
    var apiKeyVisible by remember { mutableStateOf(false) }
    var showTimePicker by remember { mutableStateOf(false) }
    var showAddBucketDialog by remember { mutableStateOf(false) }
    var editingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    var deletingBucket by remember { mutableStateOf<BucketEntity?>(null) }
    val timePickerState = rememberTimePickerState(
        initialHour = savedDigestHour,
        initialMinute = savedDigestMinute,
        is24Hour = true
    )

    val googleAuthLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            viewModel.handleGoogleAuthResult(result.data)
        }
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
                title = { Text("Settings") },
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
                Text(
                    text = "Use this after installing on a new device or to restore your data.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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

            // ── Morning Digest ─────────────────────────────────────
            Text(
                text = "Morning Digest",
                style = MaterialTheme.typography.titleMedium
            )

            OutlinedButton(
                onClick = { showTimePicker = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Filled.AccessTime,
                    contentDescription = null,
                    modifier = Modifier.padding(end = 8.dp)
                )
                Text(
                    text = "%02d:%02d".format(savedDigestHour, savedDigestMinute)
                )
            }

            if (showTimePicker) {
                AlertDialog(
                    onDismissRequest = { showTimePicker = false },
                    title = { Text("Morning digest time") },
                    text = { TimePicker(state = timePickerState) },
                    confirmButton = {
                        TextButton(onClick = {
                            viewModel.saveDigestTime(timePickerState.hour, timePickerState.minute)
                            showTimePicker = false
                        }) { Text("OK") }
                    },
                    dismissButton = {
                        TextButton(onClick = { showTimePicker = false }) { Text("Cancel") }
                    }
                )
            }

            HorizontalDivider()

            // ── Vault ──────────────────────────────────────────────
            Text(
                text = "Vault",
                style = MaterialTheme.typography.titleMedium
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Show vault items in Dashboard",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showVaultInDashboard,
                    onCheckedChange = { viewModel.setShowVaultInDashboard(it) }
                )
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Show vault items in Notifications",
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = showVaultInNotifications,
                    onCheckedChange = { viewModel.setShowVaultInNotifications(it) }
                )
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

            buckets.forEach { bucket ->
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
            onConfirm = { name, isVault ->
                viewModel.createBucket(name, isVault)
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
            onConfirm = { name, isVault ->
                viewModel.renameBucket(editing, name)
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
private fun BucketRow(
    bucket: BucketEntity,
    onEdit: () -> Unit,
    onVaultToggle: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        if (bucket.isVault) {
            Icon(
                Icons.Filled.Lock,
                contentDescription = "Vault bucket",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(end = 4.dp)
            )
        }
        Text(
            text = bucket.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f)
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

@Composable
private fun BucketDialog(
    title: String,
    initialName: String,
    initialIsVault: Boolean,
    onConfirm: (name: String, isVault: Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var isVault by remember { mutableStateOf(initialIsVault) }

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
                onClick = { onConfirm(name, isVault) },
                enabled = name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
