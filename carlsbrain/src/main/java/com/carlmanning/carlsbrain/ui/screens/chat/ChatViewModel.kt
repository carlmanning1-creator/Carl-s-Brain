package com.carlmanning.carlsbrain.ui.screens.chat

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import androidx.core.app.ActivityCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.health.HealthRepository
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ClaudeClient
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import com.carlmanning.carlsbrain.domain.chat.ChatTools
import com.carlmanning.carlsbrain.domain.defaultBucket
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.usecase.CompleteTodoUseCase
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val content: String,
    val isFromUser: Boolean,
    val createdTodoTitles: List<String> = emptyList(),
    val createdNoteTitles: List<String> = emptyList(),
    val completedTodoTitles: List<String> = emptyList()
)

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val memoryLoaded: Boolean = false,
    val isListening: Boolean = false,
    val partialText: String = "",
    val voiceError: String? = null,
    val isSpeakingEnabled: Boolean = false,
    /**
     * Unleashed: Opus 5 with web search and fetch, instead of Sonnet 5 on Carl's own material.
     *
     * Per-conversation and off by default, deliberately. It is a mode flipped for a question
     * rather than a preference, and each search costs real money — a visible switch is right,
     * a quietly-remembered setting is not. It also resets whenever the screen is rebuilt,
     * which is the safe direction to fail in.
     */
    val isUnleashed: Boolean = false
)

class ChatViewModel(app: Application) : AndroidViewModel(app) {

    private val claude = CarlsBrainApp.claudeClient
    private val drive = DriveRepository(app)
    private val db = AppDatabase.getInstance(app)
    private val calendarRepo = CalendarRepository(app)

    private val _uiState = MutableStateFlow(ChatUiState())
    val uiState: StateFlow<ChatUiState> = _uiState.asStateFlow()

    private var memoryMd: String = DriveRepository.INITIAL_MEMORY
    private val apiHistory = mutableListOf<ApiMessage>()
    private var speechRecognizer: SpeechRecognizer? = null
    private var lastPartialText: String = ""
    private var tts: TextToSpeech? = null
    private var ttsReady = false

    // The thread this chat session belongs to (set by loadThread)
    private var currentThreadId: Long = -1L

    fun loadThread(threadId: Long) {
        if (currentThreadId == threadId) return
        currentThreadId = threadId
        viewModelScope.launch {
            val msgs = db.chatDao().getMessagesForThread(threadId).map { entity ->
                ChatMessage(
                    id = entity.id,
                    content = entity.content,
                    isFromUser = entity.isFromUser
                )
            }
            // Rebuild apiHistory from persisted messages
            apiHistory.clear()
            msgs.forEach { msg ->
                apiHistory.add(ApiMessage(role = if (msg.isFromUser) "user" else "assistant", content = msg.content))
            }
            _uiState.update { it.copy(messages = msgs) }
        }
    }

