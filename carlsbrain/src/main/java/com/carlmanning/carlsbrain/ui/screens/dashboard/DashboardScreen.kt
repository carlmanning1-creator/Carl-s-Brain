package com.carlmanning.carlsbrain.ui.screens.dashboard

import android.content.Intent
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
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.MonitorHeart
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.outlined.Circle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ElectricBolt
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.data.remote.WeatherInfo
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.components.BrainFab
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import com.carlmanning.carlsbrain.ui.screens.todos.formatEstimate
import com.carlmanning.carlsbrain.util.formatSmartDateTime
import com.carlmanning.carlsbrain.util.formatSmartDueDateTime
import kotlinx.coroutines.launch
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
    /**
     * Chat now has its own bottom-nav tab, so the Dashboard renders no Chat control.
     * The parameter is kept so the nav host call site is unaffected.
     */
    onNavigateToChat: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    onOpenTodo: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    /**
     * Item #4 — opens a meeting from the "Recently viewed" strip.
     * Defaulted to null so existing call sites keep compiling; while unwired in the nav host the
     * MEETING cards render non-interactive rather than as dead tap targets.
     */
    onOpenMeeting: ((Long) -> Unit)? = null,
    onOpenCalendar: () -> Unit = {},
    /** Health is off the bottom nav; the top-bar overflow menu is its entry point. */
    onNavigateToHealth: () -> Unit = {},
    /**
     * On-demand weekly review. Until the nav host wires this up it stays null and the menu item
     * falls back to firing the exact same intent [WeeklyReviewWorker] puts on its notification —
     * MainActivity.handleIntent turns that into appViewModel.requestChatPrompt(), so the fallback
     * is the identical code path, never a second copy of the prompt.
     */
    onWeeklyReview: (() -> Unit)? = null,
    viewModel: DashboardViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val buckets by viewModel.buckets.collectAsStateWithLifecycle()
    val recentlyViewed by viewModel.recentlyViewed.collectAsStateWithLifecycle()
    val briefingRules by viewModel.briefingRules.collectAsStateWithLifecycle()
    var focusMode by remember { mutableStateOf(false) }
    var weekExpanded by remember { mutableStateOf(false) }
    var detailCalendarEvent by remember { mutableStateOf<CalendarEvent?>(null) }
    var showBriefingSheet by remember { mutableStateOf(false) }
    // True only between "Carl asked for a rewrite" and "the new briefing landed", so the sheet
    // knows to show its spinner and to close itself when the answer arrives.
    var briefingRegenPending by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }
    val snackbarScope = rememberCoroutineScope()

    /**
     * Item #1 — tick a to-do off in place, with an Undo snackbar (same pattern as TodosScreen).
     * Un-ticking needs no snackbar; it is already the undo.
     */
    fun toggleWithUndo(todoId: Long, isDone: Boolean) {
        viewModel.toggleDone(todoId, isDone)
        if (!isDone) return
        snackbarScope.launch {
            val result = snackbarHostState.showSnackbar(
                message = "Marked done",
                actionLabel = "Undo",
                duration = SnackbarDuration.Short
            )
            if (result == SnackbarResult.ActionPerformed) {
                viewModel.toggleDone(todoId, false)
            }
        }
    }

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

    // Fallback path for the "Weekly review" menu item — see [onWeeklyReview].
    val context = LocalContext.current
    val openWeeklyReview = {
        context.startActivity(
            Intent(context, MainActivity::class.java).apply {
                action = WeeklyReviewWorker.ACTION_OPEN_WEEKLY_REVIEW
                putExtra(
                    WeeklyReviewWorker.EXTRA_REVIEW_PROMPT,
                    WeeklyReviewWorker.WEEKLY_REVIEW_PROMPT
                )
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
            }
        )
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) viewModel.refreshIfStale()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // The regeneration finished (successfully or not) — dismiss the steering sheet.
    // Keyed on isLoadingBriefing so it only fires on an actual loading→idle transition.
    LaunchedEffect(uiState.isLoadingBriefing) {
        if (briefingRegenPending && !uiState.isLoadingBriefing) {
            briefingRegenPending = false
            showBriefingSheet = false
        }
    }

    if (showBriefingSheet) {
        BriefingSteerSheet(
            rules = briefingRules,
            isRegenerating = briefingRegenPending,
            onRegenerate = { instruction, persist ->
                // "Always" saves the rule for next time; the instruction is also passed through as
                // a one-off so this regeneration reflects it without waiting on the DataStore write.
                if (persist) viewModel.addBriefingRule(instruction)
                briefingRegenPending = true
                viewModel.regenerateBriefing(instruction)
            },
            onRemoveRule = { rule -> viewModel.removeBriefingRule(rule) },
            onDismiss = {
                showBriefingSheet = false
                briefingRegenPending = false
            }
        )
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
                // Calendar and Health live here rather than in the bottom nav: the bottom nav is
                // capped at five items, and Dashboard already shows today's events inline.
                overflowMenuContent = { dismiss ->
                    DropdownMenuItem(
                        text = { Text("Calendar") },
                        leadingIcon = { Icon(Icons.Filled.CalendarMonth, null) },
                        onClick = { dismiss(); onOpenCalendar() }
                    )
                    DropdownMenuItem(
                        text = { Text("Health") },
                        leadingIcon = { Icon(Icons.Filled.MonitorHeart, null) },
                        onClick = { dismiss(); onNavigateToHealth() }
                    )
                    // The Friday notification is the only other way in — dismiss it and the
                    // feature vanishes for a week. This is its in-app front door.
                    DropdownMenuItem(
                        text = { Text("Weekly review") },
                        leadingIcon = { Icon(Icons.Filled.Insights, null) },
                        onClick = {
                            dismiss()
                            if (onWeeklyReview != null) onWeeklyReview() else openWeeklyReview()
                        }
                    )
                }
            )
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
                .verticalScroll(rememberScrollState())
                // Item #2 — clear the FAB so the last row stays reachable (matches TodosScreen).
                .padding(bottom = 88.dp),
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
            // Item #3 — "Today focus" strips everything that isn't today's work.
            val weather = uiState.weatherInfo
            if (weather != null && !focusMode) {
                WeatherCard(weather = weather, modifier = Modifier.padding(horizontal = 16.dp))
            }

            // ── What Next? ──────────────────────────────────────────
            WhatNextSection(
                whatNext = uiState.whatNext,
                isLoading = uiState.isLoadingWhatNext,
                errorMessage = uiState.whatNextError,
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
                // Completion signal — the app otherwise only ever shows what's outstanding.
                // Silent at zero on purpose: no scoreboard, no "0 done this week".
                if (uiState.completedThisWeek > 0) {
                    Text(
                        text = "${uiState.completedThisWeek} done this week",
                        modifier = Modifier.padding(top = 2.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
                } else if (uiState.briefingError != null) {
                    // Item #5 — the briefing used to fail silently.
                    Box(modifier = Modifier.padding(top = 8.dp)) {
                        ClaudeErrorCard(
                            message = uiState.briefingError!!,
                            onRetry = { viewModel.retryBriefing() }
                        )
                    }
                } else if (uiState.briefing.isNotBlank()) {
                    // Tapping opens the steering sheet — Carl can tell Claude what to leave out.
                    Card(
                        onClick = { showBriefingSheet = true },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = uiState.briefing,
                                style = MaterialTheme.typography.bodyMedium,
                                fontStyle = FontStyle.Italic
                            )
                            Text(
                                text = "Tap to change what this focuses on",
                                modifier = Modifier.padding(top = 6.dp),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                        }
                    }
                }
            }

            // ── What fits right now ─────────────────────────────────
            // Shown in "Today focus" too: it is today's work, and it is the section that most
            // directly answers "I've got a gap — what can I actually do?".
            uiState.gapFit?.let { gapFit ->
                GapFitSection(
                    gapFit = gapFit,
                    buckets = buckets,
                    onOpenTodo = onOpenTodo,
                    onToggleTodoDone = { todo -> toggleWithUndo(todo.id, !todo.isDone) },
                    modifier = Modifier.padding(horizontal = 16.dp)
                )
            }

            // ── Recently viewed strip ───────────────────────────────
            if (recentlyViewed.isNotEmpty() && !focusMode) {
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
                            // Item #4 — MEETING opens via onOpenMeeting once the nav host wires it.
                            // EVENT has no destination, so those cards render non-interactive.
                            val onEntryClick: (() -> Unit)? = when (entry.itemType) {
                                "TODO" -> { { onOpenTodo(entry.itemId) } }
                                "NOTE" -> { { onOpenNote(entry.itemId) } }
                                "MEETING" -> onOpenMeeting?.let { open -> { open(entry.itemId) } }
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
                        val bucket = buckets.find { it.id == todo.bucketId }
                        Card(
                            onClick = { onOpenTodo(todo.id) },
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                            )
                        ) {
                            Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                BucketBar(bucket)
                                Row(
                                    modifier = Modifier.weight(1f).padding(end = 12.dp),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    // Item #1 — complete without opening the editor.
                                    TodoCompleteCheckbox(
                                        isDone = todo.isDone,
                                        title = todo.title,
                                        onToggle = { toggleWithUndo(todo.id, !todo.isDone) }
                                    )
                                    PriorityGlyph(
                                        priority = Priority.fromRank(todo.priority),
                                        color = MaterialTheme.colorScheme.error
                                    )
                                    Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                                        Text(text = todo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                                        val dueDate = todo.dueDate
                                        // Item #6 — "Overdue" in words + icon, not red alone.
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Icon(
                                                imageVector = Icons.Filled.PriorityHigh,
                                                contentDescription = null,
                                                tint = MaterialTheme.colorScheme.error,
                                                modifier = Modifier.size(12.dp)
                                            )
                                            Text(
                                                text = if (dueDate != null)
                                                    "Overdue — was due ${formatSmartDueDateTime(dueDate)}"
                                                else "Overdue",
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
                            onClick = { onOpenTodo(todo.id) },
                            onToggleDone = { toggleWithUndo(todo.id, !todo.isDone) }
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
                    onToggleTodoDone = { todo -> toggleWithUndo(todo.id, !todo.isDone) },
                    onOpenCalendarEvent = { detailCalendarEvent = it }
                )
                // Contextual way into the Calendar screen. Event rows already own their tap —
                // it opens the detail dialog, which itself offers "Open Calendar" — so the
                // section-level button is the affordance rather than a second row action.
                TextButton(
                    onClick = onOpenCalendar,
                    modifier = Modifier.padding(horizontal = 8.dp)
                ) {
                    Text(text = "View full calendar", style = MaterialTheme.typography.labelLarge)
                    Icon(
                        imageVector = Icons.Filled.ChevronRight,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                }

                // Reference information, so it sits below today's actions and stays out of
                // "Today focus". Renders nothing at all when no health data is cached.
                if (uiState.healthSummary.isNotBlank() && !focusMode) {
                    HealthSummaryCard(
                        summary = uiState.healthSummary,
                        onClick = onNavigateToHealth,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }

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
                            onToggleTodoDone = { todo -> toggleWithUndo(todo.id, !todo.isDone) },
                            onOpenCalendarEvent = { detailCalendarEvent = it }
                        )
                        if (uiState.weekSchedule.isNotEmpty()) {
                            WeekSection(
                                items = uiState.weekSchedule,
                                buckets = buckets,
                                onOpenTodo = onOpenTodo,
                                onOpenNote = onOpenNote,
                                onToggleTodoDone = { todo -> toggleWithUndo(todo.id, !todo.isDone) },
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

/**
 * Compact, glanceable health line — the Dashboard's only entry point to Health outside the
 * top-bar overflow. It renders whatever is already cached for the briefing; the caller keeps it
 * off screen entirely when that cache is empty, so there is never an empty card or a "connect
 * Health" nag.
 */
@Composable
private fun HealthSummaryCard(
    summary: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.MonitorHeart,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Health",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = summary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
            Icon(
                imageVector = Icons.Filled.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * "Steer the briefing" sheet. [onRegenerate] is only ever called after Carl picks a scope —
 * "Just this once" (persist = false) or "Always" (persist = true). Nothing is saved silently.
 * The saved rules are listed here with a delete on each, so a standing rule can never end up
 * shaping the briefing invisibly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BriefingSteerSheet(
    rules: List<String>,
    isRegenerating: Boolean,
    onRegenerate: (instruction: String, persist: Boolean) -> Unit,
    onRemoveRule: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    var instruction by remember { mutableStateOf("") }
    // Carl asked to be asked every time, so Regenerate reveals the scope choice rather than
    // picking one for him.
    var awaitingScope by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 24.dp)
                .padding(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Steer the briefing",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            OutlinedTextField(
                value = instruction,
                onValueChange = { instruction = it; awaitingScope = false },
                label = { Text("What should be different?") },
                placeholder = { Text("e.g. the anniversary in my calendar isn't relevant") },
                enabled = !isRegenerating,
                modifier = Modifier.fillMaxWidth()
            )

            when {
                isRegenerating -> Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                    Text(
                        text = "Rewriting your briefing…",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                !awaitingScope -> Button(
                    onClick = { awaitingScope = true },
                    enabled = instruction.isNotBlank(),
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Regenerate") }
                else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Apply this…",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = { onRegenerate(instruction, false) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Just this once") }
                        Button(
                            onClick = { onRegenerate(instruction, true) },
                            modifier = Modifier.weight(1f)
                        ) { Text("Always") }
                    }
                }
            }

            if (rules.isNotEmpty()) {
                HorizontalDivider()
                Text(
                    text = "Always applied",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                rules.forEach { rule ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = rule,
                            modifier = Modifier.weight(1f),
                            style = MaterialTheme.typography.bodySmall
                        )
                        IconButton(
                            onClick = { onRemoveRule(rule) },
                            modifier = Modifier.size(32.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Close,
                                contentDescription = "Remove rule \"$rule\"",
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
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
    errorMessage: String? = null,
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
        // Item #5 — surface the failure where the answer would have been, with a retry.
        if (errorMessage != null && !isLoading) {
            ClaudeErrorCard(message = errorMessage, onRetry = onAsk)
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

/**
 * "What fits right now" — names the gap, then lists only to-dos that genuinely fit inside it.
 * The ViewModel emits null when there's no usable gap or nothing fits, so this composable is
 * never called with an empty list and never renders an empty state.
 */
@Composable
private fun GapFitSection(
    gapFit: GapFit,
    buckets: List<BucketEntity>,
    onOpenTodo: (Long) -> Unit,
    onToggleTodoDone: (TodoEntity) -> Unit,
    modifier: Modifier = Modifier
) {
    val gapText = formatGapLength(gapFit.gapMinutes)
    val heading = if (gapFit.nextEventTitle != null) {
        "You've got $gapText before ${gapFit.nextEventTitle}"
    } else {
        "You've got a clear run — at least $gapText"
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Filled.Schedule,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp)
            )
            Text(
                text = heading,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
        gapFit.todos.forEach { todo ->
            GapFitTodoRow(
                todo = todo,
                bucket = buckets.find { it.id == todo.bucketId },
                onClick = { onOpenTodo(todo.id) },
                onToggleDone = { onToggleTodoDone(todo) }
            )
        }
    }
}

@Composable
private fun GapFitTodoRow(
    todo: TodoEntity,
    bucket: BucketEntity? = null,
    onClick: () -> Unit = {},
    onToggleDone: () -> Unit = {}
) {
    // The ViewModel only ever selects to-dos with an estimate, so this is always present.
    val estimate = todo.estimateMinutes ?: return

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.4f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            BucketBar(bucket)
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Same inline completion pattern as every other Dashboard to-do row.
                TodoCompleteCheckbox(
                    isDone = todo.isDone,
                    title = todo.title,
                    onToggle = onToggleDone
                )
                PriorityGlyph(
                    priority = Priority.fromRank(todo.priority),
                    color = when (todo.priority) {
                        Priority.URGENT.rank -> MaterialTheme.colorScheme.error
                        Priority.HIGH.rank -> MaterialTheme.colorScheme.tertiary
                        else -> MaterialTheme.colorScheme.onSurface
                    }
                )
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                )
                Text(
                    text = formatEstimate(estimate),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

/** "40 minutes" / "2 hours" / "1 hour 30 minutes" — spelled out for the section heading. */
private fun formatGapLength(minutes: Int): String {
    if (minutes < 60) return "$minutes minutes"
    val hours = minutes / 60
    val rest = minutes % 60
    val hoursLabel = if (hours == 1) "1 hour" else "$hours hours"
    return if (rest == 0) hoursLabel else "$hoursLabel $rest minutes"
}

@Composable
private fun ScheduleDaySection(
    title: String,
    items: List<ScheduleItem>,
    emptyText: String,
    buckets: List<BucketEntity> = emptyList(),
    onOpenTodo: (Long) -> Unit = {},
    onOpenNote: (Long) -> Unit = {},
    onToggleTodoDone: (TodoEntity) -> Unit = {},
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
                        onClick = { onOpenTodo(item.todo.id) },
                        onToggleDone = { onToggleTodoDone(item.todo) }
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
    onToggleTodoDone: (TodoEntity) -> Unit = {},
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
                        onClick = { onOpenTodo(item.todo.id) },
                        onToggleDone = { onToggleTodoDone(item.todo) }
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
    onClick: () -> Unit = {},
    onToggleDone: () -> Unit = {}
) {
    val timeMs = todo.reminderAt ?: todo.dueDate ?: return
    val isOverdue = timeMs < System.currentTimeMillis()
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
            BucketBar(bucket)
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Item #1 — complete without opening the editor.
                TodoCompleteCheckbox(
                    isDone = todo.isDone,
                    title = todo.title,
                    onToggle = onToggleDone
                )
                Text(
                    text = formatSmartDateTime(timeMs),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Medium
                )
                Column(modifier = Modifier.weight(1f).padding(vertical = 12.dp)) {
                    Text(text = todo.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    if (priorityColor != null) {
                        Text(
                            text = Priority.fromRank(todo.priority).displayName,
                            style = MaterialTheme.typography.labelSmall,
                            color = priorityColor
                        )
                    }
                    // Item #6 — overdue is stated in words + icon, never red alone.
                    if (isOverdue) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PriorityHigh,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(12.dp)
                            )
                            Text(
                                text = "Overdue",
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

@Composable
private fun DashboardNoteReminderRow(
    note: NoteEntity,
    bucket: BucketEntity? = null,
    onClick: () -> Unit = {}
) {
    val timeMs = note.reminderAt ?: return
    val displayTitle = note.title.ifBlank { note.content.lines().first().take(60) }

    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.45f)
        )
    ) {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            BucketBar(bucket)
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
    onClick: () -> Unit = {},
    onToggleDone: () -> Unit = {}
) {
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
            BucketBar(bucket)
            Row(
                modifier = Modifier.weight(1f).padding(end = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Item #1 — complete without opening the editor.
                TodoCompleteCheckbox(
                    isDone = todo.isDone,
                    title = todo.title,
                    onToggle = onToggleDone
                )
                PriorityGlyph(
                    priority = Priority.fromRank(todo.priority),
                    color = priorityColor
                )
                Text(
                    text = todo.title,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.weight(1f).padding(vertical = 12.dp)
                )
            }
        }
    }
}

/**
 * Item #1 — leading completion toggle for every Dashboard to-do row.
 * The icon is 24dp but the [IconButton] keeps the full 48dp touch target.
 * Styling matches the TodosScreen row checkbox.
 */
@Composable
private fun TodoCompleteCheckbox(
    isDone: Boolean,
    title: String,
    onToggle: () -> Unit
) {
    IconButton(onClick = onToggle, modifier = Modifier.size(48.dp)) {
        Icon(
            imageVector = if (isDone) Icons.Filled.CheckCircle else Icons.Outlined.Circle,
            contentDescription = if (isDone) "Mark \"$title\" not done" else "Mark \"$title\" done",
            tint = if (isDone) MaterialTheme.colorScheme.primary
                   else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(24.dp)
        )
    }
}

/**
 * Item #6 — the single-letter priority glyph, with the full priority name exposed to TalkBack
 * so it isn't a bare "U"/"H"/"N".
 */
@Composable
private fun PriorityGlyph(priority: Priority, color: Color) {
    Text(
        text = priority.displayName.take(1),
        style = MaterialTheme.typography.labelMedium,
        color = color,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.semantics { contentDescription = "${priority.displayName} priority" }
    )
}

/**
 * Item #6 — the bucket colour bar. Colour alone carries no meaning for TalkBack or
 * colour-blind users, so the bucket name rides along as a content description.
 */
@Composable
private fun BucketBar(bucket: BucketEntity?) {
    val label = bucket?.name?.let { "$it bucket" } ?: "No bucket"
    Box(
        modifier = Modifier
            .width(4.dp)
            .fillMaxHeight()
            .background(bucketBarColor(bucket))
            .semantics { contentDescription = label }
    )
}

/** Item #5 — inline "Claude couldn't be reached" card with a retry button. */
@Composable
private fun ClaudeErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = message,
                modifier = Modifier.weight(1f),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
            TextButton(onClick = onRetry) {
                Icon(
                    imageVector = Icons.Filled.Refresh,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onErrorContainer
                )
                Text(
                    text = "  Retry",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
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
    val typeLabel = when (entry.itemType) {
        "TODO" -> "To-do"
        "NOTE" -> "Note"
        "MEETING" -> "Meeting"
        "EVENT" -> "Event"
        else -> entry.itemType
    }
    val content: @Composable () -> Unit = {
        Row(modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
            BucketBar(bucket)
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
        // No destination: no ripple, and reduced emphasis so it doesn't read as tappable.
        Card(
            modifier = Modifier.widthIn(max = 160.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                contentColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        ) { content() }
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
