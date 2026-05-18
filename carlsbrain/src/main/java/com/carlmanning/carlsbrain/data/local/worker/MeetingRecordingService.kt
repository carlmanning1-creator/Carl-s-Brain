package com.carlmanning.carlsbrain.data.local.worker

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.MediaRecorder
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

sealed class MeetingServiceState {
    object Idle : MeetingServiceState()
    data class Recording(
        val meetingId: Long,
        val durationMs: Long,
        val transcript: String
    ) : MeetingServiceState()
    data class Stopped(
        val meetingId: Long,
        val durationMs: Long,
        val localAudioPath: String,
        val transcript: String
    ) : MeetingServiceState()
}

class MeetingRecordingService : Service() {

    private var mediaRecorder: MediaRecorder? = null
    private var speechRecognizer: SpeechRecognizer? = null
    private val transcript = StringBuilder()
    private var partialSuffix = ""
    private var meetingId = -1L
    private var audioFile: File? = null
    private var startTimeMs = 0L
    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var durationJob: Job? = null
    private var isRecording = false

    companion object {
        internal val _state = MutableStateFlow<MeetingServiceState>(MeetingServiceState.Idle)
        val state: StateFlow<MeetingServiceState> = _state.asStateFlow()

        const val ACTION_START = "com.carlmanning.carlsbrain.ACTION_MEETING_START"
        const val ACTION_STOP = "com.carlmanning.carlsbrain.ACTION_MEETING_STOP"
        const val EXTRA_MEETING_ID = "meeting_id"

        fun resetState() { _state.value = MeetingServiceState.Idle }

        const val MAX_DURATION_MS = 60 * 60 * 1000L
        private const val NOTIFICATION_ID = 9001
        private const val CHANNEL_ID = "meeting_recording"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                meetingId = intent.getLongExtra(EXTRA_MEETING_ID, -1L)
                if (meetingId == -1L) { stopSelf(); return START_NOT_STICKY }
                startRecording()
            }
            ACTION_STOP -> stopRecording()
        }
        return START_NOT_STICKY
    }

    private fun startRecording() {
        isRecording = true
        transcript.clear()
        partialSuffix = ""

        val dir = File(cacheDir, "meetings").also { it.mkdirs() }
        audioFile = File(dir, "meeting_$meetingId.m4a")

        @Suppress("DEPRECATION")
        mediaRecorder = (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
            MediaRecorder(this) else MediaRecorder()
        ).apply {
            setAudioSource(MediaRecorder.AudioSource.MIC)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            setAudioChannels(1)
            setAudioSamplingRate(16000)
            setAudioEncodingBitRate(32000)
            setOutputFile(audioFile!!.absolutePath)
            runCatching { prepare() }
            runCatching { start() }
        }

        startTimeMs = System.currentTimeMillis()
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, buildNotification("0:00"),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
        startDurationUpdates()
        startSpeechRecognition()

        // Auto-stop at 1 hour
        handler.postDelayed({ if (isRecording) stopRecording() }, MAX_DURATION_MS)

        _state.value = MeetingServiceState.Recording(meetingId, 0L, "")
    }

    private fun startDurationUpdates() {
        durationJob = serviceScope.launch {
            while (isRecording) {
                delay(1000)
                val elapsed = System.currentTimeMillis() - startTimeMs
                updateNotification(formatDuration(elapsed))
                _state.value = MeetingServiceState.Recording(
                    meetingId, elapsed,
                    transcript.toString() + if (partialSuffix.isNotBlank()) " $partialSuffix" else ""
                )
            }
        }
    }

    private fun startSpeechRecognition() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) return
        handler.post {
            if (!isRecording) return@post
            speechRecognizer?.destroy()
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(object : RecognitionListener {
                    override fun onReadyForSpeech(p: Bundle?) {}
                    override fun onBeginningOfSpeech() {}
                    override fun onRmsChanged(v: Float) {}
                    override fun onBufferReceived(b: ByteArray?) {}
                    override fun onEndOfSpeech() { partialSuffix = "" }
                    override fun onPartialResults(partial: Bundle?) {
                        partialSuffix = partial
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull() ?: ""
                    }
                    override fun onResults(results: Bundle?) {
                        partialSuffix = ""
                        val text = results
                            ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                            ?.firstOrNull()
                        if (!text.isNullOrBlank()) {
                            if (transcript.isNotEmpty()) transcript.append(" ")
                            transcript.append(text)
                        }
                        if (isRecording) handler.postDelayed({ startSpeechRecognition() }, 300)
                    }
                    override fun onError(errorCode: Int) {
                        partialSuffix = ""
                        if (isRecording) handler.postDelayed({ startSpeechRecognition() }, 1000)
                    }
                    override fun onEvent(t: Int, p: Bundle?) {}
                })
                startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 10000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 8000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 5000L)
                })
            }
        }
    }

    private fun stopRecording() {
        isRecording = false
        handler.removeCallbacksAndMessages(null)
        durationJob?.cancel()

        handler.post {
            speechRecognizer?.destroy()
            speechRecognizer = null
        }

        val finalPath = runCatching {
            mediaRecorder?.stop()
            mediaRecorder?.release()
            audioFile?.absolutePath ?: ""
        }.getOrElse {
            runCatching { mediaRecorder?.release() }
            ""
        }
        mediaRecorder = null

        val durationMs = System.currentTimeMillis() - startTimeMs
        val finalTranscript = transcript.toString().trim()

        _state.value = MeetingServiceState.Stopped(
            meetingId = meetingId,
            durationMs = durationMs,
            localAudioPath = finalPath,
            transcript = finalTranscript
        )

        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Meeting Recording", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Active meeting recording" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun buildNotification(duration: String): Notification {
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, MeetingRecordingService::class.java).apply { action = ACTION_STOP },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording meeting")
            .setContentText(duration)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(0, "Stop", stopIntent)
            .setOngoing(true)
            .build()
    }

    private fun updateNotification(duration: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(duration))
    }

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        isRecording = false
        serviceScope.cancel()
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        mediaRecorder?.release()
    }
}
