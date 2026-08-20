package com.carlmanning.carlsbrain.data.local.worker

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.MainActivity
import com.carlmanning.carlsbrain.data.audio.AmbientBuffer
import com.carlmanning.carlsbrain.data.audio.PcmAacEncoder
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File

/** What the ambient pipeline is doing, for the Meetings screen, the tile and the widget. */
sealed class AmbientState {
    /** Not capturing anything. */
    object Off : AmbientState()

    /** Rolling buffer running. [bufferedMs] is how much audio could be recovered right now. */
    data class Buffering(val bufferedMs: Long) : AmbientState()

    /** A meeting is being recorded. [prependedMs] is how much of it came from the buffer. */
    data class Recording(
        val meetingId: Long,
        val elapsedMs: Long,
        val prependedMs: Long
    ) : AmbientState()
}

/**
 * The rolling ambient buffer, and the meeting recording that grows out of it.
 *
 * The point of the whole feature is that Carl realises *afterwards* that the last few minutes
 * were worth keeping. So the buffer runs continuously (when he has switched it on), and
 * "start recording" splices what has already been heard onto the front of a normal meeting.
 * There is no separate kind of object: the result is a [MeetingEntity], processed by exactly
 * the same Fireflies → Whisper → Claude path as a meeting recorded the ordinary way.
 *
 * ## Microphone ownership
 * Android will not give one app two usable concurrent AudioRecord clients, so the wake word
 * and the buffer cannot each hold the microphone:
 *  - **Wake word ON** — [VoiceCaptureService]'s keyword-spotter loop already reads 16 kHz mono
 *    frames, and hands each one to [AmbientBuffer] as it goes. This service opens nothing.
 *    While the wake word is parked (quiet hours, or an active conversation) the buffer simply
 *    stops filling; it holds the last N minutes *of capture*, not of wall-clock.
 *  - **Wake word OFF** — this service runs its own capture loop.
 *
 * On promotion the wake word is asked to release the microphone, and is resumed when the
 * meeting ends. About a second of audio is lost at the handover, in the middle of the join
 * between buffered and live audio.
 *
 * ## No live transcript
 * Unlike [MeetingRecordingService], nothing here runs SpeechRecognizer alongside the capture.
 * That pairing is safe with MediaRecorder because the two are separate system clients, but
 * starting SpeechRecognizer while *this* service holds AudioRecord risks the recogniser taking
 * the microphone and the encoder writing silence — which would lose the audio, the one thing
 * that cannot be reconstructed later. The transcript comes from Fireflies (or Whisper), as it
 * does for every meeting where those keys are set.
 */
class AmbientBufferService : Service() {

    companion object {
        private const val TAG = "AmbientBufferService"

        const val ACTION_START_BUFFER = "com.carlmanning.carlsbrain.AMBIENT_START"
        const val ACTION_STOP_BUFFER = "com.carlmanning.carlsbrain.AMBIENT_STOP"

        /**
         * Turns the feature off for good, not just this run.
         *
         * Distinct from [ACTION_STOP_BUFFER], which stands the service down while leaving the
         * setting alone (used when the setting has already been changed elsewhere). The
         * notification's "Turn off" needs this one: with ACTION_STOP_BUFFER it would stop a
         * recording, see the preference still on, and immediately start buffering again.
         */
        const val ACTION_DISABLE = "com.carlmanning.carlsbrain.AMBIENT_DISABLE"

        /** Turn what has been buffered into a meeting and keep recording. */
        const val ACTION_PROMOTE = "com.carlmanning.carlsbrain.AMBIENT_PROMOTE"
        const val ACTION_STOP_MEETING = "com.carlmanning.carlsbrain.AMBIENT_STOP_MEETING"

        /** Single entry point for the tile, the widget and the notification action. */
        const val ACTION_TOGGLE = "com.carlmanning.carlsbrain.AMBIENT_TOGGLE"

        /** Re-reads the length setting and resizes the ring. */
        const val ACTION_RECONFIGURE = "com.carlmanning.carlsbrain.AMBIENT_RECONFIGURE"

        private val _state = MutableStateFlow<AmbientState>(AmbientState.Off)
        val state: StateFlow<AmbientState> = _state.asStateFlow()

        private const val NOTIFICATION_ID = 9101
        private const val CHANNEL_ID = "ambient_buffer"

        const val AUTO_CUTOFF_MS = 90 * 60 * 1000L

        /** 100 ms of audio per read, matching the wake-word loop. */
        private const val READ_SAMPLES = AmbientBuffer.SAMPLE_RATE / 10

        /** Time allowed for the wake word to let go of the mic before we open it. */
        private const val MIC_HANDOVER_MS = 900L

        /**
         * Starts, stops or reconfigures the buffer to match the current setting.
         * Safe to call from anywhere — it does nothing when the feature is off.
         */
        fun syncWithPreferences(context: Context) {
            CarlsBrainApp.appScope.launch {
                val enabled = CarlsBrainApp.userPreferences.ambientBufferEnabled.first()
                val action = if (enabled) ACTION_START_BUFFER else ACTION_STOP_BUFFER
                send(context, action)
            }
        }

        fun send(context: Context, action: String) {
            val intent = Intent(context, AmbientBufferService::class.java).apply {
                this.action = action
            }
            runCatching {
                if (action == ACTION_STOP_BUFFER) context.startService(intent)
                else context.startForegroundService(intent)
            }.onFailure { Log.w(TAG, "Could not deliver $action: ${it.message}") }
        }
    }

