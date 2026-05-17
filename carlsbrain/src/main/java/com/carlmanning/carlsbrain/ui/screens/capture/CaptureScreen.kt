package com.carlmanning.carlsbrain.ui.screens.capture

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.model.Priority

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptureScreen(
    onDismiss: () -> Unit,
    viewModel: CaptureViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()

    val selectedBucket = buckets.find { it.id == uiState.selectedBucketId }
        ?: buckets.find { it.name == "Other" }
        ?: buckets.lastOrNull()

    var bucketExpanded by remember { mutableStateOf(false) }

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
            value = uiState.text,
            onValueChange = viewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text(if (uiState.captureType == CaptureType.NOTE) "Write your note…" else "What's on your mind?") },
            minLines = 3,
            trailingIcon = {
                if (uiState.captureType == CaptureType.TODO) {
                    IconButton(onClick = { /* voice capture — coming soon */ }) {
                        Icon(Icons.Filled.Mic, contentDescription = "Voice input")
                    }
                }
            }
        )

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
                    CircularProgressIndicator(modifier = Modifier.width(20.dp).height(20.dp))
                } else {
                    Text("Save")
                }
            }
        }
    }
}
