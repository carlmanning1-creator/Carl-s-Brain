package com.carlmanning.carlsbrain.data.local.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineException
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.health.HealthRepository
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

class VoiceCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_capture_listener_v2"
        const val CONFIRM_CHANNEL_ID = "voice_confirm"
        const val TRIGGER_CHANNEL_ID = "voice_trigger"
        private const val NOTIFICATION_ID = 9002
        private const val TRIGGER_NOTIFICATION_ID = 9003
        private const val TAG = "VoiceCaptureService"
        private const val PPM_FILE = "Hey-Brain_en_android_v4_0_0.ppn"

        const val ACTION_START_WAKE_WORD = "com.carlmanning.carlsbrain.START_WAKE_WORD"
        const val ACTION_STOP_WAKE_WORD = "com.carlmanning.carlsbrain.STOP_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.carlmanning.carlsbrain.RESUME_WAKE_WORD"
        // Sent by VoiceCaptureActivity.onResume to release any in-progress service speech.
        const val ACTION_STOP_LISTENING = "com.carlmanning.carlsbrain.STOP_LISTENING"

        // True while either the service or VoiceCaptureActivity is handling a session.
        @Volatile var isConversationActive = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var wakeWordActive = false
    @Volatile private var isListening = false

    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var porcupine: Porcupine? = null

    // Service-side voice pipeline (speech → Claude → TTS)
    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsOnDone: (() -> Unit)? = null
    private val conversationHistory = mutableListOf<ApiMessage>()

    private val db by lazy { AppDatabase.getInstance(this) }
    private val claude by lazy { CarlsBrainApp.claudeClient }
    private val drive by lazy { DriveRepository(this) }
    private val calendarRepo by lazy { CalendarRepository(this) }

    private val todoRegex = Regex("""\[TODO:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val noteRegex = Regex("""\[NOTE:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val doneRegex = Regex("""\[DONE:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
    private val calendarRegex = Regex("""\[CALENDAR:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)

    // Fetched once per conversation so memory.md is consistent across all turns
    private var sessionMemory: String = ""

    // Resume window: 45 s gives plenty of time after the 8 s silence end tone to say Hey Brain.
    private val conversationResumeWindowMs = 45_000L
    private var lastConversationEndTime: Long = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        // Reset static flag in case service was killed while a conversation was active.
        // Without this, START_STICKY restarts would leave isConversationActive = true
        // permanently, preventing wake word from ever starting.
        isConversationActive = false
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("Brain is ready"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        serviceScope.launch {
            if (CarlsBrainApp.userPreferences.wakeWordEnabled.first()) {
                handler.post { startWakeWordLoop() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_WAKE_WORD -> handler.post { startWakeWordLoop() }
            ACTION_STOP_WAKE_WORD -> handler.post { stopWakeWordLoop() }
            ACTION_RESUME_WAKE_WORD -> {
                // Check the DataStore preference directly rather than wakeWordActive, because
                // ACTION_STOP_WAKE_WORD (used for Chat mic pause) sets wakeWordActive = false,
                // which would permanently block this resume from ever restarting Porcupine.
                handler.postDelayed({
                    if (!isConversationActive && !isListening) {
                        serviceScope.launch {
                            if (CarlsBrainApp.userPreferences.wakeWordEnabled.first()) {
                                handler.post { startWakeWordLoop() }
                            }
                        }
                    }
                }, 1200)
            }
            // VoiceCaptureActivity is taking over the mic — stop any in-progress service speech.
            ACTION_STOP_LISTENING -> handler.post {
                isListening = false
                speechRecognizer?.destroy()
                speechRecognizer = null
            }
        }
        return START_STICKY
    }

    // ── Wake word (Porcupine) ─────────────────────────────────────────────────

    private fun startWakeWordLoop() {
        wakeWordActive = true
        if (isConversationActive) return
        if (isListening) return

        serviceScope.launch {
            val accessKey = CarlsBrainApp.userPreferences.picovoiceAccessKey.first()
            if (accessKey.isBlank()) {
                Log.w(TAG, "Picovoice access key not set — wake word inactive")
                updateNotification("Hey Brain: add Picovoice key in Settings")
                return@launch
            }
            handler.post { launchAudioThread(accessKey) }
        }
    }

    private fun launchAudioThread(accessKey: String) {
        if (isListening) return
        isListening = true

        audioThread = Thread({
            val porcupineInstance: Porcupine
            try {
                porcupineInstance = Porcupine.Builder()
                    .setAccessKey(accessKey)
                    .setKeywordPath(PPM_FILE)
                    .setSensitivity(0.4f)
                    .build(applicationContext)
            } catch (e: PorcupineActivationException) {
                Log.e(TAG, "Porcupine activation failed: ${e.message}")
                updateNotification("Hey Brain: key invalid — check Settings")
                isListening = false
                return@Thread
            } catch (e: PorcupineException) {
                Log.e(TAG, "Porcupine init error: ${e.message}")
                updateNotification("Hey Brain: init failed — ${e.message?.take(60)}")
                isListening = false
                return@Thread
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected Porcupine error: ${e.message}")
                updateNotification("Hey Brain: error — ${e.message?.take(60)}")
                isListening = false
                return@Thread
            }

            porcupine = porcupineInstance

            val frameLength = porcupineInstance.frameLength
            val sampleRate = porcupineInstance.sampleRate
            val minBufSize = AudioRecord.getMinBufferSize(
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2

            val record = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                sampleRate,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBufSize
            )
            if (record.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord failed to initialise — RECORD_AUDIO permission likely missing")
                record.release()
                porcupineInstance.delete()
                porcupine = null
                isListening = false
                return@Thread
            }

            audioRecord = record
            record.startRecording()

            val buffer = ShortArray(frameLength)
            try {
                while (isListening) {
                    val read = record.read(buffer, 0, frameLength)
                    if (read < frameLength) continue
                    val keywordIndex = porcupineInstance.process(buffer)
                    if (keywordIndex >= 0 && !isConversationActive) {
                        handler.post { triggerConversation() }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loop error: ${e.message}")
            } finally {
                record.stop()
                record.release()
                audioRecord = null
                porcupineInstance.delete()
                porcupine = null
                isListening = false
                if (wakeWordActive) {
                    handler.postDelayed({
                        if (wakeWordActive && !isConversationActive && !isListening) {
                            startWakeWordLoop()
                        }
                    }, 1500)
                }
            }
        }, "porcupine-audio-thread")

        audioThread!!.start()
    }

    private fun stopWakeWordLoop() {
        wakeWordActive = false
        // Stop only the Porcupine audio thread — do NOT removeCallbacksAndMessages(null)
        // as that would also kill any in-flight TTS onDone and speak() continuations.
        isListening = false
    }

    // ── Conversation pipeline ─────────────────────────────────────────────────

    private fun triggerConversation() {
        // Porcupine's AudioRecord thread will release the mic in its finally block.
        // SpeechRecognizer can safely acquire it a moment later.
        isListening = false

        val isResume = lastConversationEndTime > 0
            && System.currentTimeMillis() - lastConversationEndTime < conversationResumeWindowMs
            && conversationHistory.isNotEmpty()

        isConversationActive = true
        initTtsIfNeeded()
        wakeScreen()

        if (!isResume) {
            conversationHistory.clear()
            sessionMemory = ""
            serviceScope.launch {
                sessionMemory = drive.getMemoryMd() ?: DriveRepository.INITIAL_MEMORY
            }
        }

        val headsUp = NotificationCompat.Builder(this, TRIGGER_CHANNEL_ID)
            .setContentTitle("Hey Brain")
            .setContentText(if (isResume) "Resuming…" else "Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(4_000)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(TRIGGER_NOTIFICATION_ID, headsUp)

        updateNotification("Hey Brain is listening…")
        if (isResume) {
            speak("Go ahead.") {
                handler.postDelayed({ startServiceSpeechRecognition() }, 300)
            }
        } else {
            speak("How can I help?") {
                handler.postDelayed({ startServiceSpeechRecognition() }, 300)
            }
        }
    }

    private fun startServiceSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Log.w(TAG, "SpeechRecognizer not available on this device")
            endConversation()
            return
        }
        updateNotification("Hey Brain is listening…")
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(serviceSpeechListener)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // 8 s silence before a single timeout; two consecutive = ~16 s total before
                // endConversation() fires, matching the requested 15-30 s resume window.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 8_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 6_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
                // Required in release builds — without the calling package the Google speech
                // service may initialise but refuse to open the audio device.
                putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, packageName)
            })
        }
    }

    private val serviceSpeechListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
        override fun onPartialResults(partialResults: Bundle?) {}

        override fun onError(error: Int) {
            Log.d(TAG, "SpeechRecognizer error: $error")
            when {
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> endConversation(intentional = true)
                // ERROR_SERVER_DISCONNECTED fires on Android 12+ when the audio device hasn't
                // finished switching from TTS speaker output to mic input — retry after a delay.
                error == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                    handler.postDelayed({ startServiceSpeechRecognition() }, 800)
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    // End immediately after one timeout. Keeping the mic open for a second
                    // timeout is dangerous: if the user says "Hey Brain" during the retry,
                    // SpeechRecognizer captures it as plain text and sends it to Claude instead
                    // of Porcupine treating it as a wake word. One clean end + 45 s resume
                    // window is the better UX: conversation ends clearly, user says Hey Brain.
                    endConversation(intentional = false)
                }
                error == SpeechRecognizer.ERROR_NO_MATCH -> startServiceSpeechRecognition()
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_AUDIO -> handler.postDelayed({ startServiceSpeechRecognition() }, 800)
                else -> handler.postDelayed({ startServiceSpeechRecognition() }, 1000)
            }
        }

        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (text.isNullOrBlank()) {
                startServiceSpeechRecognition()
                return
            }
            getSystemService(NotificationManager::class.java).cancel(TRIGGER_NOTIFICATION_ID)
            onUserSpoke(text)
        }
    }

    private fun onUserSpoke(text: String) {
        if (isExitIntent(text)) {
            // Destroy the recognizer immediately so the mic orange indicator clears at once,
            // rather than waiting until endConversation() fires after TTS finishes.
            speechRecognizer?.destroy()
            speechRecognizer = null
            handler.post { speak("Goodbye!") { endConversation(intentional = true) } }
            return
        }

        updateNotification("Brain is thinking…")
        conversationHistory.add(ApiMessage("user", text))

        serviceScope.launch {
            val buckets = db.bucketDao().getAllBuckets().first()
            val bucketNames = buckets.filter { !it.isVault }.joinToString(", ") { it.name }
            val healthCtx = HealthRepository.getCachedContextString()

            val systemPrompt = buildString {
                append(
                    """You are Carl's Brain — Carl's personal AI assistant responding via VOICE.
Keep responses SHORT and conversational. Do NOT use markdown, bullet points, asterisks, or special characters.
Speak in plain natural sentences only.

## Action Markers
Use these silently at the end of your response. Never explain or mention them.

Create a to-do:
[TODO: title | bucket | URGENT/HIGH/NORMAL/SOMEDAY]

Create a note:
[NOTE: title | bucket]

Mark a to-do as done (fuzzy title match):
[DONE: title of the todo]

Create a calendar event:
[CALENDAR: title | yyyy-MM-dd'T'HH:mm | yyyy-MM-dd'T'HH:mm | optional location]

Valid buckets: $bucketNames

## Carl's Memory
$sessionMemory"""
                )
                if (healthCtx.isNotBlank()) {
                    append("\n\n## Carl's Health Context\n$healthCtx")
                }
            }

            val result = claude.chat(messages = conversationHistory.toList(), systemPrompt = systemPrompt)
                .getOrElse { e ->
                    Log.e(TAG, "Claude error: ${e.message}")
                    handler.post {
                        speak("Sorry, I had a problem connecting. Please try again.") {
                            startServiceSpeechRecognition()
                        }
                    }
                    return@launch
                }

            parseAndActOnMarkers(result, text)

            val displayText = calendarRegex.replace(
                todoRegex.replace(
                    noteRegex.replace(
                        doneRegex.replace(result, ""), ""
                    ), ""
                ), ""
            ).trim()

            conversationHistory.add(ApiMessage("assistant", displayText))

            MemoryLearner.learnFrom(
                applicationContext,
                "Voice: \"$text\" → \"${displayText.take(200)}\"",
                "voice"
            )

            handler.post {
                if (displayText.isBlank()) {
                    // Claude responded with only action markers and no spoken text.
                    // Skip TTS to avoid a silent hang where onDone never fires.
                    handler.postDelayed({ startServiceSpeechRecognition() }, 300)
                } else {
                    speak(displayText) {
                        handler.postDelayed({ startServiceSpeechRecognition() }, 300)
                    }
                }
            }
        }
    }

    private suspend fun parseAndActOnMarkers(response: String, userText: String) {
        val buckets = db.bucketDao().getAllBuckets().first()
        val defaultBucket = buckets.find { !it.isVault && it.name == "Other" }
            ?: buckets.firstOrNull { !it.isVault }
            ?: return

        todoRegex.findAll(response).forEach { match ->
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { return@forEach }
            val bucketName = parts.getOrElse(1) { "Other" }
            val priorityStr = parts.getOrElse(2) { "NORMAL" }.uppercase()
            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) } ?: defaultBucket
            val priority = runCatching { Priority.valueOf(priorityStr) }.getOrDefault(Priority.NORMAL)
            val todoId = db.todoDao().insertTodo(
                TodoEntity(title = title, bucketId = bucket.id, priority = priority.rank)
            )
            postSavedNotification("Task added", title, todoId, true)
        }

        noteRegex.findAll(response).forEach { match ->
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { return@forEach }
            val bucketName = parts.getOrElse(1) { "Other" }
            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) } ?: defaultBucket
            val noteId = db.noteDao().insertNote(
                NoteEntity(title = title, content = userText, bucketId = bucket.id)
            )
            postSavedNotification("Note saved", title, noteId, false)
        }

        doneRegex.findAll(response).forEach { match ->
            val titleQuery = match.groupValues[1].trim().ifBlank { return@forEach }
            val todo = db.todoDao().searchTodos(titleQuery).firstOrNull { !it.isDone } ?: return@forEach
            db.todoDao().setTodoDone(todo.id, true)
        }

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
                calendarRepo.createEvent(title, startMs, endMs, location)
            }
        }
    }

    private fun isExitIntent(text: String): Boolean {
        val lower = text.lowercase().trim()
        // Exact-match only for short ambiguous words ("done", "end" alone could be mid-sentence).
        // Removed "done" and "end" — too likely to appear as partial STT results mid-thought.
        // Removed startsWith("thank you") — "thank you, and also add a task" would false-exit.
        // "thanks," check removed — STT never includes punctuation so it was dead code.
        return lower in setOf(
            "stop", "goodbye", "bye", "exit",
            "that's all", "that's it", "thats all", "thats it",
            "all done", "thank you", "thanks"
        ) || lower.startsWith("goodbye") || lower.startsWith("bye ")
    }

    /**
     * @param intentional true when the user explicitly ended the session (goodbye/stop/thank you).
     *   Clears the resume timestamp so the next Hey Brain starts fresh.
     *   false (timeout) preserves the timestamp so Hey Brain within 20 s resumes the conversation.
     */
    private fun endConversation(intentional: Boolean = false) {
        isConversationActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        lastConversationEndTime = if (intentional) 0L else System.currentTimeMillis()
        playEndTone()
        updateNotification("Brain is ready")
        if (wakeWordActive) {
            handler.postDelayed({
                if (wakeWordActive && !isConversationActive && !isListening) startWakeWordLoop()
            }, 1200)
        }
    }

    // ── Text-to-Speech ────────────────────────────────────────────────────────

    private fun initTtsIfNeeded() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        handler.post {
                            val cb = pendingTtsOnDone
                            pendingTtsOnDone = null
                            cb?.invoke()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        handler.post {
                            val cb = pendingTtsOnDone
                            pendingTtsOnDone = null
                            cb?.invoke()
                        }
                    }
                })
                ttsReady = true
            }
        }
    }

    private fun speak(text: String, onDone: () -> Unit) {
        updateNotification(text.take(80))
        if (ttsReady) {
            pendingTtsOnDone = onDone
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brain_${System.currentTimeMillis()}")
        } else {
            // TTS engine not ready yet — skip audio and proceed after a short delay.
            handler.postDelayed({ onDone() }, 800)
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    private fun postSavedNotification(title: String, body: String, itemId: Long, isTodo: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            (itemId + if (isTodo) 10_000 else 20_000).toInt(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(
                    if (isTodo) MainActivity.EXTRA_OPEN_TODO_ID else MainActivity.EXTRA_OPEN_NOTE_ID,
                    itemId
                )
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CONFIRM_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        getSystemService(NotificationManager::class.java)
            .notify((System.currentTimeMillis() % Int.MAX_VALUE).toInt(), notification)
    }

    // ── Screen wake ───────────────────────────────────────────────────────────

    private fun wakeScreen() {
        try {
            @Suppress("DEPRECATION")
            val wl = getSystemService(PowerManager::class.java).newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP,
                "CarlsBrain:WakeWord"
            )
            // Keeps the screen lit for the duration of the conversation (up to 30 s).
            wl.acquire(30_000L)
        } catch (e: Exception) {
            Log.w(TAG, "WakeLock failed: ${e.message}")
        }
    }

    // ── Audio cues ────────────────────────────────────────────────────────────

    private fun playEndTone() {
        try {
            val tg = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
            tg.startTone(ToneGenerator.TONE_PROP_BEEP2, 350)
            handler.postDelayed({ tg.release() }, 500)
        } catch (e: Exception) {
            Log.w(TAG, "ToneGenerator failed: ${e.message}")
        }
    }

    // ── Service lifecycle ─────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(contentText: String): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, VoiceCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Brain is ready")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak", tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
