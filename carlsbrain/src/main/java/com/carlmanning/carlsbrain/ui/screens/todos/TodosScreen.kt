package com.carlmanning.carlsbrain.ui.screens.todos

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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.DriveFileMove
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.FilterAltOff
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.ViewColumn
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.carlmanning.carlsbrain.ui.components.ConfirmDeleteDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.domain.model.Todo
import com.carlmanning.carlsbrain.ui.components.BrainFab
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDate
import kotlinx.coroutines.launch
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TodosScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    onNavigateToHistory: () -> Unit,
    onOpenTodo: (Long) -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: TodosViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val todos by viewModel.todos.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val bucketList by viewModel.bucketList.collectAsStateWithLifecycle()
    val subtasksMap by viewModel.subtasksMap.collectAsStateWithLifecycle()
    val selectedPriority by viewModel.selectedPriority.collectAsStateWithLifecycle()
    val selectedBucketId by viewModel.selectedBucketId.collectAsStateWithLifecycle()
    val sortMode by viewModel.sortMode.collectAsStateWithLifecycle()
    val swipeToCompleteEnabled by viewModel.swipeToCompleteEnabled.collectAsStateWithLifecycle()
    val prioritisationResult by viewModel.prioritisationResult.collectAsStateWithLifecycle()
    val isPrioritising by viewModel.isPrioritising.collectAsStateWithLifecycle()
    var priorityExpanded by remember { mutableStateOf(false) }
    var bucketExpanded by remember { mutableStateOf(false) }
    var sortExpanded by remember { mutableStateOf(false) }
    val kanbanMode by viewModel.kanbanMode.collectAsStateWithLifecycle()
    val overdueTodos by viewModel.overdueTodos.collectAsStateWithLifecycle()
    var overdueBannerDismissed by remember { mutableStateOf(false) }
    val hiddenVaultCount by viewModel.hiddenVaultCount.collectAsStateWithLifecycle()

    // ── Multi-select (screen-local, not persisted) ──────────────────
    var selectionMode by remember { mutableStateOf(false) }
    val selectedIds = remember { mutableStateListOf<Long>() }
    var bulkBucketExpanded by remember { mutableStateOf(false) }
    var showBulkArchiveConfirm by remember { mutableStateOf(false) }

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

    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()
    val filtersActive = selectedPriority != null || selectedBucketId != null

    /** Archive a todo and offer an Undo via snackbar. */
    fun archiveWithUndo(todoId: Long) {
        viewModel.archiveTodo(todoId)
        snackbarScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Archived",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.unarchiveTodo(todoId)
            }
        }
    }

    /**
     * Move every overdue to-do in the current list to today (time-of-day preserved) and offer
     * an Undo that restores each one's original due date.
     */
    fun rescheduleOverdueWithUndo() {
        viewModel.rescheduleOverdueToToday { moved ->
            if (moved == 0) return@rescheduleOverdueToToday
            snackbarScope.launch {
                val result = snackbarHostState.showSnackbar(
                    message = "Moved $moved to today",
                    actionLabel = "Undo",
                    duration = SnackbarDuration.Short
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.undoRescheduleOverdue()
                }
            }
        }
    }

    val lazyListState = rememberLazyListState()
    val reorderState = rememberReorderableLazyListState(lazyListState) { from, to ->
        viewModel.reorderTodo(from.index, to.index)
    }

    // Claude prioritisation result dialog
    if (prioritisationResult != null) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissPrioritisationResult() },
            title = { Text("Claude's suggestion") },
            text = {
                Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                    Text(prioritisationResult ?: "", style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissPrioritisationResult() }) { Text("Got it") }
            }
        )
    }

    // Bulk archive confirmation
    if (showBulkArchiveConfirm) {
        ConfirmDeleteDialog(
            itemType = "${selectedIds.size} to-dos",
            isRecoverable = true,
            onConfirm = {
                viewModel.bulkArchive(selectedIds.toList())
                showBulkArchiveConfirm = false
                exitSelection()
            },
            onDismiss = { showBulkArchiveConfirm = false }
        )
    }

    Scaffold(
        topBar = {
            if (selectionMode) {
                val allPinned = selectedIds.isNotEmpty() &&
                    todos.filter { selectedIds.contains(it.id) }.all { it.isPinned }
                TopAppBar(
                    navigationIcon = {
                        IconButton(onClick = { exitSelection() }) {
                            Icon(Icons.Filled.Close, contentDescription = "Exit selection")
                        }
                    },
                    title = { Text("${selectedIds.size} selected") },
                    actions = {
                        IconButton(onClick = {
                            viewModel.bulkMarkDone(selectedIds.toList()); exitSelection()
                        }) {
                            Icon(Icons.Filled.CheckCircle, contentDescription = "Mark done")
                        }
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
                                bucketList.forEach { bucket ->
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
                        IconButton(onClick = { showBulkArchiveConfirm = true }) {
                            Icon(Icons.Filled.Archive, contentDescription = "Archive")
                        }
                    }
                )
            } else {
            BrainTopBar(
                title = "To Do",
                isVaultVisible = isVaultVisible,
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    IconButton(onClick = { viewModel.prioritiseWithClaude() }, enabled = !isPrioritising) {
                        if (isPrioritising) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Psychology, contentDescription = "Prioritise with Claude")
                        }
                    }
                },
                overflowMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Chat") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Filled.Chat, null) },
                        onClick = { dismiss(); onNavigateToChat() }
                    )
                    DropdownMenuItem(
                        text = { Text("History") },
                        leadingIcon = { Icon(Icons.Filled.History, null) },
                        onClick = { dismiss(); onNavigateToHistory() }
                    )
                    DropdownMenuItem(
                        text = { Text(if (kanbanMode) "List view" else "Column view") },
                        leadingIcon = {
                            Icon(
                                if (kanbanMode) Icons.Filled.ViewList else Icons.Filled.ViewColumn,
                                null
                            )
                        },
                        onClick = { dismiss(); viewModel.setKanbanMode(!kanbanMode) }
                    )
                }
            )
            }
        },
        snackbarHost = { SnackbarHost(snackbarHostState) },
        floatingActionButton = {
            BrainFab(
                icon = Icons.Filled.Add,
                contentDescription = "Quick capture",
                onClick = onNavigateToCapture
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // ── Filter row ─────────────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Scrollable filter chips (Priority + Bucket)
                Row(
                    modifier = Modifier
                        .weight(1f)
                        .horizontalScroll(rememberScrollState())
                        .padding(start = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Priority chip
                    Box {
                        FilterChip(
                            selected = selectedPriority != null,
                            onClick = { priorityExpanded = true },
                            label = { Text(selectedPriority?.displayName ?: "Priority", style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                        DropdownMenu(expanded = priorityExpanded, onDismissRequest = { priorityExpanded = false }) {
                            DropdownMenuItem(text = { Text("All priorities") }, onClick = { viewModel.onPriorityFilterSelected(null); priorityExpanded = false })
                            Priority.entries.forEach { priority ->
                                DropdownMenuItem(text = { Text(priority.displayName) }, onClick = { viewModel.onPriorityFilterSelected(priority); priorityExpanded = false })
                            }
                        }
                    }

                    // Bucket chip
                    Box {
                        FilterChip(
                            selected = selectedBucketId != null,
                            onClick = { bucketExpanded = true },
                            label = { Text(buckets[selectedBucketId]?.name ?: "Bucket", style = MaterialTheme.typography.labelSmall) },
                            trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(14.dp)) }
                        )
                        DropdownMenu(expanded = bucketExpanded, onDismissRequest = { bucketExpanded = false }) {
                            DropdownMenuItem(text = { Text("All buckets") }, onClick = { viewModel.onBucketFilterSelected(null); bucketExpanded = false })
                            bucketList.forEach { bucket ->
                                DropdownMenuItem(text = { Text(bucket.name) }, onClick = { viewModel.onBucketFilterSelected(bucket.id); bucketExpanded = false })
                            }
                        }
                    }
                }

                // Sort chip pinned to right
                Box(modifier = Modifier.padding(horizontal = 8.dp)) {
                    FilterChip(
                        selected = sortMode != TodoSortMode.PRIORITY,
                        onClick = { sortExpanded = true },
                        label = { Text("Sort by", style = MaterialTheme.typography.labelSmall) },
                        trailingIcon = { Icon(Icons.Filled.ArrowDropDown, contentDescription = null, modifier = Modifier.size(14.dp)) }
                    )
                    DropdownMenu(expanded = sortExpanded, onDismissRequest = { sortExpanded = false }) {
                        TodoSortMode.entries.forEach { mode ->
                            DropdownMenuItem(text = { Text(mode.label) }, onClick = { viewModel.setSortMode(mode); sortExpanded = false })
                        }
                    }
                }

            }

            // ── Overdue rescue banner ───────────────────────────────
            if (overdueTodos.isNotEmpty() && !overdueBannerDismissed && !selectionMode) {
                OverdueBanner(
                    count = overdueTodos.size,
                    filtersActive = filtersActive,
                    onReschedule = { rescheduleOverdueWithUndo() },
                    onDismiss = { overdueBannerDismissed = true }
                )
            }

            // ── Todo list / Kanban ──────────────────────────────────
            if (kanbanMode) {
                KanbanView(
                    todos = todos,
                    buckets = buckets,
                    onOpenTodo = onOpenTodo
                )
            } else {
            if (todos.isEmpty()) {
                Column(modifier = Modifier.fillMaxSize()) {
                    if (filtersActive) {
                        EmptyState(
                            icon = Icons.Filled.FilterAltOff,
                            title = "No to-dos match this filter",
                            subtitle = null,
                            actionLabel = "Clear filters",
                            onAction = {
                                viewModel.onPriorityFilterSelected(null)
                                viewModel.onBucketFilterSelected(null)
                            },
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    } else {
                        EmptyState(
                            icon = Icons.Filled.CheckCircle,
                            title = "No to-dos yet",
                            subtitle = "Tap + to capture one.",
                            modifier = Modifier.fillMaxWidth().weight(1f)
                        )
                    }
                    if (!isVaultVisible && hiddenVaultCount > 0) {
                        VaultHiddenLine(count = hiddenVaultCount, onClick = onVaultToggle)
                    }
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        start = 12.dp, end = 12.dp, top = 4.dp, bottom = 88.dp
                    )
                ) {
                    items(todos, key = { it.id }) { todo ->
                        val isSelected = selectedIds.contains(todo.id)
                        val onRowClick: () -> Unit = {
                            if (selectionMode) toggleSelection(todo.id) else onOpenTodo(todo.id)
                        }
                        val onRowLongClick: () -> Unit = {
                            if (!selectionMode) selectionMode = true
                            if (!selectedIds.contains(todo.id)) selectedIds.add(todo.id)
                        }
                        ReorderableItem(reorderState, key = todo.id) {
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
                                            .padding(start = 4.dp, end = 4.dp)
                                            .size(24.dp)
                                            .clickable { toggleSelection(todo.id) },
                                        tint = if (isSelected) MaterialTheme.colorScheme.primary
                                               else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                if (sortMode == TodoSortMode.MANUAL && !selectionMode) {
                                    Icon(
                                        imageVector = Icons.Filled.DragHandle,
                                        contentDescription = "Drag to reorder",
                                        modifier = Modifier
                                            .padding(start = 4.dp)
                                            .size(24.dp)
                                            .draggableHandle(),
                                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Box(modifier = Modifier.weight(1f)) {
                                    if (swipeToCompleteEnabled && !selectionMode) {
                                        val dismissState = rememberSwipeToDismissBoxState(
                                            confirmValueChange = { it != SwipeToDismissBoxValue.Settled }
                                        )
                                        LaunchedEffect(dismissState.currentValue) {
                                            when (dismissState.currentValue) {
                                                SwipeToDismissBoxValue.StartToEnd -> {
                                                    viewModel.toggleDone(todo.id, !todo.isDone)
                                                    dismissState.reset()
                                                }
                                                SwipeToDismissBoxValue.EndToStart -> archiveWithUndo(todo.id)
                                                else -> {}
                                            }
                                        }
                                        SwipeToDismissBox(
                                            state = dismissState,
                                            backgroundContent = {
                                                val bgColor by animateColorAsState(
                                                    targetValue = when (dismissState.targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd -> Color(0xFF388E3C)
                                                        SwipeToDismissBoxValue.EndToStart -> Color(0xFFD32F2F)
                                                        else -> MaterialTheme.colorScheme.surfaceVariant
                                                    },
                                                    label = "swipeBg"
                                                )
                                                Box(
                                                    modifier = Modifier.fillMaxSize().background(bgColor).padding(horizontal = 20.dp),
                                                    contentAlignment = when (dismissState.targetValue) {
                                                        SwipeToDismissBoxValue.StartToEnd -> Alignment.CenterStart
                                                        else -> Alignment.CenterEnd
                                                    }
                                                ) {
                                                    Icon(
                                                        imageVector = when (dismissState.targetValue) {
                                                            SwipeToDismissBoxValue.StartToEnd -> Icons.Filled.CheckCircle
                                                            else -> Icons.Filled.Delete
                                                        },
                                                        contentDescription = null,
                                                        tint = Color.White
                                                    )
                                                }
                                            }
                                        ) {
                                            TodoRow(
                                                todo = todo,
                                                bucketName = buckets[todo.bucketId]?.name,
                                                colorHex = buckets[todo.bucketId]?.colorHex,
                                                subtasks = subtasksMap[todo.id] ?: emptyList(),
                                                onToggle = { viewModel.toggleDone(todo.id, !todo.isDone) },
                                                onToggleSubtask = { subtaskId, isDone -> viewModel.toggleSubtask(subtaskId, isDone) },
                                                onArchive = { archiveWithUndo(todo.id) },
                                                onEdit = onRowClick,
                                                onLongClick = onRowLongClick,
                                                isPinned = todo.isPinned,
                                                isSelected = isSelected
                                            )
                                        }
                                    } else {
                                        TodoRow(
                                            todo = todo,
                                            bucketName = buckets[todo.bucketId]?.name,
                                            colorHex = buckets[todo.bucketId]?.colorHex,
                                            subtasks = subtasksMap[todo.id] ?: emptyList(),
                                            onToggle = { viewModel.toggleDone(todo.id, !todo.isDone) },
                                            onToggleSubtask = { subtaskId, isDone -> viewModel.toggleSubtask(subtaskId, isDone) },
                                            onArchive = { archiveWithUndo(todo.id) },
                                            onEdit = onRowClick,
                                            onLongClick = onRowLongClick,
                                            isPinned = todo.isPinned,
                                            isSelected = isSelected
                                        )
                                    }
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
            } // end kanban else
        }
    }
}

/**
 * Dismissible "way out" for a pile of overdue to-dos: one tap moves them all to today,
 * keeping each one's time of day. Only ever covers what's currently visible.
 */
@Composable
private fun OverdueBanner(
    count: Int,
    filtersActive: Boolean,
    onReschedule: () -> Unit,
    onDismiss: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, end = 4.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Icon(
                imageVector = Icons.Filled.ErrorOutline,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.onErrorContainer
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "$count overdue",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
                if (filtersActive) {
                    Text(
                        text = "In the current filter only",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
            TextButton(onClick = onReschedule) { Text("Reschedule to today") }
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Dismiss overdue banner",
                    modifier = Modifier.size(18.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
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

@Composable
private fun KanbanView(
    todos: List<com.carlmanning.carlsbrain.domain.model.Todo>,
    buckets: Map<Long, com.carlmanning.carlsbrain.data.local.entity.BucketEntity>,
    onOpenTodo: (Long) -> Unit
) {
    val priorities = listOf(Priority.URGENT, Priority.HIGH, Priority.NORMAL, Priority.SOMEDAY)
    LazyRow(
        modifier = Modifier.fillMaxSize(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        items(priorities) { priority ->
            val columnTodos = todos.filter { it.priority == priority && !it.isDone }
            Column(
                // Min-width with room to grow: fixed widths clip at large font scales.
                modifier = Modifier.widthIn(min = 220.dp, max = 320.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = priority.displayName,
                    style = MaterialTheme.typography.labelLarge,
                    color = when (priority) {
                        Priority.URGENT -> MaterialTheme.colorScheme.error
                        Priority.HIGH -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(bottom = 4.dp)
                )
                if (columnTodos.isEmpty()) {
                    Text(
                        text = "No ${priority.displayName.lowercase()} todos",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                    )
                }
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.heightIn(max = 600.dp)
                ) {
                    items(columnTodos, key = { it.id }) { todo ->
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onOpenTodo(todo.id) },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant
                            )
                        ) {
                            Column(modifier = Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                Text(
                                    // Wraps rather than clips at large font scales.
                                    text = todo.title,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                                if (todo.dueDate != null) {
                                    val isOverdue = todo.dueDate < System.currentTimeMillis()
                                    Text(
                                        text = if (isOverdue)
                                            "Overdue ${formatSmartDate(todo.dueDate)}"
                                        else
                                            formatSmartDate(todo.dueDate),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isOverdue)
                                            MaterialTheme.colorScheme.error
                                        else MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                val bucketName = buckets[todo.bucketId]?.name
                                if (bucketName != null) {
                                    Text(
                                        text = bucketName,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.primary
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

private fun recurrenceLabel(r: Recurrence): String = when (r) {
    is Recurrence.Daily -> "Daily"
    is Recurrence.Weekly -> "Weekly"
    is Recurrence.Fortnightly -> "Fortnightly"
    is Recurrence.Monthly -> "Monthly"
    is Recurrence.Custom -> "Every ${r.intervalDays}d"
    else -> ""
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TodoRow(
    todo: Todo,
    bucketName: String?,
    colorHex: String?,
    subtasks: List<SubtaskEntity> = emptyList(),
    onToggle: () -> Unit,
    onToggleSubtask: (Long, Boolean) -> Unit = { _, _ -> },
    onArchive: () -> Unit,
    onEdit: () -> Unit = {},
    onLongClick: () -> Unit = {},
    isPinned: Boolean = false,
    isSelected: Boolean = false
) {
    val baseColor = if (isSelected)
        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.25f)
    else if (todo.calendarEventId != null)
        MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
    else
        MaterialTheme.colorScheme.surfaceVariant
    val containerColor by animateColorAsState(
        targetValue = if (todo.isDone) baseColor.copy(alpha = 0.5f) else baseColor,
        label = "cardColor"
    )
    val bucketColor = try {
        Color(android.graphics.Color.parseColor(colorHex ?: "#6750A4"))
    } catch (e: Exception) {
        MaterialTheme.colorScheme.primary
    }

    var subtasksExpanded by remember { mutableStateOf(false) }
    val doneSubs = subtasks.count { it.isDone }
    val totalSubs = subtasks.size

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(onClick = onEdit, onLongClick = onLongClick),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(bucketColor.copy(alpha = if (todo.isDone) 0.4f else 1f))
                    // Colour alone carries the bucket identity here — name it for TalkBack.
                    .semantics {
                        contentDescription = bucketName?.let { "Bucket: $it" } ?: "No bucket"
                    }
            )
        Column(modifier = Modifier.weight(1f)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(onClick = onToggle) {
                Icon(
                    imageVector = if (todo.isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                    contentDescription = if (todo.isDone) "Mark undone" else "Mark done",
                    tint = if (todo.isDone)
                        MaterialTheme.colorScheme.primary
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Text(
                        text = todo.title,
                        style = MaterialTheme.typography.bodyMedium,
                        textDecoration = if (todo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                        color = if (todo.isDone)
                            MaterialTheme.colorScheme.onSurfaceVariant
                        else
                            MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.weight(1f)
                    )
                    if (isPinned) {
                        Icon(
                            imageVector = Icons.Filled.PushPin,
                            contentDescription = "Pinned",
                            modifier = Modifier.size(14.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bucketName != null) {
                            Text(
                                text = bucketName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        val priorityColor = when (todo.priority) {
                            Priority.URGENT -> MaterialTheme.colorScheme.error
                            Priority.HIGH -> Color(0xFFE65100)
                            Priority.NORMAL -> MaterialTheme.colorScheme.primary
                            Priority.SOMEDAY -> MaterialTheme.colorScheme.outline
                        }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Box(
                                Modifier
                                    .size(6.dp)
                                    .background(priorityColor, CircleShape)
                            )
                            Text(
                                text = todo.priority.displayName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        if (todo.calendarEventId != null) {
                            Icon(
                                imageVector = Icons.Filled.CalendarToday,
                                contentDescription = "From calendar",
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                            )
                        }
                        if (todo.dueDate != null) {
                            val now = System.currentTimeMillis()
                            val isOverdue = todo.dueDate < now && !todo.isDone
                            val isDueSoon = !isOverdue && !todo.isDone &&
                                todo.dueDate < now + 24 * 60 * 60 * 1000L
                            // Non-colour cue as well as colour: overdue is labelled, due-soon
                            // gets a clock icon, so neither depends on hue alone.
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                if (isOverdue) {
                                    Icon(
                                        imageVector = Icons.Filled.ErrorOutline,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                } else if (isDueSoon) {
                                    Icon(
                                        imageVector = Icons.Filled.Schedule,
                                        contentDescription = null,
                                        modifier = Modifier.size(10.dp),
                                        tint = MaterialTheme.colorScheme.tertiary
                                    )
                                }
                                Text(
                                    text = if (isOverdue)
                                        "· Overdue ${formatSmartDate(todo.dueDate)}"
                                    else
                                        "· ${formatSmartDate(todo.dueDate)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when {
                                        isOverdue -> MaterialTheme.colorScheme.error
                                        isDueSoon -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    }
                                )
                            }
                        }
                        if (todo.recurrence != Recurrence.None) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp)
                            ) {
                                Text(
                                    text = "·",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                                Icon(
                                    imageVector = Icons.Filled.Repeat,
                                    contentDescription = "Repeating",
                                    modifier = Modifier.size(10.dp),
                                    tint = MaterialTheme.colorScheme.tertiary
                                )
                                Text(
                                    text = recurrenceLabel(todo.recurrence),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.tertiary
                                )
                            }
                        }
                    }
            }

            if (todo.isDone) {
                IconButton(onClick = onArchive) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "Remove",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                    )
                }
            }
        } // end inner Row

        // Subtask progress chip + expanded list
        if (totalSubs > 0) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 48.dp, end = 12.dp, bottom = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                androidx.compose.material3.SuggestionChip(
                    onClick = { subtasksExpanded = !subtasksExpanded },
                    label = { Text("$doneSubs/$totalSubs subtasks", style = MaterialTheme.typography.labelSmall) }
                )
            }
            if (subtasksExpanded) {
                Column(modifier = Modifier.padding(start = 48.dp, end = 12.dp, bottom = 8.dp)) {
                    subtasks.forEach { sub ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 48dp touch target (minimum accessible size); the icon stays small.
                            IconButton(
                                onClick = { onToggleSubtask(sub.id, !sub.isDone) },
                                modifier = Modifier.size(48.dp)
                            ) {
                                Icon(
                                    imageVector = if (sub.isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = if (sub.isDone) "Mark subtask undone" else "Mark subtask done",
                                    tint = if (sub.isDone) MaterialTheme.colorScheme.primary
                                           else MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Text(
                                text = sub.title,
                                style = MaterialTheme.typography.bodySmall,
                                textDecoration = if (sub.isDone) TextDecoration.LineThrough else TextDecoration.None,
                                color = if (sub.isDone) MaterialTheme.colorScheme.onSurfaceVariant
                                        else MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
        } // end Column(weight 1f)
        } // end outer Row (IntrinsicSize.Min)
    }
}
