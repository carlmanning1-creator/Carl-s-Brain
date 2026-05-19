package com.carlmanning.carlsbrain.data.local.worker

import android.app.Notification
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import ai.picovoice.porcupine.Porcupine
import ai.picovoice.porcupine.PorcupineActivationException
import ai.picovoice.porcupine.PorcupineException
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.ui.VoiceCaptureActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class VoiceCaptureService : Service() {

    companion object {
        // v2 channel ID forces a fresh channel with IMPORTANCE_LOW (can't downgrade MIN→LOW on existing channel)
        const val CHANNEL_ID = "voice_capture_listener_v2"
        const val CONFIRM_CHANNEL_ID = "voice_confirm"
        private const val NOTIFICATION_ID = 9002
        private const val TAG = "VoiceCaptureService"
        private const val PPM_FILE = "Hey-Brain_en_android_v4_0_0.ppn"

        const val ACTION_START_WAKE_WORD = "com.carlmanning.carlsbrain.START_WAKE_WORD"
        const val ACTION_STOP_WAKE_WORD = "com.carlmanning.carlsbrain.STOP_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.carlmanning.carlsbrain.RESUME_WAKE_WORD"

        // VoiceCaptureActivity sets this true while a conversation is open so the
        // wake word loop doesn't try to start a second session simultaneously.
        @Volatile var isConversationActive = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var wakeWordActive = false
    @Volatile private var isListening = false

    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var porcupine: Porcupine? = null

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
                // Activity finished — resume listening after a brief pause so the
                // Activity's audio resources are fully torn down first.
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
        if (isListening) return // already running

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
            }
        }, "porcupine-audio-thread")

        audioThread!!.start()
    }

    private fun stopWakeWordLoop() {
        wakeWordActive = false
        isListening = false
        // audioRecord and porcupine are released by the audio thread's finally block
        handler.removeCallbacksAndMessages(null)
    }

    private fun triggerConversation() {
        // Release the mic now so SpeechRecognizer can open it when the activity starts.
        // ACTION_RESUME_WAKE_WORD (sent from VoiceCaptureActivity.onPause) will restart
        // the Porcupine loop after the activity closes.
        isListening = false
        startActivity(Intent(this, VoiceCaptureActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        })
    }

    override fun onDestroy() {
        super.onDestroy()
        isListening = false
        handler.removeCallbacksAndMessages(null)
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
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
