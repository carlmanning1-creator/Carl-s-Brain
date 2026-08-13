package com.carlmanning.carlsbrain.ui.screens.search

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.CalendarToday
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SearchOff
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SearchBar
import androidx.compose.material3.SearchBarDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.domain.model.Note
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Todo
import com.carlmanning.carlsbrain.ui.components.EmptyState
import com.carlmanning.carlsbrain.util.formatSmartDateTime

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onNavigateBack: () -> Unit,
    onOpenNote: (Long) -> Unit,
    onOpenTodo: (Long) -> Unit,
    onOpenCalendar: () -> Unit = {},
    onOpenChat: () -> Unit = {},
    onOpenMeeting: (Long) -> Unit = {},
    isVaultVisible: Boolean = false,
    viewModel: SearchViewModel = viewModel()
) {
    LaunchedEffect(isVaultVisible) { viewModel.setVaultVisible(isVaultVisible) }

    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val selectedType by viewModel.selectedType.collectAsStateWithLifecycle()
    var expanded by remember { mutableStateOf(true) }

    Scaffold { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            SearchBar(
                inputField = {
                    SearchBarDefaults.InputField(
                        query = uiState.query,
                        onQueryChange = viewModel::onQueryChange,
                        onSearch = { expanded = true },
                        expanded = expanded,
                        onExpandedChange = { expanded = it },
                        placeholder = { Text("Search notes, to-dos, meetings, calendar…") },
                        leadingIcon = {
                            IconButton(onClick = onNavigateBack) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                            }
                        },
                        trailingIcon = {
                            if (uiState.query.isNotEmpty()) {
                                IconButton(onClick = viewModel::clearQuery) {
                                    Icon(Icons.Filled.Clear, contentDescription = "Clear")
                                }
                            }
                        }
                    )
                },
                expanded = expanded,
                onExpandedChange = { expanded = it },
                modifier = Modifier.fillMaxWidth()
            ) {
                if (uiState.query.isNotBlank()) {
                    TypeFilterRow(
                        uiState = uiState,
                        selectedType = selectedType,
                        onSelectType = viewModel::selectType
                    )
                }
                SearchResults(
                    uiState = uiState,
                    selectedType = selectedType,
                    onOpenNote = onOpenNote,
                    onOpenTodo = onOpenTodo,
                    onOpenCalendar = onOpenCalendar,
                    onOpenChat = onOpenChat,
                    onOpenMeeting = onOpenMeeting
                )
            }
        }
    }
}

@Composable
private fun TypeFilterRow(
    uiState: SearchUiState,
    selectedType: SearchType,
    onSelectType: (SearchType) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        FilterChip(
            selected = selectedType == SearchType.ALL,
            onClick = { onSelectType(SearchType.ALL) },
            label = { Text("All") }
        )
        FilterChip(
            selected = selectedType == SearchType.NOTES,
            onClick = { onSelectType(SearchType.NOTES) },
            label = { Text("Notes (${uiState.notes.size})") }
        )
        FilterChip(
            selected = selectedType == SearchType.TODOS,
            onClick = { onSelectType(SearchType.TODOS) },
            label = { Text("To-Dos (${uiState.todos.size})") }
        )
        FilterChip(
            selected = selectedType == SearchType.MEETINGS,
            onClick = { onSelectType(SearchType.MEETINGS) },
            label = { Text("Meetings (${uiState.meetings.size})") }
        )
        FilterChip(
            selected = selectedType == SearchType.EVENTS,
            onClick = { onSelectType(SearchType.EVENTS) },
            label = { Text("Events (${uiState.calendarEvents.size})") }
        )
    }
}

