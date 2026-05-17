package com.carlmanning.carlsbrain.ui.screens.todos

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Todo
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

@Composable
fun TodosScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToHistory: () -> Unit,
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: TodosViewModel = viewModel()
) {
    val todos by viewModel.todos.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val selectedPriority by viewModel.selectedPriority.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "To Do",
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow,
                extraActions = {
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
            // ── Priority filter chips ───────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FilterChip(
                    selected = selectedPriority == null,
                    onClick = { viewModel.onPriorityFilterSelected(null) },
                    label = { Text("All") }
                )
                Priority.entries.forEach { priority ->
                    FilterChip(
                        selected = selectedPriority == priority,
                        onClick = { viewModel.onPriorityFilterSelected(priority) },
                        label = { Text(priority.displayName) }
                    )
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
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 12.dp, vertical = 4.dp
                    )
                ) {
                    items(todos, key = { it.id }) { todo ->
                        TodoRow(
                            todo = todo,
                            bucketName = buckets[todo.bucketId]?.name,
                            onToggle = { viewModel.toggleDone(todo.id, !todo.isDone) },
                            onArchive = { viewModel.archiveTodo(todo.id) }
                        )
                    }
                }
            }
        }
    }
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

@Composable
private fun TodoRow(
    todo: Todo,
    bucketName: String?,
    onToggle: () -> Unit,
    onArchive: () -> Unit
) {
    val containerColor by animateColorAsState(
        targetValue = if (todo.isDone)
            MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        else
            MaterialTheme.colorScheme.surfaceVariant,
        label = "cardColor"
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = containerColor)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 10.dp),
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
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    textDecoration = if (todo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                    color = if (todo.isDone)
                        MaterialTheme.colorScheme.onSurfaceVariant
                    else
                        MaterialTheme.colorScheme.onSurface
                )
                val showMeta = bucketName != null ||
                        todo.priority != Priority.NORMAL ||
                        todo.dueDate != null
                if (showMeta) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (bucketName != null) {
                            Text(
                                text = bucketName,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }
                        if (todo.priority != Priority.NORMAL) {
                            Text(
                                text = "· ${todo.priority.displayName}",
                                style = MaterialTheme.typography.labelSmall,
                                color = when (todo.priority) {
                                    Priority.URGENT -> MaterialTheme.colorScheme.error
                                    Priority.HIGH -> MaterialTheme.colorScheme.tertiary
                                    else -> MaterialTheme.colorScheme.onSurfaceVariant
                                }
                            )
                        }
                        if (todo.dueDate != null) {
                            Text(
                                text = "· ${formatDueDate(todo.dueDate)}",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (todo.dueDate < System.currentTimeMillis() && !todo.isDone)
                                    MaterialTheme.colorScheme.error
                                else
                                    MaterialTheme.colorScheme.onSurfaceVariant
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
        }
    }
}
