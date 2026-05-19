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
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
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
    private var questionCount = 0

    private val db by lazy { AppDatabase.getInstance(this) }
    private val claude by lazy { CarlsBrainApp.claudeClient }
    private val drive by lazy { DriveRepository(this) }

    // Fetched once per conversation so memory.md is consistent across all turns
    private var sessionMemory: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
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
                handler.postDelayed({
                    if (wakeWordActive && !isConversationActive) startWakeWordLoop()
                }, 1200)
            }
            // VoiceCaptureActivity is taking over the mic — stop any in-progress service speech.
            // isConversationActive is NOT touched here; VoiceCaptureActivity manages that flag.
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
                    .setSensitivity(0.7f)
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
        isListening = false
        handler.removeCallbacksAndMessages(null)
    }

    // ── Conversation pipeline (runs entirely in the service) ──────────────────

    private fun triggerConversation() {
        // Porcupine's AudioRecord thread will release the mic in its finally block.
        // SpeechRecognizer can safely acquire it a moment later.
        isListening = false
        isConversationActive = true
        conversationHistory.clear()
        questionCount = 0
        sessionMemory = ""
        initTtsIfNeeded()
        wakeScreen()

        // Load memory.md in the background so it's ready before the first user turn
        serviceScope.launch {
            sessionMemory = drive.getMemoryMd() ?: DriveRepository.INITIAL_MEMORY
        }

        // Brief heads-up so the user knows the wake word fired; auto-dismisses in 4 s.
        val headsUp = NotificationCompat.Builder(this, TRIGGER_CHANNEL_ID)
            .setContentTitle("Hey Brain")
            .setContentText("Listening…")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setTimeoutAfter(4_000)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(TRIGGER_NOTIFICATION_ID, headsUp)

        updateNotification("Hey Brain is listening…")
        // Greet the user with TTS, then open the mic once the greeting finishes.
        // If TTS isn't ready yet the speak() fallback fires after 800 ms.
        speak("How can I help?") {
            handler.postDelayed({ startServiceSpeechRecognition() }, 300)
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
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
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
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> endConversation()
                error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> startServiceSpeechRecognition()
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
        updateNotification("Brian is thinking…")
        conversationHistory.add(ApiMessage("user", text))

        serviceScope.launch {
            val buckets = db.bucketDao().getAllBuckets().first()
            val bucketNames = buckets.joinToString("|") { it.name }

            val memorySection = if (sessionMemory.isNotBlank())
                "\n\n## Carl's Memory\n$sessionMemory" else ""

            val systemPrompt = """You are Brain, the AI voice assistant inside Carl's Brain app.
Carl is an ADHD support worker and NSW SES Deputy in Dubbo, Australia.

Classify his voice capture into a todo or note. You may ask short follow-up questions if critical details are missing (e.g. time for a reminder, which bucket, priority). Ask at most ${4 - questionCount} more question(s) total, then save.

Respond with JSON only — no markdown, no extra text.

To ask a question:
{"action":"ask","question":"Short question here?"}

To save a todo:
{"action":"save","type":"todo","title":"...","bucket":"$bucketNames","priority":"URGENT|HIGH|NORMAL|SOMEDAY"}

To save a note:
{"action":"save","type":"note","title":"...","bucket":"$bucketNames","content":"..."}$memorySection"""

            val raw = claude.chat(messages = conversationHistory, systemPrompt = systemPrompt).getOrNull()
            val response = raw?.let { parseResponse(it) }

            if (response == null) {
                val defaultBucketId = buckets.find { it.name == "Personal" }?.id
                    ?: buckets.firstOrNull()?.id
                if (defaultBucketId != null) {
                    val noteId = db.noteDao().insertNote(
                        NoteEntity(title = text.take(60), content = text, bucketId = defaultBucketId)
                    )
                    postSavedNotification("Note saved", text.take(60), noteId, false)
                }
                handler.post { speak("Saved as a note.") { endConversation() } }
                return@launch
            }

            when (response.action) {
                "ask" -> {
                    val question = response.question ?: "Can you give me more details?"
                    conversationHistory.add(ApiMessage("assistant", question))
                    questionCount++
                    handler.post { speak(question) { startServiceSpeechRecognition() } }
                }
                "save" -> {
                    val defaultBucketId = buckets.find { it.name == "Personal" }?.id
                        ?: buckets.firstOrNull()?.id
                        ?: run { handler.post { endConversation() }; return@launch }
                    val bucketId =
                        buckets.find { it.name.equals(response.bucket, ignoreCase = true) }?.id
                            ?: defaultBucketId

                    if (response.type == "note") {
                        val title = response.title.ifBlank { text.take(60) }
                        val noteId = db.noteDao().insertNote(
                            NoteEntity(
                                title = title,
                                content = response.content ?: text,
                                bucketId = bucketId
                            )
                        )
                        postSavedNotification("Note saved", title, noteId, false)
                        MemoryLearner.learnFrom(
                            applicationContext,
                            "Voice note saved: \"$title\" — bucket: ${response.bucket}, content: ${(response.content ?: text).take(120)}",
                            "voice"
                        )
                        handler.post { speak("Done. I've saved that note for you.") { endConversation() } }
                    } else {
                        val title = response.title.ifBlank { text }
                        val priority = Priority.entries
                            .find { it.name == response.priority.uppercase() } ?: Priority.NORMAL
                        val todoId = db.todoDao().insertTodo(
                            TodoEntity(title = title, bucketId = bucketId, priority = priority.name)
                        )
                        postSavedNotification("Task added", title, todoId, true)
                        MemoryLearner.learnFrom(
                            applicationContext,
                            "Voice todo created: \"$title\" — bucket: ${response.bucket}, priority: ${priority.name}",
                            "voice"
                        )
                        handler.post { speak("Got it. Task created: $title.") { endConversation() } }
                    }
                }
                else -> handler.post { endConversation() }
            }
        }
    }

    private fun endConversation() {
        isConversationActive = false
        speechRecognizer?.destroy()
        speechRecognizer = null
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
            // The OS reclaims it automatically when the timeout expires.
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

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseResponse(raw: String): BrainResponse? {
        val stripped = raw.removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { appJson.decodeFromString<BrainResponse>(stripped) }.getOrNull()
    }

    @Serializable
    private data class BrainResponse(
        val action: String = "save",
        val question: String? = null,
        val type: String = "todo",
        val title: String = "",
        val bucket: String = "",
        val priority: String = "NORMAL",
        val content: String? = null
    )

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