@Composable
private fun SearchResults(
    uiState: SearchUiState,
    selectedType: SearchType,
    onOpenNote: (Long) -> Unit,
    onOpenTodo: (Long) -> Unit,
    onOpenCalendar: () -> Unit,
    onOpenChat: () -> Unit,
    onOpenMeeting: (Long) -> Unit
) {
    val showNotes = selectedType == SearchType.ALL || selectedType == SearchType.NOTES
    val showTodos = selectedType == SearchType.ALL || selectedType == SearchType.TODOS
    val showMeetings = selectedType == SearchType.ALL || selectedType == SearchType.MEETINGS
    val showEvents = selectedType == SearchType.ALL || selectedType == SearchType.EVENTS
    val showMemory = selectedType == SearchType.ALL
    val hasVisibleResults = (showNotes && uiState.notes.isNotEmpty()) ||
        (showTodos && uiState.todos.isNotEmpty()) ||
        (showMeetings && uiState.meetings.isNotEmpty()) ||
        (showEvents && uiState.calendarEvents.isNotEmpty()) ||
        (showMemory && uiState.memoryLines.isNotEmpty())

    when {
        uiState.query.isBlank() -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = "Search across notes, to-dos, meetings, calendar events and memory",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(24.dp)
                )
            }
        }
        uiState.isSearching -> {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        !hasVisibleResults -> {
            EmptyState(
                icon = Icons.Filled.SearchOff,
                title = "No results",
                subtitle = "Try a different search or filter."
            )
        }
        else -> {
            LazyColumn(modifier = Modifier.fillMaxSize()) {
                if (showNotes && uiState.notes.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Notes (${uiState.notes.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.notes, key = { "note_${it.id}" }) { note ->
                        NoteResultRow(note = note, onClick = { onOpenNote(note.id) })
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                if (showTodos && uiState.todos.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "To-Dos (${uiState.todos.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.todos, key = { "todo_${it.id}" }) { todo ->
                        TodoResultRow(todo = todo, onClick = { onOpenTodo(todo.id) })
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                if (showEvents && uiState.calendarEvents.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Calendar (${uiState.calendarEvents.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.calendarEvents, key = { "cal_${it.id}" }) { event ->
                        CalendarEventResultRow(event = event, onClick = onOpenCalendar)
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                if (showMeetings && uiState.meetings.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Meetings (${uiState.meetings.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.meetings, key = { "meeting_${it.id}" }) { meeting ->
                        MeetingResultRow(meeting = meeting, onClick = { onOpenMeeting(meeting.id) })
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
                if (showMemory && uiState.memoryLines.isNotEmpty()) {
                    item {
                        SectionHeader(
                            text = "Memory (${uiState.memoryLines.size})",
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                    items(uiState.memoryLines, key = { "mem_$it" }) { line ->
                        MemoryLineRow(line = line, onClick = onOpenChat)
                        HorizontalDivider(modifier = Modifier.padding(start = 56.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
    )
}

@Composable
private fun NoteResultRow(note: Note, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.AutoMirrored.Filled.Notes,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = note.title.ifBlank { note.content.lines().first().take(60) },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (note.content.isNotBlank()) {
                Text(
                    text = note.content.take(120).replace('\n', ' '),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun TodoResultRow(todo: Todo, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CheckBox,
            contentDescription = null,
            tint = if (todo.isDone)
                MaterialTheme.colorScheme.primary
            else
                MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = todo.title,
                style = MaterialTheme.typography.bodyMedium,
                textDecoration = if (todo.isDone) TextDecoration.LineThrough else TextDecoration.None,
                color = if (todo.isDone)
                    MaterialTheme.colorScheme.onSurfaceVariant
                else
                    MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            val priorityColor = when (todo.priority) {
                Priority.URGENT -> MaterialTheme.colorScheme.error
                Priority.HIGH -> MaterialTheme.colorScheme.tertiary
                else -> null
            }
            if (priorityColor != null) {
                Text(
                    text = todo.priority.displayName,
                    style = MaterialTheme.typography.labelSmall,
                    color = priorityColor
                )
            }
        }
    }
}

@Composable
private fun CalendarEventResultRow(event: CalendarEvent, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.CalendarToday,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = event.title,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = event.formattedTime() + if (event.location != null) " · ${event.location}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun MemoryLineRow(line: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Psychology,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(end = 16.dp)
        )
        Text(
            text = line.trimStart('-', ' ', '#').trim(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun MeetingResultRow(meeting: MeetingEntity, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.Mic,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.secondary,
            modifier = Modifier.padding(end = 16.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = meeting.title.ifBlank { "Untitled meeting" },
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = formatSmartDateTime(meeting.recordedAt) +
                    if (meeting.summary.isNotBlank()) " · ${meeting.summary.take(80).replace('\n', ' ')}" else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
