package com.carlmanning.carlsbrain.ui.screens.dashboard

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.WeatherInfo
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.components.BrainFab
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun DashboardScreen(
    isVaultVisible: Boolean,
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToCapture: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onOpenTodo: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onOpenCalendar: () -> Unit = {},
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewed.collectAsStateWithLifecycle()
    var focusMode by remember { mutableStateOf(false) }
    var weekExpanded by remember { mutableStateOf(false) }
    var detailCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }

    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

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

    // Calendar event detail dialog
    detailCalendarEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { detailCalendarEvent = null },
            title = { Text(event.title, style = MaterialTheme.typography.titleMedium) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Filled.Schedule, contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            text = if (event.isAllDay) "All day" else event.formattedTime(),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    if (event.location != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null,
                                modifier = Modifier.size(16.dp),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text(text = event.location, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { detailCalendarEvent = null; onOpenCalendar() }) {
                    Text("Open Calendar")
                }
            },
            dismissButton = {
                TextButton(onClick = { detailCalendarEvent = null }) { Text("Close") }
            }
        )
    }

    Scaffold(
        topBar = {
            BrainTopBar(
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
        },
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
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ── Focus mode toggle ───────────────────────────────────
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(
                    selected = focusMode,
                    onClick = { focusMode = !focusMode },
                    label = { Text("Today focus", style = MaterialTheme.typography.labelSmall) }
                )
            }

            // ── Weather card ────────────────────────────────────────
            val weather = uiState.weatherInfo
            if (weather != null) {
                WeatherCard(weather = weather, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // ── What Next? ──────────────────────────────────────────
            WhatNextSection(
                whatNext = uiState.whatNext,
                isLoading = uiState.isLoadingWhatNext,
                onAsk = { viewModel.askWhatNext() },
                onDismiss = { viewModel.dismissWhatNext() },
                modifier = Modifier.padding(horizontal = 16.dp)
            )

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

            // ── Recently viewed strip ───────────────────────────────
            if (recentlyViewed.isNotEmpty()) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Recently viewed",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(horizontal = 16.dp)
                    ) {
                        items(recentlyViewed, key = { it.id }) { entry ->
                            // MEETING/EVENT rows have no Dashboard nav callback — rendered non-clickable.
                            val onEntryClick: (() -> Unit)? = when (entry.itemType) {
                                "TODO" -> { { onOpenTodo(entry.itemId) } }
                                "NOTE" -> { { onOpenNote(entry.itemId) } }
                                else -> null
                            }
                            RecentlyViewedCard(
                                entry = entry,
                                bucket = entry.bucketId?.let { id -> buckets.find { it.id == id } },
                                onClick = onEntryClick
                            )
                        }
                    }
                }
            }

            // ── Overdue section ──────────────────────────────────────
            AnimatedVisibility(visible = uiState.overdueTodos.isNotEmpty()) {
                Column(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Overdue",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    uiState.overdueTodos.forEach { todo ->
                        val barColor = bucketBarColor(buckets.find { it.id == todo.bucketId })
                        Card(
                            onClick = { onOpenTodo(todo.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                Box(
                                    modifier = Modifier
                                        .width(4.dp)
                                        .fillMaxHeight()
                                        .background(barColor)
                                )
                                Row(
                                    modifier = Modifier.weight(1f).padding(12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = Priority.fromRank(todo.priority).displayName.take(1),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.error,
                                        fontWeight = FontWeight.Bold
                                    )
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(text = todo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        val dueDate = todo.dueDate
                                        if (dueDate != null) {
                                            Text(
                                                text = "Due ${formatSmartDateTime(dueDate)}",
                                                style = MaterialTheme.typography.labelSmall,
                                                color = MaterialTheme.colorScheme.error
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
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
                        DashboardTodoRow(
                            todo = todo,
                            bucket = buckets.find { it.id == todo.bucketId },
                            onClick = { onOpenTodo(todo.id) }
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
                    emptyText = "Nothing scheduled today",
                    buckets = buckets,
                    onOpenTodo = onOpenTodo,
                    onOpenNote = onOpenNote,
                    onOpenCalendarEvent = { detailCalendarEvent = it }
                )
                if (!focusMode) {
                    // Collapsible "Tomorrow & this week" accordion
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { weekExpanded = !weekExpanded }
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Tomorrow & this week", style = MaterialTheme.typography.titleSmall)
                        Icon(
                            if (weekExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                            contentDescription = null
                        )
                    }
                    if (weekExpanded) {
                        ScheduleDaySection(
                            title = "Tomorrow",
                            items = uiState.tomorrowSchedule,
                            emptyText = "Nothing scheduled tomorrow",
                            buckets = buckets,
                            onOpenTodo = onOpenTodo,
                            onOpenNote = onOpenNote,
                            onOpenCalendarEvent = { detailCalendarEvent = it }
                        )
                        if (uiState.weekSchedule.isNotEmpty()) {
                            WeekSection(
                                items = uiState.weekSchedule,
                                buckets = buckets,
                                onOpenTodo = onOpenTodo,
                                onOpenNote = onOpenNote,
                                onOpenCalendarEvent = { detailCalendarEvent = it }
                            )
                        }
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
private fun WeatherCard(weather: WeatherInfo, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp).fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("Today — Dubbo", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "${weather.currentTemp}°  ${weather.today.description}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "↑${weather.today.maxTemp}° ↓${weather.today.minTemp}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text("Tomorrow", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    weather.tomorrow.description,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                Text(
                    "↑${weather.tomorrow.maxTemp}° ↓${weather.tomorrow.minTemp}°",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
private fun WhatNextSection(
    whatNext: String,
    isLoading: Boolean,
    onAsk: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(
            onClick = onAsk,
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            if (isLoading) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
                Text(
                    text = "  Thinking…",
                    style = MaterialTheme.typography.labelMedium
                )
            } else {
                Icon(
                    imageVector = Icons.Filled.ElectricBolt,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Text(
                    text = "  What should I do next?",
                    style = MaterialTheme.typography.labelMedium
                )
            }
        }
        if (whatNext.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.tertiaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
                    verticalAlignment = Alignment.Top,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = whatNext,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onTertiaryContainer
                    )
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = Icons.Filled.Close,
                            contentDescription = "Dismiss",
                            tint = MaterialTheme.colorScheme.onTertiaryContainer,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScheduleDaySection(
    title: String,
    items: List<ScheduleItem>,
    emptyText: String,
    buckets: List<BucketEntity> = emptyList(),
    onOpenTodo: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onOpenCalendarEvent: (CalendarEvent) -> Unit = {}
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
                    is ScheduleItem.Event -> DashboardEventRow(event = item.event, onClick = { onOpenCalendarEvent(item.event) })
                    is ScheduleItem.TodoDue -> DashboardTodoScheduleRow(
                        todo = item.todo,
                        bucket = buckets.find { it.id == item.todo.bucketId },
                        onClick = { onOpenTodo(item.todo.id) }
                    )
                    is ScheduleItem.NoteReminder -> DashboardNoteReminderRow(
                        note = item.note,
                        bucket = buckets.find { it.id == item.note.bucketId },
                        onClick = { onOpenNote(item.note.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun WeekSection(
    items: List<ScheduleItem>,
    buckets: List<BucketEntity> = emptyList(),
    onOpenTodo: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onOpenCalendarEvent: (CalendarEvent) -> Unit = {}
) {
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
                    is ScheduleItem.Event -> DashboardEventRow(event = item.event, onClick = { onOpenCalendarEvent(item.event) })
                    is ScheduleItem.TodoDue -> DashboardTodoScheduleRow(
                        todo = item.todo,
                        bucket = buckets.find { it.id == item.todo.bucketId },
                        onClick = { onOpenTodo(item.todo.id) }
                    )
                    is ScheduleItem.NoteReminder -> DashboardNoteReminderRow(
                        note = item.note,
                        bucket = buckets.find { it.id == item.note.bucketId },
                        onClick = { onOpenNote(item.note.id) }
                    )
                }
            }
        }
    }
}

@Composable
private fun DashboardEventRow(event: CalendarEvent, onClick: () -> Unit = {}) {
    Card(
        onClick = onClick,
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
private fun DashboardTodoScheduleRow(
    todo: TodoEntity,
    bucket: BucketEntity? = null,
    onClick: () -> Unit = {}
) {
    val timeMs = todo.reminderAt ?: todo.dueDate ?: return
    val barColor = bucketBarColor(bucket)
    val priorityColor = when (todo.priority) {
        Priority.URGENT.rank -> MaterialTheme.colorScheme.error
        Priority.HIGH.rank -> MaterialTheme.colorScheme.tertiary
        else -> null
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor)
            )
            Row(
                modifier = Modifier.weight(1f).padding(12.dp),
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
                    text = formatSmartDateTime(timeMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = todo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (priorityColor != null) {
                        Text(
                            text = Priority.fromRank(todo.priority).displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = priorityColor
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun DashboardNoteReminderRow(
    note: NoteEntity,
    bucket: BucketEntity? = null,
    onClick: () -> Unit = {}
) {
    val timeMs = note.reminderAt ?: return
    val barColor = bucketBarColor(bucket)
    val displayTitle = note.title.ifBlank { note.content.lines().first().take(60) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor)
            )
            Row(
                modifier = Modifier.weight(1f).padding(12.dp),
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
                    text = formatSmartDateTime(timeMs),
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
}

@Composable
private fun DashboardTodoRow(
    todo: TodoEntity,
    bucket: BucketEntity? = null,
    onClick: () -> Unit = {}
) {
    val barColor = bucketBarColor(bucket)
    val priorityColor = when (todo.priority) {
        Priority.URGENT.rank -> MaterialTheme.colorScheme.error
        Priority.HIGH.rank -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.onSurface
    }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor)
            )
            Row(
                modifier = Modifier.weight(1f).padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = Priority.fromRank(todo.priority).displayName.take(1),
                    style = MaterialTheme.typography.labelMedium,
                    color = priorityColor,
                    fontWeight = FontWeight.Bold
                )
                Text(text = todo.title, style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

/**
 * Item #16 — a single card in the "Recently viewed" strip.
 * [onClick] is null for item types the Dashboard has no navigation callback for, in which
 * case the card renders as non-interactive rather than a dead tap target.
 */
@Composable
private fun RecentlyViewedCard(
    entry: RecentlyViewedEntity,
    bucket: BucketEntity?,
    onClick: (() -> Unit)?
) {
    val barColor = bucketBarColor(bucket)
    val typeLabel = when (entry.itemType) {
        "TODO" -> "To-do"
        "NOTE" -> "Note"
        "MEETING" -> "Meeting"
        "EVENT" -> "Event"
        else -> entry.itemType
    }
    val content: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .fillMaxHeight()
                    .background(barColor)
            )
            Column(modifier = Modifier.weight(1f).padding(8.dp)) {
                Text(
                    text = entry.title,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = typeLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
    if (onClick != null) {
        Card(onClick = onClick, modifier = Modifier.widthIn(max = 160.dp)) { content() }
    } else {
        Card(modifier = Modifier.widthIn(max = 160.dp)) { content() }
    }
}

/** Resolves a bucket's colour bar tint, falling back to a neutral outline when unknown. */
@Composable
private fun bucketBarColor(bucket: BucketEntity?): Color {
    val fallback = MaterialTheme.colorScheme.outline
    if (bucket == null) return fallback
    return try {
        Color(android.graphics.Color.parseColor(bucket.colorHex))
    } catch (e: Exception) {
        fallback
    }
}