    private val db by lazy { AppDatabase.getInstance(this) }
    private val prefs get() = CarlsBrainApp.userPreferences
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var captureThread: Thread? = null
    @Volatile private var capturing = false

    /** Set from the capture thread; read by it. Guarded by [encoderLock] for cross-thread use. */
    private val encoderLock = Any()
    private var encoder: PcmAacEncoder? = null

    @Volatile private var meetingId = -1L
    @Volatile private var recordingStartMs = 0L
    @Volatile private var prependedMs = 0L
    @Volatile private var wakeWordWasRunning = false
    private var tickPosted = false

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_BUFFER -> startBuffering()
            ACTION_RECONFIGURE -> if (_state.value !is AmbientState.Recording) startBuffering()
            ACTION_STOP_BUFFER -> {
                if (_state.value is AmbientState.Recording) stopMeeting() else shutdown()
            }
            ACTION_DISABLE -> scope.launch {
                // Cleared first, so the recording's own tear-down sees the feature as off and
                // stands down instead of resuming the buffer.
                prefs.setAmbientBufferEnabled(false)
                handler.post {
                    if (_state.value is AmbientState.Recording) stopMeeting() else shutdown()
                }
            }
            ACTION_PROMOTE -> promote()
            ACTION_STOP_MEETING -> stopMeeting()
            ACTION_TOGGLE ->
                if (_state.value is AmbientState.Recording) stopMeeting() else promote()
            // Null action: START_STICKY restarting us after the process was killed. Rebuild
            // from the setting rather than stopping, or the buffer would silently stay dead
            // after every low-memory kill.
            else -> syncWithPreferences(this)
        }
        return START_STICKY
    }

    // ── Buffering ─────────────────────────────────────────────────────────────

    private fun startBuffering() {
        if (_state.value is AmbientState.Recording) return
        scope.launch {
            if (!prefs.ambientBufferEnabled.first()) {
                handler.post { shutdown() }
                return@launch
            }
            if (!hasMicPermission()) {
                Log.w(TAG, "No microphone permission — buffer cannot run")
                handler.post { shutdown() }
                return@launch
            }
            val minutes = prefs.ambientBufferMinutes.first()
            val wakeWordOwnsMic = prefs.wakeWordEnabled.first()

            AmbientBuffer.open(cacheDir, minutes)
            handler.post {
                foreground(bufferNotification(minutes))
                _state.value = AmbientState.Buffering(AmbientBuffer.bufferedMs())
                startTicking()
                if (wakeWordOwnsMic) {
                    // VoiceCaptureService's KWS loop feeds the ring; opening a second
                    // AudioRecord here would fight it for the microphone and both would suffer.
                    stopCaptureThread()
                } else {
                    startCaptureThread()
                }
            }
        }
    }

    /**
     * Reads the microphone into whichever sink is currently active.
     *
     * One loop serves both jobs on purpose: when a meeting is promoted while this thread owns
     * the mic, only the destination changes, so there is no gap and no second mic client.
     */
    private fun startCaptureThread() {
        if (captureThread?.isAlive == true) return
        capturing = true
        val thread = Thread({
            val minBuf = AudioRecord.getMinBufferSize(
                AmbientBuffer.SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            ) * 2
            val record = runCatching {
                AudioRecord(
                    MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AmbientBuffer.SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    minBuf
                )
            }.getOrNull()

            if (record == null || record.state != AudioRecord.STATE_INITIALIZED) {
                Log.w(TAG, "AudioRecord unavailable — retrying in 3 s")
                runCatching { record?.release() }
                capturing = false
                handler.postDelayed({
                    if (_state.value !is AmbientState.Off) startCaptureThread()
                }, 3_000)
                return@Thread
            }

            val shorts = ShortArray(READ_SAMPLES)
            val bytes = ByteArray(READ_SAMPLES * AmbientBuffer.BYTES_PER_SAMPLE)
            var consecutiveErrors = 0
            runCatching { record.startRecording() }
            try {
                while (capturing) {
                    val read = record.read(shorts, 0, shorts.size)
                    if (read < 0) {
                        consecutiveErrors++
                        if (read == AudioRecord.ERROR_DEAD_OBJECT || consecutiveErrors >= 5) {
                            Log.w(TAG, "Microphone lost (code $read)")
                            break
                        }
                        Thread.sleep(50)
                        continue
                    }
                    consecutiveErrors = 0
                    if (read == 0) { Thread.sleep(10); continue }

                    val enc = synchronized(encoderLock) { encoder }
                    if (enc != null) {
                        var i = 0
                        for (s in 0 until read) {
                            val v = shorts[s].toInt()
                            bytes[i++] = (v and 0xFF).toByte()
                            bytes[i++] = ((v shr 8) and 0xFF).toByte()
                        }
                        enc.feed(bytes, read * AmbientBuffer.BYTES_PER_SAMPLE)
                    } else {
                        AmbientBuffer.feed(shorts, read)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Capture loop error: ${e.message}")
            } finally {
                runCatching { record.stop() }
                runCatching { record.release() }
                capturing = false
            }

            // A meeting in progress must not be left recording silence: end it properly so the
            // audio captured so far is kept and processed.
            handler.post {
                if (_state.value is AmbientState.Recording) stopMeeting()
                else if (_state.value is AmbientState.Buffering) {
                    handler.postDelayed({
                        if (_state.value is AmbientState.Buffering) startCaptureThread()
                    }, 3_000)
                }
            }
        }, "ambient-capture")
        captureThread = thread
        thread.start()
    }

    /**
     * Asks the capture loop to finish. The reference is deliberately kept: the thread does not
     * exit until it notices the flag (one read, ~100 ms), and [startCaptureThread]'s isAlive
     * guard is what stops a second AudioRecord being opened alongside a dying one.
     */
    private fun stopCaptureThread() {
        capturing = false
    }

    // ── Promotion to a meeting ────────────────────────────────────────────────

    private fun promote() {
        if (_state.value is AmbientState.Recording) return
        scope.launch {
            if (!hasMicPermission()) return@launch

            val bufferedMs = AmbientBuffer.bufferedMs()
            val startedAt = System.currentTimeMillis()
            // The meeting genuinely began when the buffered audio began, so that is what is
            // stamped on it — otherwise the recording would be dated after most of its content.
            val id = db.meetingDao().insertMeeting(
                MeetingEntity(status = "RECORDING", recordedAt = startedAt - bufferedMs)
            )

            val wakeWordOn = prefs.wakeWordEnabled.first()
            val autoCutoff = prefs.meetingAutoCutoffEnabled.first()

            val dir = MeetingAudioStore.dir(this@AmbientBufferService)
            val out = File(dir, "meeting_$id.m4a")
            val enc = PcmAacEncoder(out)
            if (!enc.start()) {
                Log.e(TAG, "Encoder would not start — abandoning promotion")
                db.meetingDao().getMeetingById(id)?.let { db.meetingDao().deleteMeeting(it) }
                return@launch
            }

            // Drain the ring into the encoder BEFORE any live audio reaches it, so the
            // buffered minutes land at the front of the file in the right order.
            val drained = AmbientBuffer.drainTo { chunk, len -> enc.feed(chunk, len) }
            prependedMs = drained / (AmbientBuffer.SAMPLE_RATE.toLong() *
                    AmbientBuffer.BYTES_PER_SAMPLE / 1000)

            meetingId = id
            recordingStartMs = System.currentTimeMillis()

            handler.post {
                _state.value = AmbientState.Recording(id, prependedMs, prependedMs)
                foreground(recordingNotification(prependedMs))
                startTicking()

                // Published immediately in both branches, so a Stop tapped during the handover
                // still finds an encoder to finish rather than silently failing to stop.
                synchronized(encoderLock) { encoder = enc }

                if (wakeWordOn) {
                    // Ask the wake word to let go before taking the mic ourselves. The gap is
                    // simply missing audio at the join, not misordered audio.
                    wakeWordWasRunning = true
                    startService(
                        Intent(this@AmbientBufferService, VoiceCaptureService::class.java)
                            .apply { action = VoiceCaptureService.ACTION_STOP_WAKE_WORD }
                    )
                    handler.postDelayed({
                        if (_state.value is AmbientState.Recording) startCaptureThread()
                    }, MIC_HANDOVER_MS)
                } else {
                    // Our loop already owns the mic; the published encoder redirects it.
                    startCaptureThread()
                }

                if (autoCutoff) {
                    handler.postDelayed({
                        if (_state.value is AmbientState.Recording) stopMeeting()
                    }, AUTO_CUTOFF_MS)
                }
            }
        }
    }

    private fun stopMeeting() {
        val current = _state.value
        if (current !is AmbientState.Recording) return
        val id = meetingId
        val enc = synchronized(encoderLock) { encoder.also { encoder = null } } ?: return

        // Freeze the state now so a second Stop tap, or the capture thread's own exit path,
        // cannot start a second finish on the same encoder.
        _state.value = AmbientState.Off
        val thread = captureThread
        stopCaptureThread()
        meetingId = -1L

        scope.launch {
            // The capture thread may be inside PcmAacEncoder.feed right now, and the encoder is
            // not safe to finish underneath it. Wait for the loop to notice `capturing` is
            // false — one read is 100 ms, so this returns almost immediately.
            runCatching { thread?.join(2_000) }
            val file = enc.finish()
            val durationMs = enc.encodedMs()
            val meeting = db.meetingDao().getMeetingById(id)
            if (meeting != null && meeting.status == "RECORDING") {
                db.meetingDao().updateMeeting(
                    meeting.copy(
                        durationMs = durationMs,
                        localAudioPath = file?.absolutePath ?: "",
                        updatedAt = System.currentTimeMillis()
                    )
                )
            }

            // Hand off through the same channel a normally-recorded meeting uses, so
            // MeetingViewModel's existing Fireflies → Whisper → Claude path picks it up
            // unchanged. If no ViewModel is alive — a recording started from the tile with the
            // app closed — the row stays at RECORDING with its audio saved, and
            // MeetingViewModel recovers it the next time the Meetings screen opens.
            MeetingRecordingService._state.value = MeetingServiceState.Stopped(
                meetingId = id,
                durationMs = durationMs,
                localAudioPath = file?.absolutePath ?: "",
                transcript = ""
            )
            notifyRecordingSaved(id, durationMs)

            // Back to buffering if it is still switched on; otherwise stand down entirely.
            val stillOn = prefs.ambientBufferEnabled.first()
            handler.post {
                if (wakeWordWasRunning) {
                    wakeWordWasRunning = false
                    startService(
                        Intent(this@AmbientBufferService, VoiceCaptureService::class.java)
                            .apply { action = VoiceCaptureService.ACTION_RESUME_WAKE_WORD }
                    )
                }
                if (stillOn) startBuffering() else shutdown()
            }
        }
    }

    private fun shutdown() {
        stopCaptureThread()
        _state.value = AmbientState.Off
        handler.removeCallbacksAndMessages(null)
        tickPosted = false
        AmbientBuffer.deleteFile(cacheDir)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    // ── Notification ──────────────────────────────────────────────────────────

    private fun startTicking() {
        if (tickPosted) return
        tickPosted = true
        handler.postDelayed(ticker, 1_000)
    }

    private val ticker = object : Runnable {
        override fun run() {
            when (val s = _state.value) {
                is AmbientState.Buffering -> {
                    _state.value = AmbientState.Buffering(AmbientBuffer.bufferedMs())
                }
                is AmbientState.Recording -> {
                    val elapsed = prependedMs + (System.currentTimeMillis() - recordingStartMs)
                    _state.value = s.copy(elapsedMs = elapsed)
                    updateNotification(recordingNotification(elapsed))
                }
                AmbientState.Off -> { tickPosted = false; return }
            }
            handler.postDelayed(this, 1_000)
        }
    }

    private fun foreground(notification: Notification) {
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification,
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        )
    }

    private fun updateNotification(notification: Notification) {
        if (!canPostNotifications()) return
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    private fun action(intentAction: String, requestCode: Int, label: String) =
        NotificationCompat.Action(
            0, label,
            PendingIntent.getService(
                this, requestCode,
                Intent(this, AmbientBufferService::class.java).apply { action = intentAction },
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
        )

    private fun bufferNotification(minutes: Int): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Listening")
            .setContentText("Keeping the last $minutes minutes")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(action(ACTION_PROMOTE, 1, "Save that"))
            .addAction(action(ACTION_DISABLE, 2, "Turn off"))
            .setOngoing(true)
            .build()

    private fun recordingNotification(elapsedMs: Long): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Recording meeting")
            .setContentText(
                formatDuration(elapsedMs) +
                    if (prependedMs > 0) " · ${formatDuration(prependedMs)} from buffer" else ""
            )
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .addAction(action(ACTION_STOP_MEETING, 3, "Stop"))
            .setOngoing(true)
            .build()

    private fun notifyRecordingSaved(id: Long, durationMs: Long) {
        if (!canPostNotifications()) return
        val tapIntent = Intent(this, MainActivity::class.java).apply {
            putExtra(MainActivity.EXTRA_OPEN_MEETING_ID, id)
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val notification = NotificationCompat.Builder(this, CarlsBrainApp.MEETINGS_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Recording saved")
            .setContentText("${formatDuration(durationMs)} — tap to process")
            .setAutoCancel(true)
            .setContentIntent(
                PendingIntent.getActivity(
                    this, id.toInt(), tapIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            )
            .build()
        getSystemService(NotificationManager::class.java).notify(id.toInt(), notification)
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID, "Ambient buffer", NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Rolling audio buffer and buffer-started recordings" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun canPostNotifications(): Boolean =
        android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    private fun hasMicPermission(): Boolean =
        ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun formatDuration(ms: Long): String {
        val s = ms / 1000
        return "%d:%02d".format(s / 60, s % 60)
    }

    override fun onDestroy() {
        super.onDestroy()
        // A meeting killed with the process still has real audio in the encoder. Finish it
        // synchronously — losing the recording is the one outcome that cannot be undone.
        val enc = synchronized(encoderLock) { encoder.also { encoder = null } }
        if (enc != null && meetingId > 0) {
            val id = meetingId
            val file = enc.finish()
            val durationMs = enc.encodedMs()
            runCatching {
                runBlocking {
                    db.meetingDao().getMeetingById(id)?.let { m ->
                        if (m.status == "RECORDING") {
                            db.meetingDao().updateMeeting(
                                m.copy(
                                    durationMs = durationMs,
                                    localAudioPath = file?.absolutePath ?: "",
                                    updatedAt = System.currentTimeMillis()
                                )
                            )
                        }
                    }
                }
            }
        }
        // ACTION_STOP_WAKE_WORD parked the wake word for the recording. If this service is torn
        // down without stopMeeting() running, nothing else would ever un-park it and "Hey
        // Brain" would stay silently dead until Carl next toggled it in Settings.
        if (wakeWordWasRunning) {
            wakeWordWasRunning = false
            runCatching {
                startService(
                    Intent(this, VoiceCaptureService::class.java)
                        .apply { action = VoiceCaptureService.ACTION_RESUME_WAKE_WORD }
                )
            }
        }
        capturing = false
        captureThread = null
        meetingId = -1L
        handler.removeCallbacksAndMessages(null)
        tickPosted = false
        _state.value = AmbientState.Off
        scope.cancel()
    }
}
