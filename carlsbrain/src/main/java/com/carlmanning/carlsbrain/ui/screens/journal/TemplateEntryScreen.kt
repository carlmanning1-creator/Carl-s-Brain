package com.carlmanning.carlsbrain.ui.screens.journal

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.journal.FieldType
import com.carlmanning.carlsbrain.domain.journal.OTHER_OPTION
import com.carlmanning.carlsbrain.domain.journal.TemplateField

/**
 * Fills in one templated entry: the template's fields, then the free-text box, the private
 * toggle and Save — in that order, as Carl specified.
 *
 * A screen of its own rather than the inline composer on the Journal list: five anchored scales
 * plus four option lists does not belong in a card, and giving it a route means a draft can be
 * saved on back-navigation without special-casing the list screen.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateEntryScreen(
    templateId: Long?,
    entryId: Long?,
    onDone: () -> Unit,
    viewModel: TemplateEntryViewModel = viewModel()
) {
    LaunchedEffect(templateId, entryId) { viewModel.load(templateId, entryId) }
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val cachedPhotos by viewModel.cachedPhotos.collectAsStateWithLifecycle()

    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addPhoto(it) } }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri -> uri?.let { viewModel.addFile(it) } }

    // Back saves the draft rather than discarding it. Silent on purpose — a "save draft?"
    // prompt on every exit is the friction this app exists to remove.
    BackHandler {
        viewModel.saveDraftIfNeeded()
        onDone()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(state.templateName.ifBlank { "Entry" }) },
                navigationIcon = {
                    IconButton(onClick = {
                        viewModel.saveDraftIfNeeded()
                        onDone()
                    }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        if (state.isLoading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            return@Scaffold
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp)
        ) {
            state.fields.forEach { field ->
                when (field.type) {
                    FieldType.SCALE -> ScaleField(
                        field = field,
                        value = state.answers[field.id]?.number,
                        onPick = { viewModel.toggleScale(field.id, it) }
                    )
                    FieldType.CHOICE, FieldType.MULTI_CHOICE -> ChoiceField(
                        field = field,
                        options = optionsFor(field, state),
                        selected = state.answers[field.id]?.choices.orEmpty(),
                        otherText = state.answers[field.id]?.otherText.orEmpty(),
                        onPick = { option ->
                            if (field.type == FieldType.CHOICE) {
                                viewModel.setSingleChoice(field.id, option)
                            } else {
                                viewModel.toggleMultiChoice(field.id, option)
                            }
                        },
                        onOtherText = { viewModel.setOtherText(field.id, it) }
                    )
                    FieldType.TEXT, FieldType.LONG_TEXT -> {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text(field.label, style = MaterialTheme.typography.titleSmall)
                            OutlinedTextField(
                                value = state.answers[field.id]?.text.orEmpty(),
                                onValueChange = { viewModel.setText(field.id, it) },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = if (field.type == FieldType.LONG_TEXT) 3 else 1
                            )
                        }
                    }
                }
            }

            // The free-text box every template carries, underneath the fixed options.
            // No character limit — deliberately unbounded.
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Anything else", style = MaterialTheme.typography.titleSmall)
                OutlinedTextField(
                    value = state.freeText,
                    onValueChange = viewModel::setFreeText,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Write whatever is there.") },
                    minLines = 5
                )
            }

            // Attachments sit between the free text and the private toggle: they belong to the
            // entry as a whole rather than to any one question, so they cannot live among the
            // fields, and putting them below Save would be below the fold.
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                TextButton(
                    onClick = { photoPicker.launch("image/*") },
                    enabled = !state.isUploadingAttachment
                ) {
                    Icon(Icons.Filled.Image, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Photo")
                }
                TextButton(
                    onClick = { filePicker.launch("*/*") },
                    enabled = !state.isUploadingAttachment
                ) {
                    Icon(Icons.Filled.AttachFile, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("File")
                }
                if (state.isUploadingAttachment) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp))
                }
            }
            if (state.attachments.isNotEmpty()) {
                AttachmentStrip(
                    attachments = state.attachments,
                    photos = cachedPhotos,
                    onRemove = viewModel::removeAttachment
                )
            }

            if (state.buckets.isNotEmpty()) {
                var bucketMenu by remember { mutableStateOf(false) }
                Box {
                    OutlinedButton(onClick = { bucketMenu = true }) {
                        Text(state.buckets.find { it.id == state.bucketId }?.name ?: "No bucket")
                    }
                    DropdownMenu(bucketMenu, onDismissRequest = { bucketMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("No bucket") },
                            onClick = { viewModel.setBucket(null); bucketMenu = false }
                        )
                        state.buckets.forEach { bucket ->
                            DropdownMenuItem(
                                text = { Text(bucket.name) },
                                onClick = { viewModel.setBucket(bucket.id); bucketMenu = false }
                            )
                        }
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = if (state.isPrivate) Icons.Filled.Lock else Icons.Filled.LockOpen,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.size(6.dp))
                Text("Private", style = MaterialTheme.typography.bodyMedium)
                Switch(
                    checked = state.isPrivate,
                    onCheckedChange = viewModel::setPrivate,
                    modifier = Modifier.padding(start = 8.dp)
                )
            }

            FilledTonalButton(
                onClick = { viewModel.save(); onDone() },
                enabled = !state.isEmpty,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isEditingSaved) "Save changes" else "Save entry")
            }

            Spacer(Modifier.height(32.dp))
        }
    }
}

