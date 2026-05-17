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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
    captureViewModel: CaptureViewModel = viewModel()
) {
    val uiState by captureViewModel.uiState.collectAsStateWithLifecycle()

    val buckets = listOf("SES", "Family", "Work", "Personal", "Other")
    var bucketExpanded by remember { mutableStateOf(false) }
    var selectedBucketName by remember { mutableStateOf(buckets.first()) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "Quick Capture",
            style = MaterialTheme.typography.titleLarge
        )

        OutlinedTextField(
            value = uiState.text,
            onValueChange = captureViewModel::onTextChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = { Text("What's on your mind?") },
            minLines = 3,
            trailingIcon = {
                IconButton(onClick = { /* voice capture placeholder */ }) {
                    Icon(Icons.Filled.Mic, contentDescription = "Voice input")
                }
            }
        )

        ExposedDropdownMenuBox(
            expanded = bucketExpanded,
            onExpandedChange = { bucketExpanded = it }
        ) {
            OutlinedTextField(
                value = selectedBucketName,
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
                        text = { Text(bucket) },
                        onClick = {
                            selectedBucketName = bucket
                            bucketExpanded = false
                        }
                    )
                }
            }
        }

        Text("Priority", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Priority.entries.forEach { priority ->
                FilterChip(
                    selected = uiState.selectedPriority == priority,
                    onClick = { captureViewModel.onPrioritySelected(priority) },
                    label = { Text(priority.displayName) }
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End
        ) {
            OutlinedButton(onClick = onDismiss) {
                Text("Cancel")
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { captureViewModel.save(onDismiss) },
                enabled = uiState.text.isNotBlank()
            ) {
                Text("Save")
            }
        }
    }
}
