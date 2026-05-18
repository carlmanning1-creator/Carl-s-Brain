package com.carlmanning.carlsbrain.ui.screens.notes

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
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
    onNavigateToSearch: () -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: NotesViewModel = viewModel()
) {
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val selectedBucketId by viewModel.selectedBucketId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    var sortExpanded by remember { mutableStateOf(false) }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.reorderNote(from.index, to.index)
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Notes",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
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
            // Single scrollable row: bucket chips + sort chip on the right
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
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
                // Tag filter chips
                if (allTags.isNotEmpty()) {
                    allTags.forEach { tag ->
                        FilterChip(
                            selected = selectedTag == tag,
                            onClick = { viewModel.selectTag(if (selectedTag == tag) null else tag) },
                            label = { Text("#$tag", style = MaterialTheme.typography.labelSmall) }
                        )
                    }
                }
                // Sort chip pinned after bucket chips
                Box {
                    FilterChip(
                        selected = sortMode != NotesSortMode.UPDATED,
                        onClick = { sortExpanded = true },
                        label = { Text(sortMode.label, style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = {
                            Icon(Icons.Filled.ArrowDropDown, contentDescription = null,
                                modifier = Modifier.size(14.dp))
                        }
                    )
                    DropdownMenu(
                        expanded = sortExpanded,
                        onDismissRequest = { sortExpanded = false }
                    ) {
                        NotesSortMode.entries.forEach { mode ->
                            DropdownMenuItem(
                                text = { Text(mode.label) },
                                onClick = { viewModel.setSortMode(mode); sortExpanded = false }
                            )
                        }
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
                    state = lazyListState,
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        ReorderableItem(reorderState, key = note.id) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (sortMode == NotesSortMode.MANUAL) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(24.dp)
                                            .draggableHandle(),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    NoteCard(
                                        note = note,
                                        bucket = buckets.find { it.id == note.bucketId },
                                        onClick = { onOpenNote(note.id) },
                                        onLongClick = { viewModel.pinNote(note.id, !note.isPinned) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    bucket: BucketEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {}
) {
    val dateFmt = SimpleDateFormat("d MMM, HH:mm", Locale.getDefault())
    val displayTitle = note.title.ifBlank { note.content.lines().first().take(60) }
    val preview = if (note.title.isBlank()) {
        note.content.lines().drop(1).joinToString(" ").trim().take(120)
    } else {
        note.content.take(120)
    }

    val bucketColor = try {
        Color(android.graphics.Color.parseColor(bucket?.colorHex ?: "#6750A4"))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(bucketColor)
            )
        Column(modifier = Modifier.weight(1f).padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top
            ) {
                Text(
                    text = displayTitle,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                if (note.isPinned) {
                    Icon(
                        imageVector = Icons.Filled.PushPin,
                        contentDescription = "Pinned",
                        modifier = Modifier.size(14.dp).padding(start = 4.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
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
        } // end outer Row (IntrinsicSize.Min)
    }
}
