package com.carlmanning.carlsbrain.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.Locale

@Composable
fun DashboardScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    val greetingText = remember {
        val hour = java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)
        when (hour) {
            in 5..11 -> "Good morning"
            in 12..17 -> "Good afternoon"
            in 18..23 -> "Good evening"
            else -> "You're up late"
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                onVaultToggle = onVaultToggle,
                onNavigateToSettings = onNavigateToSettings,
                onNavigateToSearch = onNavigateToSearch,
                isSyncing = isSyncing,
                onSyncNow = onSyncNow
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToCapture) {
                Icon(Icons.Filled.Add, contentDescription = "Quick capture")
            }
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Claude daily briefing ───────────────────────────────
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    text = greetingText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                if (uiState.isLoadingBriefing) {
                    Row(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                        Text(
                            text = "Preparing your briefing…",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else if (uiState.briefing.isNotBlank()) {
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Text(
                            text = uiState.briefing,
                            modifier = Modifier.padding(12.dp),
                            style = MaterialTheme.typography.bodyMedium,
                            fontStyle = FontStyle.Italic
                        )
                    }
                }
            }

            // ── Schedule sections ────────────────────────────────────
            if (uiState.isLoadingCalendar) {
                Box(
                    modifier = Modifier.fillMaxWidth().padding(8.dp),
                    contentAlignment = Alignment.Center
                ) { CircularProgressIndicator() }
            } else {
                if (uiState.calendarError != null) {
                    Text(
                        text = "Calendar unavailable — connect Google in Settings",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
                ScheduleDaySection(
                    title = "Today",
                    items = uiState.todaySchedule,
                    emptyText = "Nothing scheduled today"
                )
                ScheduleDaySection(
                    title = "Tomorrow",
                    items = uiState.tomorrowSchedule,
                    emptyText = "Nothing scheduled tomorrow"
                )
                if (uiState.weekSchedule.isNotEmpty()) {
                    WeekSection(items = uiState.weekSchedule)
                }
            }

            // ── Priority to-dos ────────────────────────────────────
            AnimatedVisibility(visible = uiState.priorityTodos.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Needs attention",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    uiState.priorityTodos.forEach { todo ->
                        DashboardTodoRow(todo = todo)
                    }
                }
            }

            if (isVaultVisible) {
                Text(
                    text = "Vault visible",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.secondary,
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }
        }
    }
}

@Composable
private fun ScheduleDaySection(
    title: String,
    items: List<ScheduleItem>,
    emptyText: String
) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        if (items.isEmpty()) {
            Text(
                text = emptyText,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            items.forEach { item ->
                when (item) {
                    is ScheduleItem.Event -> DashboardEventRow(event = item.event)
                    is ScheduleItem.TodoDue -> DashboardTodoScheduleRow(todo = item.todo)
                    is ScheduleItem.NoteReminder -> DashboardNoteReminderRow(note = item.note)
                }
            }
        }
    }
}

@Composable
private fun WeekSection(items: List<ScheduleItem>) {
    val zone = ZoneId.systemDefault()
    val dayFmt = DateTimeFormatter.ofPattern("EEEE d MMM")
    val grouped = items
        .groupBy { Instant.ofEpochMilli(it.timeMs).atZone(zone).toLocalDate() }
        .entries
        .sortedBy { it.key }

    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "This week",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        grouped.forEach { (date, dayItems) ->
            Text(
                text = date.format(dayFmt),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 4.dp)
            )
            dayItems.forEach { item ->
                when (item) {
                    is ScheduleItem.Event -> DashboardEventRow(event = item.event)
                    is ScheduleItem.TodoDue -> DashboardTodoScheduleRow(todo = item.todo)
                    is ScheduleItem.NoteReminder -> DashboardNoteReminderRow(note = item.note)
                }
            }
        }
    }
}

@Composable
private fun DashboardEventRow(event: CalendarEvent) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = event.formattedTime(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Column {
                Text(text = event.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (event.location != null) {
                    Text(
                        text = event.location,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardTodoScheduleRow(todo: TodoEntity) {
    val timeMs = todo.reminderAt ?: todo.dueDate ?: return
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val priorityColor = when (todo.priority) {
        Priority.URGENT.name -> MaterialTheme.colorScheme.error
        Priority.HIGH.name -> MaterialTheme.colorScheme.tertiary
        else -> null
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.CheckCircle,
                contentDescription = "To do",
                tint = MaterialTheme.colorScheme.secondary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = timeFmt.format(Date(timeMs)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = todo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                if (priorityColor != null) {
                    Text(
                        text = todo.priority.lowercase().replaceFirstChar { it.uppercase() },
                        style = MaterialTheme.typography.labelSmall,
                        color = priorityColor
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardNoteReminderRow(note: NoteEntity) {
    val timeMs = note.reminderAt ?: return
    val timeFmt = remember { SimpleDateFormat("HH:mm", Locale.getDefault()) }
    val displayTitle = note.title.ifBlank { note.content.lines().first().take(60) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Description,
                contentDescription = "Note reminder",
                tint = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = timeFmt.format(Date(timeMs)),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                fontWeight = FontWeight.Medium
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(text = displayTitle, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                Text(
                    text = "Note reminder",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun DashboardTodoRow(todo: TodoEntity) {
    val priorityColor = when (todo.priority) {
        Priority.URGENT.name -> MaterialTheme.colorScheme.error
        Priority.HIGH.name -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = todo.priority.take(1),
                style = MaterialTheme.typography.labelMedium,
                color = priorityColor,
                fontWeight = FontWeight.Bold
            )
            Text(text = todo.title, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
