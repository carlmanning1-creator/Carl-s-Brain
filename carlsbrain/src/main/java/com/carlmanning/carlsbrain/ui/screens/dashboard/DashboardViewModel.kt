package com.carlmanning.carlsbrain.ui.screens.dashboard

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.RecentlyViewedEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.WeatherInfo
import com.carlmanning.carlsbrain.data.remote.WeatherRepository
import com.carlmanning.carlsbrain.domain.model.CalendarEvent
import com.carlmanning.carlsbrain.data.health.HealthRepository
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.usecase.CompleteTodoUseCase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Calendar

sealed class ScheduleItem {
    abstract val timeMs: Long
    abstract val title: String

    data class Event(val event: CalendarEvent) : ScheduleItem() {
        override val timeMs get() = event.startMs
        override val title get() = event.title
    }
    data class TodoDue(val todo: TodoEntity) : ScheduleItem() {
        override val timeMs get() = todo.reminderAt ?: todo.dueDate ?: 0L
        override val title get() = todo.title
    }
    data class NoteReminder(val note: NoteEntity) : ScheduleItem() {
        override val timeMs get() = note.reminderAt!!
        override val title get() = note.title.ifBlank { note.content.lines().first().take(60) }
    }
}

/**
 * "What fits right now" — the answer to Carl's time-blindness question.
 * Only ever non-null when the gap is usable AND at least one to-do genuinely fits, so the
 * feature stays invisible until estimates exist rather than nagging with an empty state.
 *
 * @param gapMinutes how long the gap is, in minutes.
 * @param nextEventTitle the event that closes the gap, or null when the day is clear ahead.
 * @param todos to-dos whose estimate fits, best-first (priority, then due date, then longest).
 */
data class GapFit(
    val gapMinutes: Int,
    val nextEventTitle: String?,
    val todos: List<TodoEntity>
)

