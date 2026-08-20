package com.carlmanning.carlsbrain.ui.screens.journal

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.JournalOptionListEntity
import com.carlmanning.carlsbrain.domain.journal.FieldType
import com.carlmanning.carlsbrain.domain.journal.JournalReminderScheduler
import com.carlmanning.carlsbrain.domain.journal.TemplateField

/**
 * Where Carl builds his own templates: create, edit, reorder and delete them and their fields,
 * and edit the shared option lists two fields can point at.
 *
 * Reached from the Journal screen rather than Settings, so a template can be fixed from the
 * place its shortcomings become obvious.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TemplateManagerScreen(
    onNavigateBack: () -> Unit,
    viewModel: TemplateManagerViewModel = viewModel()
) {
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val optionLists by viewModel.optionLists.collectAsStateWithLifecycle()
    val editing by viewModel.editing.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()

    var editingList by remember { mutableStateOf<JournalOptionListEntity?>(null) }
    var confirmDelete by remember { mutableStateOf<Long?>(null) }

    editing?.let { draft ->
        TemplateEditorDialog(
            draft = draft,
            optionLists = optionLists,
            buckets = buckets,
            viewModel = viewModel,
            onDismiss = viewModel::cancelEdit
        )
    }

    editingList?.let { list ->
        OptionListDialog(
            list = list,
            initialOptions = viewModel.optionsOf(list),
            onSave = { name, options ->
                viewModel.saveOptionList(list.id, name, options)
                editingList = null
            },
            onDismiss = { editingList = null }
        )
    }

    confirmDelete?.let { id ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete template?") },
            text = {
                Text(
                    "Entries already written from it are kept and still read correctly — each " +
                        "one stores its own copy of the questions."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.deleteTemplate(id)
                    confirmDelete = null
                }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmDelete = null }) { Text("Cancel") }
            }
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Journal templates") },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    IconButton(onClick = viewModel::newTemplate) {
                        Icon(Icons.Filled.Add, contentDescription = "New template")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(templates, key = { it.id }) { template ->
                Card {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(template.name, style = MaterialTheme.typography.titleMedium)
                                val fields = viewModel.fieldsOf(template)
                                Text(
                                    text = buildString {
                                        append("${fields.size} field${if (fields.size == 1) "" else "s"}")
                                        if (template.isPrivateByDefault) append(" · private by default")
                                    },
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            IconButton(onClick = { viewModel.editTemplate(template) }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit")
                            }
                            IconButton(onClick = { confirmDelete = template.id }) {
                                Icon(
                                    Icons.Filled.Delete,
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(8.dp))
                HorizontalDivider()
                Spacer(Modifier.height(8.dp))
                Text("Option lists", style = MaterialTheme.typography.titleMedium)
                Text(
                    text = "Shared between fields. Editing one here updates every template " +
                        "that uses it — entries already written keep what they recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            items(optionLists, key = { "list_${it.id}" }) { list ->
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(list.name, style = MaterialTheme.typography.titleSmall)
                            IconButton(onClick = { editingList = list }) {
                                Icon(Icons.Filled.Edit, contentDescription = "Edit options")
                            }
                        }
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            viewModel.optionsOf(list).forEach {
                                AssistChip(onClick = {}, label = { Text(it) })
                            }
                        }
                    }
                }
            }

            item { Spacer(Modifier.height(64.dp)) }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TemplateEditorDialog(
    draft: TemplateDraft,
    optionLists: List<JournalOptionListEntity>,
    buckets: List<com.carlmanning.carlsbrain.data.local.entity.BucketEntity>,
    viewModel: TemplateManagerViewModel,
    onDismiss: () -> Unit
) {
    var addMenuOpen by remember { mutableStateOf(false) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (draft.id == 0L) "New template" else "Edit template") },
        text = {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item {
                    OutlinedTextField(
                        value = draft.name,
                        onValueChange = viewModel::setName,
                        label = { Text("Name") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Private by default", Modifier.weight(1f))
                        Switch(
                            checked = draft.isPrivateByDefault,
                            onCheckedChange = viewModel::setPrivateByDefault
                        )
                    }
                }
                item {
                    var bucketMenu by remember { mutableStateOf(false) }
                    Box {
                        OutlinedButton(onClick = { bucketMenu = true }) {
                            Text(buckets.find { it.id == draft.bucketId }?.name ?: "No bucket")
                        }
                        DropdownMenu(bucketMenu, onDismissRequest = { bucketMenu = false }) {
                            DropdownMenuItem(
                                text = { Text("No bucket") },
                                onClick = { viewModel.setTemplateBucket(null); bucketMenu = false }
                            )
                            buckets.forEach { bucket ->
                                DropdownMenuItem(
                                    text = { Text(bucket.name) },
                                    onClick = {
                                        viewModel.setTemplateBucket(bucket.id)
                                        bucketMenu = false
                                    }
                                )
                            }
                        }
                    }
                }
                item {
                    OutlinedTextField(
                        value = draft.reminderRule,
                        onValueChange = viewModel::setReminderRule,
                        label = { Text("Remind me (e.g. SUN:10:00)") },
                        supportingText = {
                            Text(
                                if (draft.reminderRule.isBlank()) "Leave empty for no reminder"
                                else JournalReminderScheduler.describe(draft.reminderRule)
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                itemsIndexed(draft.fields) { index, field ->
                    FieldEditor(
                        field = field,
                        optionLists = optionLists,
                        onChange = { viewModel.updateField(index) { _ -> it } },
                        onRemove = { viewModel.removeField(index) },
                        onMoveUp = { viewModel.moveField(index, -1) },
                        onMoveDown = { viewModel.moveField(index, 1) }
                    )
                }
                item {
                    Box {
                        OutlinedButton(onClick = { addMenuOpen = true }) {
                            Icon(Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.size(6.dp))
                            Text("Add field")
                        }
                        DropdownMenu(
                            expanded = addMenuOpen,
                            onDismissRequest = { addMenuOpen = false }
                        ) {
                            FieldType.entries.forEach { type ->
                                DropdownMenuItem(
                                    text = { Text(type.friendlyName()) },
                                    onClick = {
                                        viewModel.addField(type)
                                        addMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = viewModel::saveTemplate,
                enabled = draft.name.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

@Composable
private fun FieldEditor(
    field: TemplateField,
    optionLists: List<JournalOptionListEntity>,
    onChange: (TemplateField) -> Unit,
    onRemove: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit
) {
    var listMenuOpen by remember { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)
        )
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = field.type.friendlyName(),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onMoveUp, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ArrowUpward, "Move up", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onMoveDown, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.ArrowDownward, "Move down", modifier = Modifier.size(16.dp))
                }
                IconButton(onClick = onRemove, modifier = Modifier.size(28.dp)) {
                    Icon(
                        Icons.Filled.Delete, "Remove field",
                        modifier = Modifier.size(16.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                }
            }

            OutlinedTextField(
                value = field.label,
                onValueChange = { onChange(field.copy(label = it)) },
                label = { Text("Question") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            when (field.type) {
                FieldType.SCALE -> {
                    // Anchors are not decoration: without them a score is uninterpretable a
                    // year later, which defeats the point of recording it as a number.
                    OutlinedTextField(
                        value = field.minAnchor,
                        onValueChange = { onChange(field.copy(minAnchor = it)) },
                        label = { Text("${field.min} means…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = field.maxAnchor,
                        onValueChange = { onChange(field.copy(maxAnchor = it)) },
                        label = { Text("${field.max} means…") },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
                FieldType.CHOICE, FieldType.MULTI_CHOICE -> {
                    Box {
                        OutlinedButton(onClick = { listMenuOpen = true }) {
                            Text(
                                optionLists.find { it.id == field.optionListId }?.name
                                    ?: "Own options"
                            )
                        }
                        DropdownMenu(
                            expanded = listMenuOpen,
                            onDismissRequest = { listMenuOpen = false }
                        ) {
                            DropdownMenuItem(
                                text = { Text("Own options") },
                                onClick = {
                                    onChange(field.copy(optionListId = 0L))
                                    listMenuOpen = false
                                }
                            )
                            optionLists.forEach { list ->
                                DropdownMenuItem(
                                    text = { Text(list.name) },
                                    onClick = {
                                        onChange(field.copy(optionListId = list.id, inlineOptions = emptyList()))
                                        listMenuOpen = false
                                    }
                                )
                            }
                        }
                    }
                    if (field.optionListId == 0L) {
                        OutlinedTextField(
                            value = field.inlineOptions.joinToString(", "),
                            onValueChange = {
                                onChange(
                                    field.copy(
                                        inlineOptions = it.split(",")
                                            .map { o -> o.trim() }
                                            .filter { o -> o.isNotBlank() }
                                    )
                                )
                            },
                            label = { Text("Options, comma separated") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("\"Other\" asks what", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        Switch(
                            checked = field.allowOther,
                            onCheckedChange = { onChange(field.copy(allowOther = it)) }
                        )
                    }
                }
                FieldType.TEXT, FieldType.LONG_TEXT -> Unit
            }
        }
    }
}

@Composable
private fun OptionListDialog(
    list: JournalOptionListEntity,
    initialOptions: List<String>,
    onSave: (String, List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(list.name) }
    var text by remember { mutableStateOf(initialOptions.joinToString("\n")) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit options") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("List name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    label = { Text("One option per line") },
                    minLines = 6,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "Removing an option here does not change entries already written — " +
                        "each one stores the text it recorded.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(name, text.lines()) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } }
    )
}

private fun FieldType.friendlyName(): String = when (this) {
    FieldType.SCALE -> "Scale 1–10"
    FieldType.CHOICE -> "Pick one"
    FieldType.MULTI_CHOICE -> "Pick several"
    FieldType.TEXT -> "Short text"
    FieldType.LONG_TEXT -> "Long text"
}