    private fun persistMessage(threadId: Long, content: String, isFromUser: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            db.chatDao().insertMessage(
                com.carlmanning.carlsbrain.data.local.entity.ChatMessageEntity(
                    threadId = threadId,
                    content = content,
                    isFromUser = isFromUser
                )
            )
            // Update thread title from first user message and bump updatedAt
            val thread = db.chatDao().getThreadById(threadId) ?: return@launch
            val newTitle = if (thread.title == "New conversation" && isFromUser) {
                content.take(50).trimEnd()
            } else thread.title
            // isSynced = false on every message, not only when the title changes: the Drive
            // file holds the whole conversation, so a thread whose row is unchanged but whose
            // messages have grown is stale.
            db.chatDao().updateThread(
                thread.copy(
                    title = newTitle,
                    updatedAt = System.currentTimeMillis(),
                    isSynced = false
                )
            )
        }
    }

    // Live bucket names — updated automatically when buckets are added/renamed
    private val liveBucketNames: StateFlow<String> = db.bucketDao()
        .getNonVaultBuckets()
        .map { list -> list.joinToString(", ") { it.name } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "SES, Family, Work, Personal, Other")

    private val todoRegex = Regex("""\[TODO:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val noteRegex = Regex("""\[NOTE:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val doneRegex = Regex("""\[DONE:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val calendarRegex = Regex("""\[CALENDAR:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)

    init {
        loadMemory()
        loadRecentMeetings()
    }

    private fun loadMemory() {
        viewModelScope.launch {
            val stored = drive.getMemoryMd()
            if (stored != null) {
                memoryMd = stored
            } else {
                drive.updateMemoryMd(DriveRepository.INITIAL_MEMORY)
            }
            _uiState.update { it.copy(memoryLoaded = true) }
        }
    }

    fun onInputTextChange(text: String) {
        _uiState.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val text = _uiState.value.inputText.trim()
        if (text.isBlank() || _uiState.value.isLoading) return

        apiHistory.add(ApiMessage(role = "user", content = text))
        if (currentThreadId != -1L) persistMessage(currentThreadId, text, isFromUser = true)
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(content = text, isFromUser = true),
                inputText = "",
                isLoading = true
            )
        }

        viewModelScope.launch {
            requestReply().fold(
                onSuccess = { reply ->
                    val createdTodoTitles = parseAndCreateTodos(reply)
                    val createdNoteTitles = parseAndCreateNotes(reply, userMessage = text)
                    val completedTodoTitles = parseAndCompleteTodos(reply)
                    parseAndCreateCalendarEvents(reply)
                    val displayReply = calendarRegex.replace(todoRegex.replace(noteRegex.replace(doneRegex.replace(reply, ""), ""), ""), "").trim()

                    apiHistory.add(ApiMessage(role = "assistant", content = displayReply))
                    if (currentThreadId != -1L) persistMessage(currentThreadId, displayReply, isFromUser = false)
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + ChatMessage(
                                content = displayReply,
                                isFromUser = false,
                                createdTodoTitles = createdTodoTitles,
                                createdNoteTitles = createdNoteTitles,
                                completedTodoTitles = completedTodoTitles
                            ),
                            isLoading = false
                        )
                    }
                    if (_uiState.value.isSpeakingEnabled) speakResponse(displayReply)
                    maybeUpdateMemory(userMsg = text, assistantReply = displayReply)
                },
                onFailure = { e ->
                    apiHistory.removeLastOrNull()
                    _uiState.update { state ->
                        state.copy(
                            messages = state.messages + ChatMessage(
                                content = "Error: ${e.message}",
                                isFromUser = false
                            ),
                            isLoading = false
                        )
                    }
                }
            )
        }
    }

    private suspend fun parseAndCreateTodos(response: String): List<String> {
        val matches = todoRegex.findAll(response)
        val created = mutableListOf<String>()

        // Non-vault only, matching MeetingViewModel.autoSortBucket. Chat is a vault-closed
        // surface: the system prompt only ever lists non-vault bucket names, and completion
        // goes through the vault-filtered searchTodos — so filing INTO a vault bucket created
        // something Chat could then never find, complete or show. One rule, both directions.
        val buckets = db.bucketDao().getNonVaultBuckets().first()
        val defaultBucket = buckets.defaultBucket()
            ?: buckets.firstOrNull()
            ?: return emptyList()

        for (match in matches) {
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { continue }
            val bucketName = parts.getOrElse(1) { "Other" }
            val priorityStr = parts.getOrElse(2) { "NORMAL" }.uppercase()

            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) }
                ?: defaultBucket
            val priority = runCatching { Priority.valueOf(priorityStr) }
                .getOrDefault(Priority.NORMAL)

            db.todoDao().insertTodo(
                TodoEntity(title = title, bucketId = bucket.id, priority = priority.rank)
            )
            created.add(title)
        }
        return created
    }

    private suspend fun parseAndCreateNotes(response: String, userMessage: String): List<String> {
        val matches = noteRegex.findAll(response)
        val created = mutableListOf<String>()

        // Non-vault only, matching MeetingViewModel.autoSortBucket. Chat is a vault-closed
        // surface: the system prompt only ever lists non-vault bucket names, and completion
        // goes through the vault-filtered searchTodos — so filing INTO a vault bucket created
        // something Chat could then never find, complete or show. One rule, both directions.
        val buckets = db.bucketDao().getNonVaultBuckets().first()
        val defaultBucket = buckets.defaultBucket()
            ?: buckets.firstOrNull()
            ?: return emptyList()

        for (match in matches) {
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { continue }
            val bucketName = parts.getOrElse(1) { "Other" }
            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) } ?: defaultBucket

            db.noteDao().insertNote(
                NoteEntity(
                    title = title,
                    content = userMessage,
                    bucketId = bucket.id
                )
            )
            created.add(title)
        }
        return created
    }

    private suspend fun parseAndCompleteTodos(response: String): List<String> {
        val matches = doneRegex.findAll(response)
        val completed = mutableListOf<String>()

        for (match in matches) {
            val titleQuery = match.groupValues[1].trim().ifBlank { continue }
            val todo = db.todoDao().searchTodos(titleQuery).firstOrNull { !it.isDone } ?: continue
            // Through the use case, not setTodoDone: completing a recurring to-do has to spawn
            // the next occurrence. Ticking one off in Chat used to end the recurrence silently.
            CompleteTodoUseCase(getApplication()).markDone(todo.id, true)
            completed.add(todo.title)
        }
        return completed
    }

    private fun parseAndCreateCalendarEvents(response: String) {
        val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm")
        val zone = ZoneId.systemDefault()
        calendarRegex.findAll(response).forEach { match ->
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { return@forEach }
            val startStr = parts.getOrElse(1) { "" }.ifBlank { return@forEach }
            val endStr = parts.getOrElse(2) { "" }.ifBlank { return@forEach }
            val location = parts.getOrNull(3)?.ifBlank { null }
            runCatching {
                val startMs = LocalDateTime.parse(startStr, fmt).atZone(zone).toInstant().toEpochMilli()
                val endMs = LocalDateTime.parse(endStr, fmt).atZone(zone).toInstant().toEpochMilli()
                viewModelScope.launch { calendarRepo.createEvent(title, startMs, endMs, location) }
            }
        }
    }

    private fun maybeUpdateMemory(userMsg: String, assistantReply: String) {
        // "Remember" keyword: bypass the evaluator and force-save immediately.
        if (isExplicitRemember(userMsg)) {
            val ctx: android.content.Context = getApplication()
            MemoryLearner.forceLearnFrom(ctx, "Chat — User: \"$userMsg\" | Assistant: \"${assistantReply.take(400)}\"")
            return
        }

        viewModelScope.launch {
            val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
            val prompt = """Review this conversation exchange for facts worth adding to Carl's permanent memory.

Look for:
- Facts about Carl's life, people, routines, and recurring commitments
- Explicit preferences about how the AI behaves or formats responses
- Recurring patterns and habits
- Important decisions, plans, or context discussed

User said: "$userMsg"
Assistant replied: "${assistantReply.take(500)}"

Current memory (tail): ...${memoryMd.takeLast(300)}

Write any new facts as concise bullets: - [$date] Fact
If truly nothing new was discussed, respond with exactly: NONE"""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You maintain Carl's memory file. Carl has ADHD and relies on this memory heavily — default to capturing. If there's a reasonable chance he'd want it remembered, include it. Err on the side of saving rather than discarding. Avoid repeating facts already in the memory tail. Return bullet lines or NONE.",
                model = ClaudeClient.HAIKU
            ).onSuccess { response ->
                val trimmed = response.trim()
                if (trimmed != "NONE" && trimmed.isNotBlank() && !trimmed.startsWith("Error")) {
                    val toAppend = if (trimmed.startsWith("- [")) {
                        trimmed
                    } else {
                        val date2 = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        "- [$date2] $trimmed"
                    }
                    memoryMd += "\n$toAppend"
                    drive.updateMemoryMd(memoryMd)
                    MemoryLearner.invalidateCache()
                }
            }
        }
    }

    private fun isExplicitRemember(text: String): Boolean {
        val lower = text.lowercase()
        return "remember this" in lower || "remember that" in lower ||
               "remember our" in lower || "save this to memory" in lower ||
               "save that to memory" in lower || "save to memory" in lower ||
               lower.startsWith("remember ") || lower == "remember"
    }

    // ── Wake word coordination ────────────────────────────────────────────────

    private fun pauseWakeWord() {
        val ctx: android.content.Context = getApplication()
        runCatching {
            ctx.startService(Intent(ctx, VoiceCaptureService::class.java).apply {
                action = VoiceCaptureService.ACTION_STOP_WAKE_WORD
            })
        }
    }

    private fun resumeWakeWord() {
        val ctx: android.content.Context = getApplication()
        runCatching {
            ctx.startService(Intent(ctx, VoiceCaptureService::class.java).apply {
                action = VoiceCaptureService.ACTION_RESUME_WAKE_WORD
            })
        }
    }

    // ── Voice input ───────────────────────────────────────────────────────────

    fun startListening() {
        viewModelScope.launch(Dispatchers.Main) {
            val ctx: android.content.Context = getApplication()
            if (!SpeechRecognizer.isRecognitionAvailable(ctx)) return@launch
            if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) return@launch

            // Release the mic from the wake-word spotter before SpeechRecognizer opens it.
            pauseWakeWord()

            speechRecognizer?.destroy()
            lastPartialText = ""
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(ctx).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {
                        _uiState.update { it.copy(isListening = true, partialText = "", voiceError = null) }
                    }
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() {}
                    override fun onError(errorCode: Int) {
                        val captured = lastPartialText.trim()
                        if (captured.isNotBlank()) {
                            _uiState.update { it.copy(inputText = captured, isListening = false, partialText = "") }
                            if (_uiState.value.isSpeakingEnabled) sendMessage() else resumeWakeWord()
                        } else {
                            // ERROR_SERVER_DISCONNECTED fires on Android 12+ when the audio device
                            // hasn't finished switching from TTS speaker to mic. Retry silently.
                            val isServerDisconnect = errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED
                                    && _uiState.value.isSpeakingEnabled
                            if (isServerDisconnect) {
                                _uiState.update { it.copy(isListening = false, partialText = "") }
                                viewModelScope.launch(Dispatchers.Main) {
                                    delay(800)
                                    if (!_uiState.value.isListening && !_uiState.value.isLoading) startListening()
                                }
                            } else {
                                val msg = when (errorCode) {
                                    SpeechRecognizer.ERROR_NO_MATCH -> "Nothing recognised — try speaking more clearly"
                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "No speech detected — try speaking louder"
                                    SpeechRecognizer.ERROR_AUDIO -> "Microphone error — check mic access in Settings"
                                    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Network error"
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Microphone permission needed"
                                    SpeechRecognizer.ERROR_CLIENT -> null
                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Voice service busy — try again"
                                    SpeechRecognizer.ERROR_SERVER -> "Voice service error — try again"
                                    SpeechRecognizer.ERROR_SERVER_DISCONNECTED -> "Voice connection dropped — tap mic to retry"
                                    else -> "Voice input failed (code $errorCode)"
                                }
                                _uiState.update { it.copy(isListening = false, partialText = "", voiceError = msg) }
                                if (!_uiState.value.isSpeakingEnabled) resumeWakeWord()
                            }
                        }
                    }
                    override fun onResults(results: Bundle?) {
                        val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()?.trim() ?: lastPartialText.trim()
                        lastPartialText = ""
                        if (text.isNotBlank()) {
                            _uiState.update { it.copy(inputText = text, isListening = false, partialText = "") }
                            if (_uiState.value.isSpeakingEnabled) sendMessage() else resumeWakeWord()
                        } else {
                            _uiState.update { it.copy(isListening = false, partialText = "") }
                            if (!_uiState.value.isSpeakingEnabled) resumeWakeWord()
                        }
                    }
                    override fun onPartialResults(partialResults: Bundle?) {
                        val partial = partialResults
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: return
                        if (partial.isNotBlank()) {
                            lastPartialText = partial
                            _uiState.update { it.copy(partialText = partial) }
                        }
                    }
                    override fun onEvent(e: Int, p: Bundle?) {}
                })
            }
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2500L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                // Required in release builds — without the calling package the Google speech
                // service may initialise but refuse to open the audio device.
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, ctx.packageName)
            }
            speechRecognizer?.startListening(intent)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun consumeVoiceError() {
        _uiState.update { it.copy(voiceError = null) }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

    fun toggleUnleashed() {
        _uiState.update { it.copy(isUnleashed = !it.isUnleashed) }
    }

    fun toggleSpeaking() {
        val enabled = !_uiState.value.isSpeakingEnabled
        _uiState.update { it.copy(isSpeakingEnabled = enabled) }
        if (enabled) {
            pauseWakeWord()
            initTts()
        } else {
            tts?.stop()
            resumeWakeWord()
        }
    }

    private fun initTts() {
        if (tts != null) return
        val ctx: android.content.Context = getApplication()
        tts = TextToSpeech(ctx) { status ->
            if (status == TextToSpeech.SUCCESS) {
                ttsReady = true
                tts?.language = java.util.Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        // Auto-restart mic after each response to create a hands-free loop.
                        // Delay 600ms to let the audio device finish switching from output
                        // (TTS speaker) to input (mic) — without this, Android 12+ fires
                        // ERROR_SERVER_DISCONNECTED (11) and the mic never opens.
                        if (_uiState.value.isSpeakingEnabled) {
                            viewModelScope.launch(Dispatchers.Main) {
                                delay(600)
                                if (!_uiState.value.isListening && !_uiState.value.isLoading) {
                                    startListening()
                                }
                            }
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {}
                })
            }
        }
    }

    private fun speakResponse(text: String) {
        if (!ttsReady) return
        val plain = text
            .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")
            .replace(Regex("\\*(.+?)\\*"), "$1")
            .replace(Regex("^#{1,6}\\s+", RegexOption.MULTILINE), "")
            .replace(Regex("\\[(.+?)\\]\\(.+?\\)"), "$1")
            .replace(Regex("```[\\s\\S]*?```"), "code block")
            .replace(Regex("`(.+?)`"), "$1")
            .trim()
        if (plain.isBlank()) return
        tts?.speak(plain, TextToSpeech.QUEUE_FLUSH, null, "carl_response")
    }

    override fun onCleared() {
        super.onCleared()
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        // Always resume wake word on exit. pauseWakeWord() is called on mic button press and
        // TTS toggle — if the user navigates away before the recognizer finishes (or at all),
        // resumeWakeWord() inside onResults/onError may never fire, leaving Hey Brain paused
        // indefinitely. resumeWakeWord() is safe to call unconditionally: if the spotter is
        // already running (isListening = true), startWakeWordLoop() returns early; if wake
        // word is disabled in Settings (DataStore), the RESUME_WAKE_WORD handler skips it.
        resumeWakeWord()
    }

    // ── Meetings context ──────────────────────────────────────────────────────

    fun clearConversation() {
        apiHistory.clear()
        _uiState.update { it.copy(messages = emptyList()) }
        loadRecentMeetings()
    }

    private var recentMeetingsSummary: String = ""

    private fun loadRecentMeetings() {
        viewModelScope.launch {
            val meetings = db.meetingDao().getRecentDoneMeetings(5)
            if (meetings.isEmpty()) return@launch
            val fmt = SimpleDateFormat("d MMM yyyy", Locale.getDefault())
            recentMeetingsSummary = meetings.joinToString("\n\n") { m ->
                val date = fmt.format(Date(m.recordedAt))
                buildString {
                    append("### ${m.title} ($date)")
                    if (m.summary.isNotBlank()) append("\n${m.summary.take(400)}")
                    if (m.transcript.isNotBlank()) append("\n\nTranscript excerpt: ${m.transcript.take(300)}…")
                }
            }
        }
    }

    /**
     * Gets one reply, by whichever route the current mode calls for.
     *
     * Default is a single request on Sonnet 5 — the behaviour Chat has always had, and the one
     * every existing marker path was written against. Unleashed goes through [runToolLoop],
     * which is the only place in the app with an agent loop in it.
     *
     * Both return plain text, so everything downstream — the markers, the transcript, TTS,
     * memory learning — is identical either way and cannot drift between the two modes.
     */
    private suspend fun requestReply(): Result<String> {
        val systemPrompt = buildSystemPrompt()
        if (!_uiState.value.isUnleashed) {
            return claude.chat(
                messages = apiHistory.toList(),
                systemPrompt = systemPrompt,
                // Chat is the one surface Carl thinks *with*, rather than captures into, so it
                // gets the better model and lets Claude decide when a question is worth
                // thinking about. Every background call in the app stays on Haiku.
                model = ClaudeClient.SONNET,
                // Haiku's 1024 was enough for a marker-laden reply and nothing more; a reasoned
                // answer that stops mid-sentence is worse than a short one.
                maxTokens = 4096,
                adaptiveThinking = true
            )
        }
        // Wrapped, because everything downstream depends on this returning rather than
        // throwing. isLoading is cleared in the fold; an exception escaping into
        // viewModelScope.launch would leave Chat spinning with no way back short of killing
        // the app — the unrecoverable state the code review gate exists to catch.
        return runCatching { runToolLoop(systemPrompt) }
            .getOrElse { Result.failure(it) }
    }

    /**
     * The agentic loop: request, run whatever Claude asked for, feed the results back, repeat.
     *
     * Bounded at [ChatTools.MAX_ITERATIONS]. An unbounded loop against a paid API is the one
     * shape here that could quietly cost Carl real money, and a question that genuinely needs
     * seven lookups is a question worth him rephrasing.
     *
     * Assistant blocks go back verbatim rather than being rebuilt from the text: the API pairs
     * each tool result with the tool_use block that asked for it, and an approximation is
     * rejected outright.
     *
     * A failing tool is reported to Claude as text rather than thrown, so it can say it could
     * not look. Losing the whole answer because one lookup failed would be worse than the
     * answer being incomplete and honest about it.
     */
    private suspend fun runToolLoop(systemPrompt: String): Result<String> {
        // Built from apiHistory each time rather than kept between questions: the tool_use and
        // tool_result blocks belong to this question only. Carrying them forward would grow the
        // request without bound and re-send stale lookups on every follow-up. Only the final
        // text joins apiHistory, exactly as in the default mode.
        val messages = apiHistory.map { msg ->
            buildJsonObject {
                put("role", JsonPrimitive(msg.role))
                put("content", JsonPrimitive(msg.content))
            }
        }.toMutableList()

        // Narration accumulates across turns. Claude typically says what it is about to look
        // for, then answers after the results arrive; showing only the last turn would drop
        // the first half, and showing only the first would drop the answer.
        val narration = StringBuilder()

        repeat(ChatTools.MAX_ITERATIONS) {
            val turn = claude.chatTurn(
                messages = JsonArray(messages),
                systemPrompt = systemPrompt,
                model = ClaudeClient.OPUS,
                maxTokens = 16000,
                tools = ChatTools.UNLEASHED_TOOLS
            ).getOrElse { return Result.failure(it) }

            if (turn.text.isNotBlank()) {
                if (narration.isNotEmpty()) narration.append("\n\n")
                narration.append(turn.text)
            }
            if (turn.toolUses.isEmpty()) {
                val answer = narration.toString().trim()
                // A blank reply would render as an empty bubble with nothing to explain it —
                // and blank is a real possibility here, where a turn can consist entirely of
                // tool calls. Better to say so.
                return if (answer.isBlank()) {
                    Result.failure(Exception("Claude came back with nothing to say — try again."))
                } else {
                    Result.success(answer)
                }
            }

            messages.add(
                buildJsonObject {
                    put("role", JsonPrimitive("assistant"))
                    put("content", turn.content)
                }
            )
            val results = turn.toolUses.map { use ->
                val output = ChatTools.execute(use, db, calendarRepo)
                buildJsonObject {
                    put("type", JsonPrimitive("tool_result"))
                    put("tool_use_id", JsonPrimitive(use.id))
                    put("content", JsonPrimitive(output))
                }
            }
            messages.add(
                buildJsonObject {
                    put("role", JsonPrimitive("user"))
                    put("content", JsonArray(results))
                }
            )
        }

        // Out of iterations. Whatever Claude has said so far is still worth showing — it is
        // usually most of the answer — but the ceiling is stated rather than hidden, because
        // an answer that silently stopped looking is worse than one that says it did.
        val partial = narration.toString().trim()
        return Result.success(
            if (partial.isBlank()) "I ran out of lookups before I could answer that — try asking it more narrowly."
            else "$partial\n\n_(I hit the lookup limit, so this may be incomplete.)_"
        )
    }

    private fun buildSystemPrompt(): String {
        val meetingsSection = if (recentMeetingsSummary.isNotBlank()) """

        ## Recent Meetings (last 5)
        $recentMeetingsSummary
        """ else ""

        val healthCtx = HealthRepository.getCachedContextString()

        // Only present in unleashed mode. Without the instruction Claude searches the web for
        // things it already knows, and — worse — for things about Carl, whose material is in
        // the context above and is emphatically not on the internet.
        val unleashedSection = if (_uiState.value.isUnleashed) """

        ## Unleashed mode
        Two kinds of tool are available.

        Carl's own material — search_notes, search_todos, search_journal, get_calendar. Reach
        for these before claiming something is or is not on his list, and before saying what
        his day looks like. They are already filtered: anything in a vault bucket, and any
        private journal entry or draft, simply does not exist as far as these tools are
        concerned. Do not tell him something is missing on the strength of an empty result.

        The open web — search and fetch. Use it when the answer genuinely depends on something
        outside Carl's own material: current facts, documentation, prices, news. Do not search
        for what you already know, and never search for anything about Carl himself. Cite what
        you used, briefly.

        The action markers above still apply.
        """ else ""

        return """
        You are Carl's Brain — Carl's personal AI assistant and second brain.
        You help Carl capture thoughts, manage tasks, and plan his life.
        Keep responses concise and practical. Carl has ADHD so structured,
        actionable answers work best.

        ## Action Markers
        Use these markers at the end of your response to take actions. They are processed silently — never explain or mention them.

        Create a to-do:
        [TODO: title | bucket | URGENT/HIGH/NORMAL/SOMEDAY]

        Save a note to the app:
        [NOTE: title | bucket]
        IMPORTANT: Only use [NOTE:] when Carl explicitly asks you to save something as a note
        (e.g. "save that as a note", "add that to my notes", "note that down"). Do NOT use it
        for general conversation, context, or things discussed — those are saved automatically
        to memory. If you think something is worth saving as a note, ask Carl first rather than
        saving silently.

        Mark a to-do as done (fuzzy title match):
        [DONE: title of the todo]

        Create a calendar event:
        [CALENDAR: title | yyyy-MM-dd'T'HH:mm | yyyy-MM-dd'T'HH:mm | optional location]
        Example: [CALENDAR: Team meeting | 2025-05-20T14:00 | 2025-05-20T15:00 | Dubbo HQ]

        Valid buckets: ${liveBucketNames.value}.
        You may include multiple markers of any type.

        ## Carl's Memory
        $memoryMd
        $meetingsSection
        ${if (healthCtx.isNotBlank()) "\n## Carl's Health Context (today)\n$healthCtx" else ""}
        $unleashedSection
    """.trimIndent()
    }
}