data class DashboardUiState(
    val todaySchedule: List<ScheduleItem> = emptyList(),
    val tomorrowSchedule: List<ScheduleItem> = emptyList(),
    val weekSchedule: List<ScheduleItem> = emptyList(),
    val priorityTodos: List<TodoEntity> = emptyList(),
    val overdueTodos: List<TodoEntity> = emptyList(),
    val briefing: String = "",
    val isLoadingCalendar: Boolean = false,
    val isLoadingBriefing: Boolean = false,
    val calendarError: String? = null,
    val weatherInfo: WeatherInfo? = null,
    val whatNext: String = "",
    val isLoadingWhatNext: Boolean = false,
    /** Item #5 — non-null when the briefing call failed, so the failure isn't silent. */
    val briefingError: String? = null,
    /** Item #5 — non-null when the "what next?" call failed. */
    val whatNextError: String? = null,
    /** "What fits right now" — null whenever there's no usable gap or nothing fits it. */
    val gapFit: GapFit? = null
)

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModel(app: Application) : AndroidViewModel(app) {

    private val calendarRepo = CalendarRepository(app)
    private val claude = CarlsBrainApp.claudeClient
    private val db = AppDatabase.getInstance(app)
    private val driveRepo = DriveRepository(app)
    private val completeTodo = CompleteTodoUseCase(app)

    private val _uiState = MutableStateFlow(DashboardUiState())
    val uiState: StateFlow<DashboardUiState> = _uiState.asStateFlow()

    private val _vaultOpen = MutableStateFlow(false)
    private var hasLoaded = false

    val buckets: StateFlow<List<BucketEntity>> = _vaultOpen
        .flatMapLatest { open ->
            if (open) db.bucketDao().getAllBuckets()
            else db.bucketDao().getNonVaultBuckets()
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /**
     * Item #16 — recently viewed strip.
     * Vault safety: recently_viewed rows can point at items in vault buckets. [buckets] only
     * emits non-vault buckets while the vault is closed, so filtering each entry's bucketId
     * against the visible bucket set removes vault items before they ever reach the UI.
     * Entries with a null bucketId (meetings/events) carry no bucket and are always allowed.
     */
    val recentlyViewed: StateFlow<List<RecentlyViewedEntity>> =
        combine(db.recentlyViewedDao().getRecent(10), buckets) { recent, visibleBuckets ->
            val visibleIds = visibleBuckets.map { it.id }.toSet()
            recent.filter { entry ->
                val bucketId = entry.bucketId
                bucketId == null || bucketId in visibleIds
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setVaultVisible(open: Boolean) {
        val changed = _vaultOpen.value != open
        _vaultOpen.value = open
        if (changed || !hasLoaded) {
            hasLoaded = true
            loadData()
        }
    }

    private var lastLoadMs = 0L
    private var weatherJob: Job? = null

    init {
        // Health data loaded eagerly; Dashboard data deferred to setVaultVisible()
        // so the first loadData() always uses the correct vault state passed from the screen.
        if (HealthRepository.isCacheStale()) {
            viewModelScope.launch {
                runCatching { HealthRepository(getApplication()).readHealthData(7) }
                    .onSuccess { HealthRepository.updateCache(it) }
            }
        }
    }

    fun refreshIfStale() {
        val elapsed = System.currentTimeMillis() - lastLoadMs
        if (elapsed > STALE_THRESHOLD_MS) { hasLoaded = true; loadData() }
    }

    fun loadData() {
        viewModelScope.launch {
            lastLoadMs = System.currentTimeMillis()
            _uiState.update { it.copy(isLoadingCalendar = true, calendarError = null) }
            // Load weather in parallel — cancel any in-flight fetch first
            weatherJob?.cancel()
            weatherJob = viewModelScope.launch {
                val weather = WeatherRepository().getWeather()
                if (weather != null) _uiState.update { it.copy(weatherInfo = weather) }
            }

            val now = System.currentTimeMillis()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
            val weekEnd = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()

            val allActiveTodos = if (_vaultOpen.value) {
                db.todoDao().getActiveTodos().first()
            } else {
                db.todoDao().getActiveNonVaultTodos().first()
            }
            val priorityTodos = allActiveTodos.filter { it.priority in listOf(0, 1) }
            val overdueTodos = allActiveTodos.filter { it.dueDate != null && it.dueDate < now }
            val remindersToday = allActiveTodos.filter {
                it.reminderAt != null && it.reminderAt in todayStart until todayEnd
            }
            val floatingCount = allActiveTodos.count { it.dueDate == null }

            // Todos with a specific due time or reminder in next 7 days (skip calendar-imported ones)
            val scheduledTodos = allActiveTodos.filter { todo ->
                todo.calendarEventId == null && (
                    (todo.dueDate != null && todo.dueDate >= todayStart && todo.dueDate < weekEnd) ||
                    (todo.reminderAt != null && todo.reminderAt >= todayStart && todo.reminderAt < weekEnd)
                )
            }

            // De-duplicate the three todo surfaces so one todo shows in exactly one place.
            // Precedence: Overdue > Needs attention (priority) > Today's schedule.
            val overdueIds = overdueTodos.map { it.id }.toSet()
            val visiblePriorityTodos = priorityTodos.filter { it.id !in overdueIds }
            val claimedIds = overdueIds + visiblePriorityTodos.map { it.id }
            val visibleScheduledTodos = scheduledTodos.filter { it.id !in claimedIds }

            val scheduledNotes = if (_vaultOpen.value) {
                db.noteDao().getAllNotesWithReminders(todayStart, weekEnd)
            } else {
                db.noteDao().getNotesWithReminders(todayStart, weekEnd)
            }

            _uiState.update { it.copy(priorityTodos = visiblePriorityTodos, overdueTodos = overdueTodos) }

            fun itemDate(ms: Long) = Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()

            fun buildSchedule(
                events: List<CalendarEvent>,
                includeCalendar: Boolean
            ): Triple<List<ScheduleItem>, List<ScheduleItem>, List<ScheduleItem>> {
                val tomorrow = today.plusDays(1)
                val all = buildList {
                    if (includeCalendar) events.forEach { add(ScheduleItem.Event(it)) }
                    visibleScheduledTodos.forEach { add(ScheduleItem.TodoDue(it)) }
                    scheduledNotes.forEach { add(ScheduleItem.NoteReminder(it)) }
                }
                val todayItems = all.filter { itemDate(it.timeMs) == today }.sortedBy { it.timeMs }
                val tomorrowItems = all.filter { itemDate(it.timeMs) == tomorrow }.sortedBy { it.timeMs }
                val weekItems = all.filter {
                    val d = itemDate(it.timeMs)
                    d.isAfter(tomorrow) && !d.isAfter(today.plusDays(6))
                }.sortedBy { it.timeMs }
                return Triple(todayItems, tomorrowItems, weekItems)
            }

            calendarRepo.getUpcomingEvents(daysAhead = 7).fold(
                onSuccess = { events ->
                    val tomorrow = today.plusDays(1)
                    val todayCalEvents = events.filter { itemDate(it.startMs) == today }
                    val tomorrowCalEvents = events.filter { itemDate(it.startMs) == tomorrow }

                    val (todayItems, tomorrowItems, weekItems) = buildSchedule(events, includeCalendar = true)
                    _uiState.update {
                        it.copy(
                            todaySchedule = todayItems,
                            tomorrowSchedule = tomorrowItems,
                            weekSchedule = weekItems,
                            isLoadingCalendar = false,
                            gapFit = computeGapFit(todayCalEvents, allActiveTodos)
                        )
                    }
                    importCalendarEventsTodos(todayCalEvents + tomorrowCalEvents)
                    generateBriefing(todayCalEvents, priorityTodos, overdueTodos, remindersToday, floatingCount)
                },
                onFailure = { e ->
                    val (todayItems, tomorrowItems, weekItems) = buildSchedule(emptyList(), includeCalendar = false)
                    _uiState.update {
                        it.copy(
                            todaySchedule = todayItems,
                            tomorrowSchedule = tomorrowItems,
                            weekSchedule = weekItems,
                            calendarError = e.message,
                            isLoadingCalendar = false,
                            // No calendar means no known next event — treat the gap as open-ended.
                            gapFit = computeGapFit(emptyList(), allActiveTodos)
                        )
                    }
                    generateBriefing(emptyList(), priorityTodos, overdueTodos, remindersToday, floatingCount)
                }
            )
        }
    }

    /**
     * "What fits right now" — measures the gap from now to the next timed event today, then picks
     * the to-dos that genuinely fit inside it.
     *
     * The gap is `nextEvent.startMs - now`. All-day events are ignored (they block no time) as are
     * events already started. With no next event today the day is open ahead, so the gap is capped
     * at [OPEN_GAP_CAP_MINUTES] rather than being treated as infinite — an unbounded gap would just
     * surface everything and defeat the point.
     *
     * Returns null — the section then renders nothing at all — when the gap is under
     * [MIN_GAP_MINUTES] or when nothing fits. To-dos without an estimate are skipped, never guessed
     * at, so the whole feature stays silent until Carl has set some estimates.
     *
     * [todos] must already be vault-filtered by the caller.
     */
    private fun computeGapFit(todayEvents: List<CalendarEvent>, todos: List<TodoEntity>): GapFit? {
        val now = System.currentTimeMillis()
        val nextEvent = todayEvents
            .filter { !it.isAllDay && it.startMs > now }
            .minByOrNull { it.startMs }

        val gapMinutes = if (nextEvent == null) {
            OPEN_GAP_CAP_MINUTES
        } else {
            ((nextEvent.startMs - now) / 60_000L).toInt()
        }
        if (gapMinutes < MIN_GAP_MINUTES) return null

        val fitting = todos
            .filter { todo ->
                val estimate = todo.estimateMinutes
                !todo.isDone && estimate != null && estimate <= gapMinutes
            }
            // Highest priority first, then nearest due date, then the biggest job that still fits.
            .sortedWith(
                compareBy<TodoEntity> { it.priority }
                    .thenBy { it.dueDate ?: Long.MAX_VALUE }
                    .thenByDescending { it.estimateMinutes ?: 0 }
            )
            .take(MAX_GAP_SUGGESTIONS)
        if (fitting.isEmpty()) return null

        return GapFit(
            gapMinutes = gapMinutes,
            nextEventTitle = nextEvent?.title,
            todos = fitting
        )
    }

    private suspend fun importCalendarEventsTodos(events: List<CalendarEvent>) {
        val nonAllDay = events.filter { !it.isAllDay }
        if (nonAllDay.isEmpty()) return
        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
            ?: return
        for (event in nonAllDay) {
            // Guard must see soft-deleted rows too, otherwise a todo Carl deleted
            // is resurrected on the next dashboard refresh.
            if (db.todoDao().findAnyByCalendarEventId(event.id) == null) {
                db.todoDao().insertTodo(
                    TodoEntity(
                        title = event.title,
                        bucketId = defaultBucket.id,
                        dueDate = event.startMs,
                        calendarEventId = event.id
                    )
                )
            }
        }
    }

    private fun generateBriefing(
        todayEvents: List<CalendarEvent>,
        priorityTodos: List<TodoEntity>,
        overdueTodos: List<TodoEntity>,
        remindersToday: List<TodoEntity>,
        floatingCount: Int
    ) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoadingBriefing = true, briefingError = null) }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val timeOfDay = when (hour) {
                in 5..11 -> "morning"
                in 12..17 -> "afternoon"
                in 18..23 -> "evening"
                else -> "late night"
            }

            val eventsStr = if (todayEvents.isEmpty()) "no calendar events today"
                else todayEvents.joinToString("; ") { "${it.formattedTime()} — ${it.title}" }

            val priorityStr = if (priorityTodos.isEmpty()) "none"
                else priorityTodos.take(5).joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }

            val overdueStr = if (overdueTodos.isEmpty()) "none"
                else overdueTodos.take(3).joinToString("; ") { it.title } +
                    if (overdueTodos.size > 3) " (+ ${overdueTodos.size - 3} more)" else ""

            val remindersStr = if (remindersToday.isEmpty()) "none"
                else remindersToday.joinToString("; ") { it.title }

            val healthStr = HealthRepository.getCachedContextString()

            val prompt = """It is ${timeOfDay}. Write Carl a thorough but concise briefing in 3–4 natural sentences.
Be warm and direct. Help him not miss anything important. If there are overdue tasks, flag them clearly.
If reminders are due today, mention them. If he has floating tasks with no due date, give a gentle nudge.
End with one clear, practical next action.

Today's calendar: $eventsStr
Urgent/High priority tasks: $priorityStr
Overdue tasks: $overdueStr
Reminders due today: $remindersStr
Tasks with no due date: $floatingCount
${if (healthStr.isNotBlank()) "\nHealth context: $healthStr" else ""}
No bullet points — flowing prose only. Don't start with "Good morning/afternoon" — jump straight into the content."""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You are Carl's personal assistant. Carl is a NSW SES Deputy at Dubbo Unit with ADHD. Be thorough, warm, and actionable. Help him stay on top of everything without feeling overwhelmed.",
                model = ClaudeClient.HAIKU
            ).onSuccess { briefing ->
                _uiState.update {
                    it.copy(briefing = briefing, isLoadingBriefing = false, briefingError = null)
                }
            }.onFailure {
                _uiState.update {
                    it.copy(isLoadingBriefing = false, briefingError = OFFLINE_MESSAGE)
                }
            }
        }
    }

    /** Item #5 — retry affordance for a failed briefing. Re-runs the whole dashboard load. */
    fun retryBriefing() {
        hasLoaded = true
        loadData()
    }

    fun dismissWhatNext() {
        _uiState.update { it.copy(whatNext = "", whatNextError = null) }
    }

    fun askWhatNext() {
        viewModelScope.launch {
            val apiKey = CarlsBrainApp.userPreferences.anthropicApiKey.first()
            if (apiKey.isBlank()) {
                _uiState.update {
                    it.copy(
                        whatNext = "Add your Anthropic API key in Settings to use this feature",
                        whatNextError = null
                    )
                }
                return@launch
            }

            _uiState.update { it.copy(isLoadingWhatNext = true, whatNextError = null) }

            val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
            val minute = Calendar.getInstance().get(Calendar.MINUTE)
            val time = String.format("%02d:%02d", hour, minute)

            val state = _uiState.value

            // Top 5 undone todos sorted by priority then due date
            val allActiveTodos = if (_vaultOpen.value) {
                db.todoDao().getActiveTodos().first()
            } else {
                db.todoDao().getActiveNonVaultTodos().first()
            }
            val topTodos = allActiveTodos
                .sortedWith(compareBy({ it.priority }, { it.dueDate ?: Long.MAX_VALUE }))
                .take(5)
                .joinToString("; ") { "[${Priority.fromRank(it.priority).displayName}] ${it.title}" }
                .ifEmpty { "none" }

            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val todayStart = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val todayEnd = today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()

            val eventsStr = state.todaySchedule
                .filterIsInstance<ScheduleItem.Event>()
                .joinToString("; ") { "${it.event.formattedTime()} — ${it.event.title}" }
                .ifEmpty { "no calendar events today" }

            val memory = driveRepo.getMemoryMd() ?: ""

            val prompt = """It is $time. Based on Carl's current situation, pick ONE specific task or action he should do right now. Be direct — name the exact task. Give a single sentence explaining why. Keep the total response under 40 words.

His todos (priority order): $topTodos
Today's calendar: $eventsStr"""

            val systemPrompt = "You are Carl's personal assistant. Carl is a NSW SES Deputy at Dubbo Unit with ADHD. Be direct and concise. Always name one specific action." +
                if (memory.isNotBlank()) "\n\nCarl's memory context:\n$memory" else ""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = systemPrompt,
                model = ClaudeClient.HAIKU,
                maxTokens = 80
            ).onSuccess { result ->
                _uiState.update {
                    it.copy(whatNext = result, isLoadingWhatNext = false, whatNextError = null)
                }
            }.onFailure {
                _uiState.update {
                    it.copy(isLoadingWhatNext = false, whatNextError = OFFLINE_MESSAGE)
                }
            }
        }
    }

    // ── To-do completion from the Dashboard ────────────────────────
    /**
     * Occurrences spawned by a completion in this session, keyed by the completed to-do's id.
     * Kept so [toggleDone]'s un-tick — which the Dashboard's Undo snackbar calls — can remove the
     * spawned occurrence instead of leaving a duplicate behind.
     */
    private val spawnedByCompletion = mutableMapOf<Long, Long>()

    /**
     * Item #1 — tick a to-do off without opening the editor.
     * Completion (and its recurrence spawning, idempotency-guarded) lives in [CompleteTodoUseCase],
     * shared with the Todos screen so the two can never drift.
     * Un-ticking doubles as the Undo: it also deletes the occurrence this completion spawned.
     * Reloads the dashboard afterwards so the row leaves the list.
     */
    fun toggleDone(todoId: Long, isDone: Boolean) {
        viewModelScope.launch {
            if (isDone) {
                completeTodo.markDone(todoId, true)?.let { spawnedByCompletion[todoId] = it }
            } else {
                completeTodo.undoDone(todoId, spawnedByCompletion.remove(todoId))
            }
            hasLoaded = true
            loadData()
        }
    }

    companion object {
        private const val STALE_THRESHOLD_MS = 15 * 60 * 1000L // 15 minutes
        /** Below this a "gap" isn't worth starting anything in. */
        private const val MIN_GAP_MINUTES = 10
        /** Ceiling used when nothing is scheduled ahead today. */
        private const val OPEN_GAP_CAP_MINUTES = 120
        private const val MAX_GAP_SUGGESTIONS = 4
        private const val OFFLINE_MESSAGE =
            "Couldn't reach Claude — check your connection and try again"
    }
}
