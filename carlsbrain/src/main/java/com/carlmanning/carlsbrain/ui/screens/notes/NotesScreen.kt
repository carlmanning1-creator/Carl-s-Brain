package com.carlmanning.carlsbrain.ui.screens.notes

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.carlmanning.carlsbrain.ui.components.ConfirmDeleteDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.ui.components.BrainFab
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotesScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: NotesViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val selectedBucketId by viewModel.selectedBucketId.collectAsStateWithLifecycle()
    val searchQuery by viewModel.searchQuery.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val selectedTag by viewModel.selectedTag.collectAsStateWithLifecycle()
    val hiddenVaultCount by viewModel.hiddenVaultCount.collectAsStateWithLifecycle()
    var sortExpanded by remember { mutableStateOf(false) }

    // ── Multi-select (screen-local, not persisted) ──────────────────
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var bulkBucketExpanded by remember { mutableStateOf(false) }
    var showBulkDeleteConfirm by remember { mutableStateOf(false) }

    fun exitSelection() {
        selectedIds.clear()
        selectionMode = false
        bulkBucketExpanded = false
    }

    fun toggleSelection(id: Long) {
        if (selectedIds.contains(id)) selectedIds.remove(id) else selectedIds.add(id)
        if (selectedIds.isEmpty()) selectionMode = false
    }

    BackHandler(enabled = selectionMode) { exitSelection() }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.reorderNote(from.index, to.index)
    }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    val onNoteDeleted: (NoteEntity) -> Unit = { deleted ->
        viewModel.deleteNote(deleted)
        scope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Note deleted",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.restoreNote(deleted.id)
            }
        }
    }

    // Bulk delete confirmation
    if (showBulkDeleteConfirm) {
        ConfirmDeleteDialog(
            itemType = "${selectedIds.size} notes",
            isRecoverable = true,
            onConfirm = {
                viewModel.bulkDelete(selectedIds.toList())
                showBulkDeleteConfirm = false
                exitSelection()
            },
            onDismiss = { showBulkDeleteConfirm = false }
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            if (selectionMode) {
                val allPinned = selectedIds.isNotEmpty() &&
                    notes.filter { selectedIds.contains(it.id) }.all { it.isPinned }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            viewModel.bulkPin(selectedIds.toList(), !allPinned); exitSelection()
                        }) {
                            Icon(
                                Icons.Filled.PushPin,
                                contentDescription = if (allPinned) "Unpin" else "Pin"
                            )
                        }
                        Box {
                            IconButton(onClick = { bulkBucketExpanded = true }) {
                                Icon(Icons.Filled.DriveFileMove, contentDescription = "Move to bucket")
                            }
                            DropdownMenu(
                                expanded = bulkBucketExpanded,
                                onDismissRequest = { bulkBucketExpanded = false }
                            ) {
                                buckets.forEach { bucket ->
                                    DropdownMenuItem(
                                        text = { Text(bucket.name) },
                                        onClick = {
                                            viewModel.bulkMoveToBucket(selectedIds.toList(), bucket.id)
                                            bulkBucketExpanded = false
                                            exitSelection()
                                        }
                                    )
                                }
                            }
                        }
                        IconButton(onClick = { showBulkDeleteConfirm = true }) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete")
                        }
                    }
                )
            } else {
            BrainTopBar(
                title = "Notes",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    IconButton(onClick = onNavigateToChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                    }
                }
            )
            }
        },
        floatingActionButton = {
            BrainFab(
                icon = Icons.Filled.Add,
                contentDescription = "New note",
                onClick = onNavigateToCapture
            )
        }
    ) { innerPadding ->
        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            // Outer row: scrollable filter chips + pinned sort chip
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                }
                // Sort chip pinned outside the scroll area
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
                val filtersActive = selectedBucketId != null || selectedTag != null
                Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                        when {
                            filtersActive -> EmptyState(
                                icon = Icons.Filled.FilterAltOff,
                                title = "No notes match this filter",
                                subtitle = null,
                                actionLabel = "Clear filters",
                                onAction = {
                                    viewModel.selectBucket(null)
                                    viewModel.selectTag(null)
                                }
                            )
                            searchQuery.isNotBlank() -> EmptyState(
                                icon = Icons.AutoMirrored.Filled.Notes,
                                title = "No notes match your search",
                                subtitle = null
                            )
                            else -> EmptyState(
                                icon = Icons.AutoMirrored.Filled.Notes,
                                title = "No notes yet",
                                subtitle = "Tap + to write one."
                            )
                        }
                    }
                    if (!isVaultVisible && hiddenVaultCount > 0) {
                        VaultHiddenLine(count = hiddenVaultCount, onClick = onVaultToggle)
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = 88.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(notes, key = { it.id }) { note ->
                        val isSelected = selectedIds.contains(note.id)
                        val onRowClick: () -> Unit = {
                            if (selectionMode) toggleSelection(note.id) else onOpenNote(note.id)
                        }
                        val onRowLongClick: () -> Unit = {
                            if (!selectionMode) selectionMode = true
                            if (!selectedIds.contains(note.id)) selectedIds.add(note.id)
                        }
                        ReorderableItem(reorderState, key = note.id) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        if (isSelected)
                                            MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
                                        else Color.Transparent
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (selectionMode) {
                                    Icon(
                                        imageVector = if (isSelected) Icons.Filled.CheckCircle
                                                      else Icons.Outlined.Circle,
                                        contentDescription = if (isSelected) "Selected" else "Not selected",
                                        modifier = Modifier
                                            .padding(end = 4.dp)
                                            .size(24.dp)
                                            .clickable { toggleSelection(note.id) },
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (sortMode == NotesSortMode.MANUAL && !selectionMode) {
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
                                    if (selectionMode) {
                                        NoteCard(
                                            note = note,
                                            bucket = buckets.find { it.id == note.bucketId },
                                            onClick = onRowClick,
                                            onLongClick = onRowLongClick,
                                            isSelected = isSelected
                                        )
                                    } else {
                                    val dismissState = rememberSwipeToDismissBoxState(
                                        confirmValueChange = { it != SwipeToDismissBoxValue.Settled }
                                    )
                                    LaunchedEffect(dismissState.currentValue) {
                                        when (dismissState.currentValue) {
                                            SwipeToDismissBoxValue.StartToEnd -> {
                                                viewModel.pinNote(note.id, !note.isPinned)
                                                dismissState.reset()
                                            }
                                            SwipeToDismissBoxValue.EndToStart -> onNoteDeleted(note)
                                            else -> {}
                                        }
                                    }
                                    SwipeToDismissBox(
                                        state = dismissState,
                                        backgroundContent = {
                                            val bgColor by animateColorAsState(
                                                targetValue = when (dismissState.targetValue) {
                                                    SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.primaryContainer
                                                    SwipeToDismissBoxValue.EndToStart -> MaterialTheme.colorScheme.errorContainer
                                                    else -> MaterialTheme.colorScheme.surfaceVariant
                                                },
                                                label = "noteSwipeBg"
                                            )
                                            Box(
                                                modifier = Modifier
                                                    .fillMaxSize()
                                                    .background(bgColor)
                                                    .padding(horizontal = 20.dp),
                                                contentAlignment = when (dismissState.targetValue) {
                                                    SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                                    else -> Alignment.CenterEnd
                                                }
                                            ) {
                                                Icon(
                                                    imageVector = when (dismissState.targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd ->
                                                            if (note.isPinned) Icons.Filled.PushPin else Icons.Filled.PushPin
                                                        else -> Icons.Filled.Delete
                                                    },
                                                    contentDescription = null,
                                                    tint = when (dismissState.targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd -> MaterialTheme.colorScheme.onPrimaryContainer
                                                        else -> MaterialTheme.colorScheme.onErrorContainer
                                                    }
                                                )
                                            }
                                        }
                                    ) {
                                        NoteCard(
                                            note = note,
                                            bucket = buckets.find { it.id == note.bucketId },
                                            onClick = onRowClick,
                                            onLongClick = onRowLongClick,
                                            isSelected = isSelected
                                        )
                                    }
                                    } // end selectionMode else
                                }
                            }
                        }
                    }

                    if (!isVaultVisible && hiddenVaultCount > 0) {
                        item {
                            VaultHiddenLine(count = hiddenVaultCount, onClick = onVaultToggle)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun VaultHiddenLine(count: Int, onClick: () -> Unit) {
    Text(
        text = "🔒 $count hidden in Vault",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.outline,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(16.dp),
        textAlign = TextAlign.Center
    )
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NoteCard(
    note: NoteEntity,
    bucket: BucketEntity?,
    onClick: () -> Unit,
    onLongClick: () -> Unit = {},
    isSelected: Boolean = false
) {
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
            containerColor = if (isSelected)
                MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
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
                    maxLines = 1,
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
                    text = formatSmartDateTime(note.updatedAt),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        } // end outer Row (IntrinsicSize.Min)
    }
}
