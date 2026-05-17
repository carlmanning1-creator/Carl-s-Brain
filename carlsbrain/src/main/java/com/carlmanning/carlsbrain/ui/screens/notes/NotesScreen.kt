package com.carlmanning.carlsbrain.ui.screens.notes

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onOpenNote: (Long) -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: NotesViewModel = viewModel()
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val selectedBucketId by viewModel.selectedBucketId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Notes",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCapture) {
                Icon(Icons.Filled.Add, contentDescription = "New note")
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            OutlinedTextField(
                value = searchQuery,
                onValueChange = viewModel::onSearchChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                placeholder = { Text("Search notes…") },
                leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                singleLine = true
            )

            if (buckets.isNotEmpty()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FilterChip(
                        selected = selectedBucketId == null,
                        onClick = { viewModel.selectBucket(null) },
                        label = { Text("All") }
                    )
                    buckets.forEach { bucket ->
                        FilterChip(
                            selected = selectedBucketId == bucket.id,
                            onClick = { viewModel.selectBucket(bucket.id) },
                            label = { Text(bucket.name) }
                        )
                    }
                }
            }

            if (notes.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (searchQuery.isNotBlank()) "No notes match your search"
                               else "No notes yet — tap + to capture one",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            bucket = buckets.find { it.id == note.bucketId },
                            onClick = { onOpenNote(note.id) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NoteCard(
    note: NoteEntity,
    bucket: BucketEntity?,
    onClick: () -> Unit
) {
    val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    val displayTitle = note.title.ifBlank { note.content.lines().first().take(60) }
    val preview = if (note.title.isBlank()) {
        note.content.lines().drop(1).joinToString(" ").trim().take(120)
    } else {
        note.content.take(120)
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = displayTitle,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (preview.isNotBlank()) {
                Text(
                    text = preview,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (bucket != null) {
                    SuggestionChip(
                        onClick = {},
                        label = { Text(bucket.name, style = MaterialTheme.typography.labelSmall) }
                    )
                }
                Text(
                    text = dateFmt.format(Date(note.updatedAt)),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
