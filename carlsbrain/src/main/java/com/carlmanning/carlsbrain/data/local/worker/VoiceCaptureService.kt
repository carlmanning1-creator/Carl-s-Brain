package com.carlmanning.carlsbrain.data.local.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceCaptureService : Service() {

    companion object {
        const val CHANNEL_ID = "voice_capture_bg"
        const val CONFIRM_CHANNEL_ID = "voice_confirm"
        private const val NOTIFICATION_ID = 9002

        const val ACTION_START_WAKE_WORD = "com.carlmanning.carlsbrain.START_WAKE_WORD"
        const val ACTION_STOP_WAKE_WORD = "com.carlmanning.carlsbrain.STOP_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.carlmanning.carlsbrain.RESUME_WAKE_WORD"

        // VoiceCaptureActivity sets this true while a conversation is open so the
        // wake word loop doesn't try to start a second SpeechRecognizer simultaneously.
        @Volatile var isConversationActive = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var speechRecognizer: SpeechRecognizer? = null
    private var wakeWordActive = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
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
                // Activity finished — resume listening after a brief pause so the
                // Activity's SpeechRecognizer is fully torn down first.
                handler.postDelayed({
                    if (wakeWordActive && !isConversationActive) startWakeWordLoop()
                }, 1200)
            }
        }
        return START_STICKY
    }

    private fun startWakeWordLoop() {
        wakeWordActive = true
        if (isConversationActive) return
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(p: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(v: Float) {}
                override fun onBufferReceived(b: ByteArray?) {}
                override fun onEndOfSpeech() {}
                override fun onPartialResults(p: Bundle?) {}
                override fun onEvent(t: Int, p: Bundle?) {}

                override fun onResults(results: Bundle?) {
                    if (isConversationActive || !wakeWordActive) return
                    val candidates = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?: emptyList<String>()
                    if (candidates.any { isWakeWord(it) }) {
                        triggerConversation()
                    } else {
                        handler.postDelayed({ startWakeWordLoop() }, 300)
                    }
                }

                override fun onError(error: Int) {
                    if (wakeWordActive && !isConversationActive) {
                        handler.postDelayed({ startWakeWordLoop() }, 1000)
                    }
                }
            })
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000L)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
            })
        }
    }

    private fun stopWakeWordLoop() {
        wakeWordActive = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
    }

    private fun isWakeWord(text: String): Boolean {
        val lower = text.lowercase()
        // Handle common speech recognition misheard variants
        return listOf(
            "hey brain", "hey, brain", "hey brains", "hey brane",
            "hey brian", "ok brain", "okay brain", "a brain", "hey brain,"
        ).any { lower.contains(it) }
    }

    private fun triggerConversation() {
        startActivity(Intent(this, VoiceCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(): Notification {
        val tapIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, VoiceCaptureActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Brain is ready")
            .setContentText("Say \"Hey Brain\" or tap to capture by voice")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(tapIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak", tapIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }
}