/**
 * Options a field should offer, with anything already answered elsewhere removed.
 *
 * Backs Secondary Activities: whatever is picked as Main disappears from it, so one activity
 * cannot be recorded twice for a single session and skew a later frequency count.
 *
 * Deliberately a pure function of [state] rather than a ViewModel method reading its own
 * StateFlow — Compose only recomposes on state it can see being read, so a method call would
 * have left the Secondary list frozen at whatever Main held when the screen first drew.
 */
private fun optionsFor(field: TemplateField, state: TemplateEntryUiState): List<String> {
    val all = state.options[field.id].orEmpty()
    if (field.excludeAnswersOf.isBlank()) return all
    val taken = state.answers[field.excludeAnswersOf]?.choices.orEmpty().toSet()
    // Anything already picked here stays visible, or an option would vanish mid-edit.
    val selectedHere = state.answers[field.id]?.choices.orEmpty().toSet()
    return all.filter { it !in taken || it in selectedHere }
}

/**
 * A 1–10 row with the anchors above it.
 *
 * Buttons rather than a slider: one tap lands exactly on the number, which matters when there
 * are five of these to fill in at 5:30am. Both anchors stay on screen, which is the whole point
 * of having written them — a bare number is not worth recording.
 */
@Composable
private fun ScaleField(
    field: TemplateField,
    value: Int?,
    onPick: (Int) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, style = MaterialTheme.typography.titleSmall)
        if (field.minAnchor.isNotBlank() || field.maxAnchor.isNotBlank()) {
            Row(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "${field.min} · ${field.minAnchor}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Text(
                    text = "${field.maxAnchor} · ${field.max}",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    modifier = Modifier.weight(1f)
                )
            }
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            for (n in field.min..field.max) {
                val selected = value == n
                if (selected) {
                    FilledTonalButton(
                        onClick = { onPick(n) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("$n") }
                } else {
                    OutlinedButton(
                        onClick = { onPick(n) },
                        modifier = Modifier.weight(1f),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)
                    ) { Text("$n") }
                }
            }
        }
    }
}

/** Single- or multi-select chips, with "Other" revealing a box to say what. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceField(
    field: TemplateField,
    options: List<String>,
    selected: List<String>,
    otherText: String,
    onPick: (String) -> Unit,
    onOtherText: (String) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(field.label, style = MaterialTheme.typography.titleSmall)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            options.forEach { option ->
                FilterChip(
                    selected = option in selected,
                    onClick = { onPick(option) },
                    label = { Text(option) }
                )
            }
        }
        // Only once Other is actually chosen — an always-visible box would read as a required
        // field and add noise to every entry.
        if (field.allowOther && OTHER_OPTION in selected) {
            OutlinedTextField(
                value = otherText,
                onValueChange = onOtherText,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Other — what?") },
                singleLine = true
            )
        }
    }
}
