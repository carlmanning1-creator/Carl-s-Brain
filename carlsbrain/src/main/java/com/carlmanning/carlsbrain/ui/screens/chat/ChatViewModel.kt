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
import com.carlmanning.carlsbrain.domain.model.Priority
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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
    val isSpeakingEnabled: Boolean = false
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
        _uiState.update { state ->
            state.copy(
                messages = state.messages + ChatMessage(content = text, isFromUser = true),
                inputText = "",
                isLoading = true
            )
        }

        viewModelScope.launch {
            claude.chat(
                messages = apiHistory.toList(),
                systemPrompt = buildSystemPrompt()
            ).fold(
                onSuccess = { reply ->
                    val createdTodoTitles = parseAndCreateTodos(reply)
                    val createdNoteTitles = parseAndCreateNotes(reply, userMessage = text)
                    val completedTodoTitles = parseAndCompleteTodos(reply)
                    parseAndCreateCalendarEvents(reply)
                    val displayReply = calendarRegex.replace(todoRegex.replace(noteRegex.replace(doneRegex.replace(reply, ""), ""), ""), "").trim()

                    apiHistory.add(ApiMessage(role = "assistant", content = displayReply))
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

        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
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

        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
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
            db.todoDao().setTodoDone(todo.id, true)
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
        viewModelScope.launch {
            val prompt = """Review this conversation exchange. Determine if it revealed new, genuinely important facts about Carl that should be permanently remembered.

Look for ALL of the following:
- Facts about Carl's life, people, routines, and recurring commitments
- Explicit preferences about how the AI generates content (e.g. "make todos shorter", "always add a reminder", "use military time", "don't use markdown in notes", "keep responses brief")
- Recurring patterns and habits
- Important decisions or context

User said: "$userMsg"
Assistant replied: "${assistantReply.take(500)}"

Current memory (tail): ...${memoryMd.takeLast(300)}

If there is something new and important to add, write it as 1-2 concise bullet(s) in format: - [YYYY-MM-DD] Fact
Use today's date: ${SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())}
If nothing new was revealed, respond with exactly: NONE"""

            claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You maintain Carl's memory file. Be selective — only capture truly important new facts. Pay special attention to explicit preferences Carl expresses about how the AI should behave or format its responses. Avoid repeating what is already in memory. Return bullet lines or NONE.",
                model = ClaudeClient.HAIKU
            ).onSuccess { response ->
                val trimmed = response.trim()
                if (trimmed != "NONE" && trimmed.isNotBlank() && !trimmed.startsWith("Error")) {
                    val toAppend = if (trimmed.startsWith("- [")) {
                        trimmed
                    } else {
                        val date = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                        "- [$date] $trimmed"
                    }
                    memoryMd += "\n$toAppend"
                    drive.updateMemoryMd(memoryMd)
                    MemoryLearner.invalidateCache()
                }
            }
        }
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

            // Release the mic from Porcupine before SpeechRecognizer opens it.
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
            speechRecognizer!!.startListening(intent)
        }
    }

    fun stopListening() {
        speechRecognizer?.stopListening()
    }

    fun consumeVoiceError() {
        _uiState.update { it.copy(voiceError = null) }
    }

    // ── TTS ───────────────────────────────────────────────────────────────────

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
        // indefinitely. resumeWakeWord() is safe to call unconditionally: if Porcupine is
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

    private fun buildSystemPrompt(): String {
        val meetingsSection = if (recentMeetingsSummary.isNotBlank()) """

        ## Recent Meetings (last 5)
        $recentMeetingsSummary
        """ else ""

        val healthCtx = HealthRepository.getCachedContextString()

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

        Valid buckets: SES, Family, Work, Personal, Other (or any bucket Carl mentions).
        You may include multiple markers of any type.

        ## Carl's Memory
        $memoryMd
        $meetingsSection
        ${if (healthCtx.isNotBlank()) "\n## Carl's Health Context (today)\n$healthCtx" else ""}
    """.trimIndent()
    }
}
