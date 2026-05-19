package com.carlmanning.carlsbrain.ui.screens.calendar

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.ui.components.BrainTopBar
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CalendarScreen(
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: () -> Unit = {},
    onNavigateToChat: () -> Unit = {},
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    viewModel: CalendarViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val dialog = uiState.createDialog
    var detailEvent by remember { mutableStateOf<CalendarEvent?>(null) }

    // Date picker dialog
    if (dialog.showDatePicker) {
        val datePickerState = rememberDatePickerState(initialSelectedDateMillis = dialog.dateMs)
        DatePickerDialog(
            onDismissRequest = viewModel::onDismissDatePicker,
            confirmButton = {
                TextButton(onClick = {
                    datePickerState.selectedDateMillis?.let { viewModel.onDateSelected(it) }
                        ?: viewModel.onDismissDatePicker()
                }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissDatePicker) { Text("Cancel") }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    // Start time picker dialog
    if (dialog.showStartTimePicker) {
        val timeState = rememberTimePickerState(dialog.startHour, dialog.startMinute, is24Hour = false)
        AlertDialog(
            onDismissRequest = viewModel::onDismissStartTimePicker,
            confirmButton = {
                TextButton(onClick = { viewModel.onStartTimeSelected(timeState.hour, timeState.minute) }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissStartTimePicker) { Text("Cancel") }
            },
            title = { Text("Start time") },
            text = { TimePicker(state = timeState) }
        )
    }

    // End time picker dialog
    if (dialog.showEndTimePicker) {
        val timeState = rememberTimePickerState(dialog.endHour, dialog.endMinute, is24Hour = false)
        AlertDialog(
            onDismissRequest = viewModel::onDismissEndTimePicker,
            confirmButton = {
                TextButton(onClick = { viewModel.onEndTimeSelected(timeState.hour, timeState.minute) }) { Text("OK") }
            },
            dismissButton = {
                TextButton(onClick = viewModel::onDismissEndTimePicker) { Text("Cancel") }
            },
            title = { Text("End time") },
            text = { TimePicker(state = timeState) }
        )
    }

    // Create event dialog
    if (dialog.isVisible) {
        AlertDialog(
            onDismissRequest = viewModel::dismissCreateDialog,
            title = { Text("New Event") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    OutlinedTextField(
                        value = dialog.title,
                        onValueChange = viewModel::onCreateTitleChange,
                        label = { Text("Title") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = viewModel::onShowDatePicker,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.Filled.CalendarToday, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(formatDialogDate(dialog.dateMs))
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = viewModel::onShowStartTimePicker, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(formatHM(dialog.startHour, dialog.startMinute))
                        }
                        OutlinedButton(onClick = viewModel::onShowEndTimePicker, modifier = Modifier.weight(1f)) {
                            Icon(Icons.Filled.Schedule, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(Modifier.width(4.dp))
                            Text(formatHM(dialog.endHour, dialog.endMinute))
                        }
                    }
                    OutlinedTextField(
                        value = dialog.location,
                        onValueChange = viewModel::onCreateLocationChange,
                        label = { Text("Location (optional)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (dialog.error != null) {
                        Text(
                            text = dialog.error,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                if (dialog.isCreating) {
                    CircularProgressIndicator(modifier = Modifier.size(24.dp))
                } else {
                    Button(
                        onClick = viewModel::submitCreateEvent,
                        enabled = dialog.title.isNotBlank()
                    ) {
                        Text("Create")
                    }
                }
            },
            dismissButton = {
                TextButton(onClick = viewModel::dismissCreateDialog) { Text("Cancel") }
            }
        )
    }

    // Event detail dialog
    detailEvent?.let { event ->
        AlertDialog(
            onDismissRequest = { detailEvent = null },
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
                TextButton(onClick = { detailEvent = null }) { Text("Close") }
            }
        )
    }

    Scaffold(
        topBar = {
            BrainTopBar(
                title = "Calendar",
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
            FloatingActionButton(onClick = viewModel::showCreateDialog) {
                Icon(Icons.Filled.Add, contentDescription = "New event")
            }
        }
    ) { innerPadding ->
        PullToRefreshBox(
            isRefreshing = uiState.isLoading,
            onRefresh = { viewModel.loadEvents() },
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                uiState.isLoading && uiState.days.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }

                uiState.error != null -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = uiState.error!!,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(24.dp)
                        )
                    }
                }

                uiState.days.isEmpty() -> {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            text = "No upcoming events",
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        uiState.days.forEach { day ->
                            item(key = day.label) {
                                Text(
                                    text = day.label,
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(
                                        start = 16.dp, end = 16.dp,
                                        top = 16.dp, bottom = 4.dp
                                    )
                                )
                            }
                            items(day.events, key = { it.id }) { event ->
                                EventCard(
                                    event = event,
                                    onClick = { detailEvent = event },
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp)
                                )
                            }
                        }
                        item { Spacer(Modifier.height(80.dp)) }
                    }
                }
            }
        }
    }
}

private fun formatDialogDate(ms: Long): String {
    val zone = ZoneId.systemDefault()
    val date = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    val today = LocalDate.now()
    val tomorrow = today.plusDays(1)
    return when (date) {
        today -> "Today"
        tomorrow -> "Tomorrow"
        else -> date.format(DateTimeFormatter.ofPattern("EEE d MMM"))
    }
}

private fun formatHM(hour: Int, minute: Int): String = String.format("%02d:%02d", hour, minute)

@Composable
private fun EventCard(event: CalendarEvent, onClick: () -> Unit = {}, modifier: Modifier = Modifier) {
    val calColor = runCatching {
        event.colorHex?.let { Color(android.graphics.Color.parseColor(it)) }
    }.getOrNull()

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (calColor != null) {
                Box(
                    modifier = Modifier
                        .width(4.dp)
                        .height(40.dp)
                        .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
                        .background(calColor)
                )
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = event.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium
                )
                val subtitle = buildString {
                    append(event.formattedTime())
                    if (!event.location.isNullOrBlank()) append(" · ${event.location}")
                    if (!event.calendarName.isNullOrBlank()) append(" · ${event.calendarName}")
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
