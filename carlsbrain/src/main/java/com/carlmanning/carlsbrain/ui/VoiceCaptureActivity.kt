package com.carlmanning.carlsbrain.ui

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.lifecycle.lifecycleScope
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.VoiceCaptureService
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import java.util.Locale

sealed class OverlayState {
    object Listening : OverlayState()
    object Processing : OverlayState()
    data class Speaking(val text: String) : OverlayState()
}

class VoiceCaptureActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private val db by lazy { AppDatabase.getInstance(this) }
    private val claude by lazy { CarlsBrainApp.claudeClient }

    private var speechRecognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingTtsOnDone: (() -> Unit)? = null

    // Conversation history passed to Claude on each turn
    private val conversationHistory = mutableListOf<ApiMessage>()
    private var questionCount = 0

    private var overlayState: OverlayState by mutableStateOf(OverlayState.Listening)
    private var partialTranscript by mutableStateOf("")

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startListening() else finish() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        // UtteranceProgressListener fires on a TTS thread — post to main
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

        setContent {
            CarlsBrainTheme {
                VoiceCaptureOverlay(
                    state = overlayState,
                    partial = partialTranscript,
                    onDismiss = { finish() }
                )
            }
        }

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startListening()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    override fun onResume() {
        super.onResume()
        VoiceCaptureService.isConversationActive = true
        // Ensure Porcupine's AudioRecord is stopped regardless of how this activity was
        // opened (triggerConversation already sets isListening=false, but a direct
        // notification tap bypasses that path). Safe to call redundantly.
        startService(Intent(this, VoiceCaptureService::class.java).apply {
            action = VoiceCaptureService.ACTION_STOP_LISTENING
        })
    }

    override fun onPause() {
        super.onPause()
        VoiceCaptureService.isConversationActive = false
        // The service itself is still running (only its audio thread stopped when the wake
        // word fired). startService() is sufficient to deliver ACTION_RESUME_WAKE_WORD to
        // the already-running service without risking background-start restrictions.
        startService(Intent(this, VoiceCaptureService::class.java).apply {
            action = VoiceCaptureService.ACTION_RESUME_WAKE_WORD
        })
    }

    // ── Listening ─────────────────────────────────────────────────────────────

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            overlayState = OverlayState.Speaking("Speech recognition is not available on this device.")
            handler.postDelayed({ finish() }, 3000)
            return
        }
        overlayState = OverlayState.Listening
        partialTranscript = ""

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onEvent(t: Int, p: Bundle?) {}

                override fun onPartialResults(partial: Bundle?) {
                    partialTranscript = partial
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull() ?: ""
                }

                override fun onError(error: Int) {
                    when (error) {
                        SpeechRecognizer.ERROR_NO_MATCH,
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT,
                        SpeechRecognizer.ERROR_AUDIO,
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                        SpeechRecognizer.ERROR_CLIENT -> startListening()
                        else -> finish()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) { startListening(); return }
                    onUserSpoke(text)
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 20_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 15_000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1_000L)
            })
        }
    }

    // ── Conversation ──────────────────────────────────────────────────────────

    private fun onUserSpoke(text: String) {
        partialTranscript = ""
        overlayState = OverlayState.Processing
        conversationHistory.add(ApiMessage("user", text))

        lifecycleScope.launch {
            val buckets = db.bucketDao().getAllBuckets().first()
            val bucketNames = buckets.joinToString("|") { it.name }

            val systemPrompt = """You are Brain, the AI voice assistant inside Carl's Brain app.
Carl is an ADHD support worker and NSW SES Deputy in Dubbo, Australia.

Classify his voice capture into a todo or note. You may ask short follow-up questions if critical details are missing (e.g. time for a reminder, which bucket, priority). Ask at most ${4 - questionCount} more question(s) total, then save.

Respond with JSON only — no markdown, no extra text.

To ask a question:
{"action":"ask","question":"Short question here?"}

To save a todo:
{"action":"save","type":"todo","title":"...","bucket":"$bucketNames","priority":"URGENT|HIGH|NORMAL|SOMEDAY"}

To save a note:
{"action":"save","type":"note","title":"...","bucket":"$bucketNames","content":"..."}"""

            val raw = claude.chat(
                messages = conversationHistory,
                systemPrompt = systemPrompt
            ).getOrNull()

            val response = raw?.let { parseResponse(it) }

            if (response == null) {
                // Claude unreachable — save raw text as note immediately
                val defaultBucketId = buckets.find { it.name == "Personal" }?.id
                    ?: buckets.firstOrNull()?.id ?: run { finish(); return@launch }
                val noteId = db.noteDao().insertNote(
                    NoteEntity(title = text.take(60), content = text, bucketId = defaultBucketId)
                )
                postNotification("Note saved", text.take(60), noteId, false)
                speak("Saved as a note.") { finish() }
                return@launch
            }

            when (response.action) {
                "ask" -> {
                    val question = response.question ?: "Can you give me more details?"
                    conversationHistory.add(ApiMessage("assistant", question))
                    questionCount++
                    speak(question) { startListening() }
                }
                "save" -> {
                    val defaultBucketId = buckets.find { it.name == "Personal" }?.id
                        ?: buckets.firstOrNull()?.id ?: run { finish(); return@launch }
                    val bucketId = buckets.find { it.name.equals(response.bucket, ignoreCase = true) }?.id
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
                        postNotification("Note saved", title, noteId, false)
                        MemoryLearner.learnFrom(
                            applicationContext,
                            "Voice note saved: \"$title\" — bucket: ${response.bucket}, content: ${(response.content ?: text).take(120)}",
                            "voice"
                        )
                        speak("Done — I've saved that note for you.") { finish() }
                    } else {
                        val title = response.title.ifBlank { text }
                        val priority = Priority.entries
                            .find { it.name == response.priority.uppercase() } ?: Priority.NORMAL
                        val todoId = db.todoDao().insertTodo(
                            TodoEntity(title = title, bucketId = bucketId, priority = priority.name)
                        )
                        postNotification("Task added", title, todoId, true)
                        MemoryLearner.learnFrom(
                            applicationContext,
                            "Voice todo created: \"$title\" — bucket: ${response.bucket}, priority: ${priority.name}",
                            "voice"
                        )
                        speak("Done — task created: $title.") { finish() }
                    }
                }
                else -> finish()
            }
        }
    }

    // ── Text-to-Speech ────────────────────────────────────────────────────────

    private fun speak(text: String, onDone: () -> Unit) {
        overlayState = OverlayState.Speaking(text)
        speechRecognizer?.destroy()
        speechRecognizer = null

        if (ttsReady) {
            pendingTtsOnDone = onDone
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brain_${System.currentTimeMillis()}")
        } else {
            // TTS not ready yet — skip audio and invoke callback directly
            handler.postDelayed({ onDone() }, 800)
        }
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun postNotification(title: String, body: String, itemId: Long, isTodo: Boolean) {
        val openIntent = PendingIntent.getActivity(
            this,
            (itemId + if (isTodo) 10000 else 20000).toInt(),
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra(if (isTodo) EXTRA_OPEN_TODO_ID else EXTRA_OPEN_NOTE_ID, itemId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, VoiceCaptureService.CONFIRM_CHANNEL_ID)
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

    // ── Parsing ───────────────────────────────────────────────────────────────

    private fun parseResponse(raw: String): BrainResponse? {
        val stripped = raw
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { appJson.decodeFromString<BrainResponse>(stripped) }.getOrNull()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        tts?.stop()
        tts?.shutdown()
    }

    companion object {
        const val EXTRA_OPEN_TODO_ID = "open_todo_id"
        const val EXTRA_OPEN_NOTE_ID = "open_note_id"
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
}

// ── Overlay UI ────────────────────────────────────────────────────────────────

@Composable
fun VoiceCaptureOverlay(state: OverlayState, partial: String, onDismiss: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxSize()
            .clickable(
                indication = null,
                interactionSource = remember { MutableInteractionSource() },
                onClick = onDismiss
            ),
        color = Color.Black.copy(alpha = 0.55f)
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.BottomCenter
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 32.dp)
                    .clickable(enabled = false) {},
                shape = RoundedCornerShape(24.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    when (state) {
                        is OverlayState.Listening -> {
                            Icon(
                                imageVector = Icons.Filled.Mic,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.primary
                            )
                            Text("Listening…", style = MaterialTheme.typography.headlineSmall)
                            if (partial.isNotBlank()) {
                                Text(
                                    text = partial,
                                    style = MaterialTheme.typography.bodyLarge,
                                    textAlign = TextAlign.Center
                                )
                            } else {
                                Text(
                                    text = "Speak a task or note",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    textAlign = TextAlign.Center
                                )
                            }
                        }
                        is OverlayState.Processing -> {
                            CircularProgressIndicator(modifier = Modifier.size(48.dp))
                            Text("Thinking…", style = MaterialTheme.typography.headlineSmall)
                        }
                        is OverlayState.Speaking -> {
                            Icon(
                                imageVector = Icons.Filled.RecordVoiceOver,
                                contentDescription = null,
                                modifier = Modifier.size(56.dp),
                                tint = MaterialTheme.colorScheme.secondary
                            )
                            Text("Brain", style = MaterialTheme.typography.headlineSmall)
                            Text(
                                text = state.text,
                                style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
                                textAlign = TextAlign.Center
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                }
            }
        }
    }
}
