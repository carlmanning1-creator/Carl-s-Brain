package com.carlmanning.carlsbrain.ui.screens.todos

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DragHandle
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Repeat
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
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.SubtaskEntity
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.domain.model.Todo
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

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

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "To Do",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
                    IconButton(onClick = onNavigateToChat) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = "Chat")
                    }
                    IconButton(onClick = { viewModel.prioritiseWithClaude() }, enabled = !isPrioritising) {
                        if (isPrioritising) {
                            CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        } else {
                            Icon(Icons.Filled.Psychology, contentDescription = "Prioritise with Claude")
                        }
                    }
                    IconButton(onClick = onNavigateToHistory) {
                        Icon(Icons.Filled.History, contentDescription = "History")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCapture) {
                Icon(Icons.Filled.Add, contentDescription = "Add to do")
            }
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

            // ── Todo list ───────────────────────────────────────────
            if (todos.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No to-dos yet — tap + to add one",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    state = lazyListState,
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp
                    )
                ) {
                    items(todos, key = { it.id }) { todo ->
                        ReorderableItem(reorderState, key = todo.id) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (sortMode == TodoSortMode.MANUAL) {
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
                                    if (swipeToCompleteEnabled) {
                                        val dismissState = rememberSwipeToDismissBoxState(
                                            confirmValueChange = { it != SwipeToDismissBoxValue.Settled }
                                        )
                                        LaunchedEffect(dismissState.currentValue) {
                                            when (dismissState.currentValue) {
                                                SwipeToDismissBoxValue.StartToEnd -> {
                                                    viewModel.toggleDone(todo.id, !todo.isDone)
                                                    dismissState.reset()
                                                }
                                                SwipeToDismissBoxValue.EndToStart -> viewModel.archiveTodo(todo.id)
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
                                                onArchive = { viewModel.archiveTodo(todo.id) },
                                                onEdit = { onOpenTodo(todo.id) },
                                                onLongClick = { viewModel.pinTodo(todo.id, !todo.isPinned) },
                                                isPinned = todo.isPinned
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
                                            onArchive = { viewModel.archiveTodo(todo.id) },
                                            onEdit = { onOpenTodo(todo.id) },
                                            onLongClick = { viewModel.pinTodo(todo.id, !todo.isPinned) },
                                            isPinned = todo.isPinned
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
}

private fun recurrenceLabel(r: Recurrence): String = when (r) {
    is Recurrence.Daily -> "Daily"
    is Recurrence.Weekly -> "Weekly"
    is Recurrence.Fortnightly -> "Fortnightly"
    is Recurrence.Monthly -> "Monthly"
    is Recurrence.Custom -> "Every ${r.intervalDays}d"
    else -> ""
}

private fun formatDueDate(dateMs: Long): String {
    val today = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    val tomorrow = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
    val due = Calendar.getInstance().apply {
        timeInMillis = dateMs
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }
    return when (due.timeInMillis) {
        today.timeInMillis -> "Today"
        tomorrow.timeInMillis -> "Tomorrow"
        else -> SimpleDateFormat("d MMM", Locale.getDefault()).format(Date(dateMs))
    }
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
    isPinned: Boolean = false
) {
    val baseColor = if (todo.calendarEventId != null)
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
                        Text(
                            text = "· ${todo.priority.displayName}",
                            style = MaterialTheme.typography.labelSmall,
                            color = when (todo.priority) {
                                Priority.URGENT -> MaterialTheme.colorScheme.error
                                Priority.HIGH -> MaterialTheme.colorScheme.tertiary
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                        if (todo.calendarEventId != null) {
                            Icon(
                                imageVector = Icons.Filled.CalendarToday,
                                contentDescription = "From calendar",
                                modifier = Modifier.size(10.dp),
                                tint = MaterialTheme.colorScheme.tertiary.copy(alpha = 0.7f)
                            )
                        }
                        if (todo.dueDate != null) {
                            Text(
                                text = "· ${formatDueDate(todo.dueDate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = when {
                                    todo.dueDate < System.currentTimeMillis() && !todo.isDone ->
                                        MaterialTheme.colorScheme.error
                                    todo.dueDate < System.currentTimeMillis() + 24 * 60 * 60 * 1000L && !todo.isDone ->
                                        MaterialTheme.colorScheme.tertiary
                                    else ->
                                        MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
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
                            IconButton(
                                onClick = { onToggleSubtask(sub.id, !sub.isDone) },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Icon(
                                    imageVector = if (sub.isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
                                    contentDescription = null,
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
