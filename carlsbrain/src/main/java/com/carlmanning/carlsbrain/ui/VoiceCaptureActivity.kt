package com.carlmanning.carlsbrain.ui

import android.Manifest
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
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
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.ui.theme.CarlsBrainTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

class VoiceCaptureActivity : ComponentActivity() {

    private var speechRecognizer: SpeechRecognizer? = null
    private val db by lazy { AppDatabase.getInstance(this) }
    private val claude by lazy { CarlsBrainApp.claudeClient }

    private var overlayState by mutableStateOf(OverlayState.LISTENING)

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) startListening() else finish()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        @Suppress("DEPRECATION")
        window.addFlags(
            WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                    WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
        )

        setContent {
            CarlsBrainTheme {
                VoiceCaptureOverlay(
                    state = overlayState,
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

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            finish()
            return
        }
        overlayState = OverlayState.LISTENING
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
                override fun onError(error: Int) { finish() }
                override fun onResults(results: Bundle?) {
                    val text = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                    if (text.isNullOrBlank()) { finish(); return }
                    processCapture(text)
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
            })
        }
    }

    private fun processCapture(text: String) {
        overlayState = OverlayState.PROCESSING
        lifecycleScope.launch {
            val buckets = db.bucketDao().getAllBuckets().first()
            val bucketNames = buckets.joinToString("|") { it.name }

            val prompt = """Classify this voice capture into a task or note, then return JSON only (no markdown fences).

Available buckets: $bucketNames

For a task/todo/reminder use:
{"type":"todo","title":"...","bucket":"...","priority":"URGENT|HIGH|NORMAL|SOMEDAY"}

For information, thoughts, or notes use:
{"type":"note","title":"...","bucket":"...","content":"..."}

Voice capture: "$text""""

            val raw = claude.chat(
                messages = listOf(ApiMessage("user", prompt)),
                systemPrompt = "You classify voice captures into todos or notes. Return only valid JSON, no markdown."
            ).getOrNull()

            val parsed = raw?.let { extractJson(it) }
            val defaultBucketId = buckets.find { it.name == "Personal" }?.id
                ?: buckets.firstOrNull()?.id
                ?: run { finish(); return@launch }

            if (parsed != null) {
                val bucketId = buckets.find { it.name.equals(parsed.bucket, ignoreCase = true) }?.id
                    ?: defaultBucketId
                if (parsed.type == "note") {
                    val noteId = db.noteDao().insertNote(
                        NoteEntity(
                            title = parsed.title.ifBlank { text.take(60) },
                            content = parsed.content ?: text,
                            bucketId = bucketId
                        )
                    )
                    postConfirmation("Note saved", parsed.title.ifBlank { text.take(60) }, noteId, false)
                } else {
                    val priority = Priority.entries.find { it.name == parsed.priority.uppercase() }
                        ?: Priority.NORMAL
                    val todoId = db.todoDao().insertTodo(
                        TodoEntity(
                            title = parsed.title.ifBlank { text },
                            bucketId = bucketId,
                            priority = priority.name
                        )
                    )
                    postConfirmation("Task added", parsed.title.ifBlank { text }, todoId, true)
                }
            } else {
                // Claude unavailable — fall back to saving raw text as a note
                val noteId = db.noteDao().insertNote(
                    NoteEntity(
                        title = text.take(60),
                        content = text,
                        bucketId = defaultBucketId
                    )
                )
                postConfirmation("Note saved", text.take(60), noteId, false)
            }
            finish()
        }
    }

    private fun postConfirmation(title: String, body: String, itemId: Long, isTodo: Boolean) {
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

    private fun extractJson(raw: String): VoiceCaptureParsed? {
        val stripped = raw
            .removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        return runCatching { appJson.decodeFromString<VoiceCaptureParsed>(stripped) }.getOrNull()
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
    }

    companion object {
        const val EXTRA_OPEN_TODO_ID = "open_todo_id"
        const val EXTRA_OPEN_NOTE_ID = "open_note_id"
    }

    @Serializable
    private data class VoiceCaptureParsed(
        val type: String = "todo",
        val title: String = "",
        val bucket: String = "",
        val priority: String = "NORMAL",
        val content: String? = null
    )
}

enum class OverlayState { LISTENING, PROCESSING }

@Composable
fun VoiceCaptureOverlay(state: OverlayState, onDismiss: () -> Unit) {
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
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    if (state == OverlayState.LISTENING) {
                        Icon(
                            imageVector = Icons.Filled.Mic,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Text(
                            text = "Listening…",
                            style = MaterialTheme.typography.headlineSmall
                        )
                        Text(
                            text = "Speak a task or note — Brain will save it automatically",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        CircularProgressIndicator(modifier = Modifier.size(48.dp))
                        Text(
                            text = "Saving…",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Text("Cancel")
                    }
                }
            }
        }
    }
}
