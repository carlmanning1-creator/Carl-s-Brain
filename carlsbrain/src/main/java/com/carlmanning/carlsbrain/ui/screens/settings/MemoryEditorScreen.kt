package com.carlmanning.carlsbrain.ui.screens.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.Button
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryEditorScreen(
    onNavigateBack: () -> Unit,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onNavigateToSettings: () -> Unit = {},
    onNavigateToSearch: (() -> Unit)? = null,
    viewModel: MemoryEditorViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val snackbarHostState = remember { SnackbarHostState() }

    var memoryFieldValue by remember { mutableStateOf(TextFieldValue("")) }
    LaunchedEffect(uiState.content) {
        if (memoryFieldValue.text != uiState.content) {
            memoryFieldValue = TextFieldValue(uiState.content, TextRange(uiState.content.length))
        }
    }

    LaunchedEffect(Unit) { viewModel.load() }

    LaunchedEffect(uiState.savedOk) {
        if (uiState.savedOk) snackbarHostState.showSnackbar("Saved to Drive")
    }

    LaunchedEffect(uiState.errorMessage) {
        val err = uiState.errorMessage ?: return@LaunchedEffect
        // A conflict is the one error with an obvious next step, so it gets an action rather
        // than a message Carl has to act on from memory. Reloading discards his unsaved edit,
        // which is why it is his choice and not automatic — the text is still on screen until
        // he takes it.
        if (uiState.conflict) {
            val result = snackbarHostState.showSnackbar(
                message = err,
                actionLabel = "Reload",
                duration = SnackbarDuration.Long
            )
            if (result == SnackbarResult.ActionPerformed) viewModel.load()
        } else {
            snackbarHostState.showSnackbar(err)
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            BrainTopBar(
                title = "Memory (memory.md)",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateBack = onNavigateBack,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    if (uiState.isSaving) {
                        CircularProgressIndicator(modifier = Modifier.padding(12.dp))
                    } else {
                        IconButton(
                            onClick = { viewModel.save() },
                            // Saving is blocked when the file could not be read: writing the
                            // box's contents over a memory file we never managed to see is how
                            // this screen used to destroy it.
                            enabled = !uiState.isLoading && !uiState.loadFailed
                        ) {
                            Icon(
                                imageVector = if (uiState.savedOk) Icons.Filled.Check else Icons.Filled.Save,
                                contentDescription = "Save",
                                tint = if (uiState.savedOk) MaterialTheme.colorScheme.primary
                                       else MaterialTheme.colorScheme.onSurface
                            )
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
        } else if (uiState.loadFailed) {
            // Deliberately not an editable box with a fallback in it. An editor showing
            // *something* invites a Save, and a Save here would overwrite the real file with
            // whatever placeholder we had chosen.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Couldn't load your memory file",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "It hasn't been changed. Editing is off until it can be read, so " +
                        "nothing here can overwrite it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
                Button(onClick = { viewModel.load() }) { Text("Try again") }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .imePadding()
            ) {
                Text(
                    text = "This file is sent as context at the start of every Claude conversation.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)
                )
                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    OutlinedTextField(
                        value = memoryFieldValue,
                        onValueChange = { newValue ->
                            memoryFieldValue = newValue
                            viewModel.onContentChange(newValue.text)
                        },
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(8.dp),
                        textStyle = MaterialTheme.typography.bodySmall.copy(
                            fontFamily = FontFamily.Monospace
                        ),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color.Transparent,
                            unfocusedBorderColor = Color.Transparent
                        ),
                        placeholder = { Text("Your memory file is empty.") }
                    )
                }
            }
        }
    }
}
