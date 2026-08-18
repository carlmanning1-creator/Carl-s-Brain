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
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
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
import com.k2fsa.sherpa.onnx.KeywordSpotter
import com.k2fsa.sherpa.onnx.KeywordSpotterConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import com.k2fsa.sherpa.onnx.getFeatureConfig
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
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.voice.WakeWordModel
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
        /** sherpa-onnx KWS runs at 16 kHz; the spotter accepts arbitrary buffer lengths. */
        private const val KWS_SAMPLE_RATE = 16000
        /** 100 ms of audio per read — matches the sherpa-onnx KWS demo's cadence. */
        private const val KWS_BUFFER_SAMPLES = KWS_SAMPLE_RATE / 10
        /** How often to re-evaluate quiet hours, both while parked and while listening. */
        private const val QUIET_RECHECK_INTERVAL_MS = 5 * 60 * 1000L

        const val ACTION_START_WAKE_WORD = "com.carlmanning.carlsbrain.START_WAKE_WORD"
        // Temporary pause — used by Chat screen while it borrows the mic. Does NOT stop the service.
        const val ACTION_STOP_WAKE_WORD = "com.carlmanning.carlsbrain.STOP_WAKE_WORD"
        // Permanent disable — used by Settings toggle. Stops the spotter AND the service.
        const val ACTION_DISABLE_WAKE_WORD = "com.carlmanning.carlsbrain.DISABLE_WAKE_WORD"
        const val ACTION_RESUME_WAKE_WORD = "com.carlmanning.carlsbrain.RESUME_WAKE_WORD"
        // Recycle the listening loop so a changed wake phrase or threshold takes effect without
        // an app restart. Distinct from STOP/START so neither of those is overloaded with a
        // second meaning, and so no second audio thread can be started while the first is
        // still holding the mic.
        const val ACTION_RESTART_WAKE_WORD = "com.carlmanning.carlsbrain.RESTART_WAKE_WORD"
        // Sent by VoiceCaptureActivity.onResume to release any in-progress service speech.
        const val ACTION_STOP_LISTENING = "com.carlmanning.carlsbrain.STOP_LISTENING"
        // Sent by the notification's explicit "Speak" action — triggers a Hey Brain conversation
        // directly in the service, respecting the resume window (same as a wake-word detection).
        const val ACTION_TRIGGER_CONVERSATION = "com.carlmanning.carlsbrain.TRIGGER_CONVERSATION"
        // Names the sender of ACTION_TRIGGER_CONVERSATION for the trigger log. Anything that
        // arrives without it is recorded as EXTERNAL_INTENT so a stray sender is visible.
        const val EXTRA_TRIGGER_SOURCE = "com.carlmanning.carlsbrain.EXTRA_TRIGGER_SOURCE"

        // True while either the service or VoiceCaptureActivity is handling a session.
        @Volatile var isConversationActive = false
    }

    private val handler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var wakeWordActive = false
    @Volatile private var isListening = false

    private var audioThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var keywordSpotter: KeywordSpotter? = null

    // Optional hardware effects on the wake-word capture session — they stop the app hearing
    // its own TTS. Absent on plenty of devices, so every use is guarded.
    private var echoCanceler: AcousticEchoCanceler? = null
    private var noiseSuppressor: NoiseSuppressor? = null

    // True from the moment TTS is asked to speak until the utterance finishes. Wake-word
    // detections are ignored while it is set, so a spoken reply containing "brain" (the app
    // is literally called Carl's Brain) can't re-trigger the service through the mic.
    @Volatile private var isSpeaking = false

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
    private var memoryLoadJob: Job? = null

    // Counts consecutive ERROR_NO_MATCH to prevent infinite re-listen loop
    private var consecutiveNoMatch = 0

    // Resume window, in ms. Configurable in Settings; the default of 45 s gives plenty of time
    // after the 8 s silence end tone to say Hey Brain. Cached rather than read from DataStore at
    // the point of use because the check runs on the main thread inside triggerConversation,
    // which cannot suspend. Refreshed whenever the wake-word loop (re)starts and on each
    // conversation end, so a Settings change lands within one cycle.
    @Volatile private var conversationResumeWindowMs = UserPreferences.DEFAULT_RESUME_WINDOW_SEC * 1000L
    private var lastConversationEndTime: Long = 0L

    // Quiet hours, cached for the same reason as the resume window. Refreshed on every loop
    // start and on each periodic re-check, so Settings changes take effect without a restart.
    @Volatile private var quietEnabled = false
    @Volatile private var quietStartMin = 0
    @Volatile private var quietEndMin = 0

    // Guards against stacking multiple pending quiet-hours re-checks. Every scheduling site
    // must go through scheduleQuietHoursRecheck() so this stays the single source of truth —
    // without it, each parked start would add another repeating chain and they would multiply.
    private var quietRecheckPending = false

    /** Whether the wake word should be parked right now because of quiet hours. */
    private fun inQuietHours(): Boolean {
        if (!quietEnabled) return false
        val now = java.time.LocalTime.now()
        return UserPreferences.isWithinQuietHours(
            now.hour * 60 + now.minute, quietStartMin, quietEndMin
        )
    }

    private fun quietWindowLabel(): String {
        fun fmt(min: Int) = String.format(Locale.US, "%02d:%02d", min / 60, min % 60)
        return "${fmt(quietStartMin)}–${fmt(quietEndMin)}"
    }

    /**
     * Re-checks quiet hours on a slow repeating tick while the wake word is parked.
     *
     * A single long postDelayed to the exact end boundary would be the obvious approach and is
     * the wrong one: it would be an 8-hour timer across Doze, where it is not reliably
     * delivered. A 5-minute chain is Doze-tolerant, self-healing if one tick is delayed, and
     * costs nothing — the imprecision at the boundary is at most 5 minutes, which is
     * immaterial for "start listening again in the morning".
     */
    private fun scheduleQuietHoursRecheck() {
        if (quietRecheckPending) return
        quietRecheckPending = true
        handler.postDelayed({
            quietRecheckPending = false
            // wakeWordActive false means Settings turned it off, or Chat borrowed the mic —
            // either way the chain ends here and the eventual resume restarts it.
            if (wakeWordActive && !isConversationActive && !isListening) startWakeWordLoop()
        }, QUIET_RECHECK_INTERVAL_MS)
    }

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
            } else {
                // Wake word is disabled — nothing to do. Stop so we don't hold the
                // FOREGROUND_SERVICE_TYPE_MICROPHONE slot with no purpose (common after
                // START_STICKY restarts the service following an app close).
                handler.post { stopSelf() }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) {
            // START_STICKY redelivery after the OS killed us: Android restarts the service with
            // a null intent, so no branch below matches and the spotter would never start — the
            // service stays alive, foreground notification showing, but completely deaf.
            // Same reasoning as ACTION_RESUME_WAKE_WORD: read DataStore, not the in-memory
            // flags, because a fresh process has them all at their defaults anyway.
            handler.post {
                if (!isConversationActive && !isListening) {
                    serviceScope.launch {
                        if (CarlsBrainApp.userPreferences.wakeWordEnabled.first()) {
                            logTrigger(UserPreferences.TRIGGER_SOURCE_RESUME)
                            handler.post { startWakeWordLoop() }
                        }
                    }
                }
            }
            return START_STICKY
        }
        when (intent.action) {
            ACTION_START_WAKE_WORD -> handler.post { startWakeWordLoop() }
            // Temporary pause (Chat mic borrow) — stop the spotter but keep the service alive.
            ACTION_STOP_WAKE_WORD -> handler.post { stopWakeWordLoop() }
            // Permanent disable (Settings toggle) — stop the spotter and shut down the service.
            ACTION_DISABLE_WAKE_WORD -> handler.post {
                stopWakeWordLoop()
                // Brief delay lets the audio thread exit its read loop cleanly before onDestroy.
                handler.postDelayed({ stopSelf() }, 1500)
            }
            // The wake phrase or threshold changed in Settings. keywords.txt is only rewritten
            // when the loop starts, so the running loop has to be recycled.
            ACTION_RESTART_WAKE_WORD -> handler.post {
                if (isListening) {
                    // Drop out of the running loop and let its own finally block restart it
                    // 1.5 s later, which re-reads the preferences and rewrites keywords.txt.
                    // Deliberately does NOT launch a thread here: the old one still owns the
                    // AudioRecord, and a second would fight it for the mic.
                    // wakeWordActive stays true, which is what makes that restart happen.
                    isListening = false
                } else if (wakeWordActive && !isConversationActive) {
                    startWakeWordLoop()
                }
                // Otherwise the wake word is intentionally paused (Chat holds the mic) or a
                // conversation is in progress — the eventual resume reads the new preference.
            }
            ACTION_RESUME_WAKE_WORD -> {
                // Check the DataStore preference directly rather than wakeWordActive, because
                // ACTION_STOP_WAKE_WORD (used for Chat mic pause) sets wakeWordActive = false,
                // which would permanently block this resume from ever restarting the spotter.
                handler.postDelayed({
                    if (!isConversationActive && !isListening) {
                        serviceScope.launch {
                            if (CarlsBrainApp.userPreferences.wakeWordEnabled.first()) {
                                logTrigger(UserPreferences.TRIGGER_SOURCE_RESUME)
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
            // Explicit "Speak" notification action — start or resume a Hey Brain conversation
            // directly in the service, same as a wake-word detection. Resume window applies.
            ACTION_TRIGGER_CONVERSATION -> {
                val source = intent.getStringExtra(EXTRA_TRIGGER_SOURCE)
                    ?: UserPreferences.TRIGGER_SOURCE_EXTERNAL_INTENT
                handler.post {
                    if (!isConversationActive) {
                        isListening = false  // stops the wake-word loop if running
                        triggerConversation(source)
                    }
                }
            }
        }
        return START_STICKY
    }

    // ── Wake word (sherpa-onnx keyword spotting) ──────────────────────────────

    private fun startWakeWordLoop() {
        wakeWordActive = true
        if (isConversationActive) return
        if (isListening) return

        serviceScope.launch {
            val prefs = CarlsBrainApp.userPreferences
            val displayName = prefs.wakeKeyword.first()
            val threshold = prefs.wakeThreshold.first()
            // Refresh everything the main-thread paths read from cached fields, so a Settings
            // change lands on the next loop start rather than needing an app restart.
            conversationResumeWindowMs = prefs.wakeResumeWindowSec.first() * 1000L
            quietEnabled = prefs.wakeQuietEnabled.first()
            quietStartMin = prefs.wakeQuietStartMin.first()
            quietEndMin = prefs.wakeQuietEndMin.first()

            // Park rather than open the mic during quiet hours. wakeWordActive stays true —
            // this is a scheduled pause, not a disable — and the re-check chain brings the
            // loop back on its own once the window ends.
            if (inQuietHours()) {
                Log.i(TAG, "Wake word parked for quiet hours ${quietWindowLabel()}")
                updateNotification("Hey Brain: quiet hours ${quietWindowLabel()}")
                handler.post { scheduleQuietHoursRecheck() }
                return@launch
            }

            // Stages the model on internal storage and writes keywords.txt for the selected
            // phrase. Returns null only when a model asset is absent from the APK.
            val files = WakeWordModel.prepare(applicationContext, displayName, threshold)
            if (files == null) {
                Log.w(TAG, "Wake word model unavailable — wake word inactive")
                updateNotification("Hey Brain: ${WakeWordModel.MISSING_MODEL_MESSAGE}")
                return@launch
            }
            handler.post { launchAudioThread(files) }
        }
    }

    private fun launchAudioThread(files: WakeWordModel.KwsFiles) {
        if (isListening) return
        isListening = true

        audioThread = Thread({
            val spotter: KeywordSpotter
            val stream: OnlineStream
            try {
                // assetManager is deliberately left null: the file-loading constructor is the
                // only one that reads keywords.txt from disk (the asset constructor resolves
                // every path through the APK), and it validates its config and returns a null
                // pointer on failure — which surfaces here as a catchable exception rather
                // than the native abort the asset path performs.
                spotter = KeywordSpotter(
                    assetManager = null,
                    config = KeywordSpotterConfig(
                        featConfig = getFeatureConfig(
                            sampleRate = KWS_SAMPLE_RATE,
                            featureDim = 80
                        ),
                        modelConfig = OnlineModelConfig(
                            transducer = OnlineTransducerModelConfig(
                                encoder = files.encoder,
                                decoder = files.decoder,
                                joiner = files.joiner
                            ),
                            tokens = files.tokens,
                            modelType = "zipformer2",
                            numThreads = 1,
                            provider = "cpu"
                        ),
                        keywordsFile = files.keywordsFile
                    )
                )
                // Empty string means "use the keywords from keywordsFile" — the phrase and its
                // threshold are already baked into the file written by WakeWordModel.prepare.
                stream = spotter.createStream()
            } catch (e: Throwable) {
                // Covers the require() in KeywordSpotter's init (bad config) as well as
                // UnsatisfiedLinkError if the native library failed to load. NOT the
                // "model files absent" case — WakeWordModel.prepare already ruled that out by
                // returning non-null, so reporting a missing model here would send Carl looking
                // in the wrong place. The real detail goes in the notification.
                val detail = e.message?.takeIf { it.isNotBlank() } ?: e::class.java.simpleName
                Log.e(TAG, "KeywordSpotter init failed: $detail", e)
                updateNotification("Hey Brain: wake word engine failed — ${detail.take(120)}")
                isListening = false
                return@Thread
            }

            keywordSpotter = spotter

            val sampleRate = KWS_SAMPLE_RATE
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
                // Mic may not have fully released from TTS or SpeechRecognizer yet.
                // Retry once after 2 s rather than dying silently.
                Log.w(TAG, "AudioRecord failed to initialise — mic not released yet, retrying in 2 s")
                record.release()
                stream.release()
                spotter.release()
                keywordSpotter = null
                isListening = false
                handler.postDelayed({
                    if (wakeWordActive && !isConversationActive && !isListening) startWakeWordLoop()
                }, 2000)
                return@Thread
            }

            audioRecord = record
            attachAudioEffects(record.audioSessionId)
            record.startRecording()
            // Clear any parked/error text now that the mic really is open — otherwise the
            // notification keeps claiming "quiet hours" all morning after the window ends.
            updateNotification("Brain is ready")

            // Unlike Porcupine, sherpa dictates no frame length — it consumes whatever it is
            // handed. 100 ms keeps latency low without waking the CPU excessively.
            val buffer = ShortArray(KWS_BUFFER_SAMPLES)
            var consecutiveReadErrors = 0
            // Reads until the next quiet-hours check. Counted in buffers (100 ms each) rather
            // than wall-clock so the check costs one integer compare per read, and so it cannot
            // fire repeatedly if the loop is starved and several reads land in the same instant.
            var readsUntilQuietCheck = (QUIET_RECHECK_INTERVAL_MS / 100).toInt()
            try {
                while (isListening) {
                    // Quiet hours can begin while the loop is already running. Dropping out here
                    // lets the finally block's restart re-enter startWakeWordLoop(), which sees
                    // the window and parks — no separate teardown path to keep in step.
                    if (quietEnabled && --readsUntilQuietCheck <= 0) {
                        readsUntilQuietCheck = (QUIET_RECHECK_INTERVAL_MS / 100).toInt()
                        if (inQuietHours()) {
                            Log.i(TAG, "Quiet hours began — releasing mic")
                            isListening = false
                            continue
                        }
                    }
                    val read = record.read(buffer, 0, buffer.size)
                    if (read < 0) {
                        // Negative values are error codes, not short reads. Without this the
                        // loop spun a tight CPU burn forever whenever a call or another app
                        // took the mic, and never recovered.
                        consecutiveReadErrors++
                        Log.w(TAG, "AudioRecord.read error $read (consecutive=$consecutiveReadErrors)")
                        if (read == AudioRecord.ERROR_DEAD_OBJECT || consecutiveReadErrors >= 5) {
                            // Mic is gone. Drop out of the loop — the finally block below already
                            // schedules the retry (and the init-failure path retries again after
                            // 2 s if the mic is still held), so no second mechanism is needed.
                            Log.w(TAG, "AudioRecord unusable — exiting loop for scheduled restart")
                            isListening = false
                        } else {
                            Thread.sleep(50)
                        }
                        continue
                    }
                    consecutiveReadErrors = 0
                    if (read == 0) {
                        // Nothing captured — sleep so this can't hot-spin.
                        Thread.sleep(10)
                        continue
                    }
                    // sherpa wants float samples in [-1, 1]. Only the samples actually read are
                    // converted, so a short read feeds valid audio instead of a stale tail.
                    val samples = FloatArray(read) { buffer[it] / 32768.0f }
                    stream.acceptWaveform(samples, sampleRate = sampleRate)
                    while (spotter.isReady(stream)) {
                        spotter.decode(stream)
                        val keyword = spotter.getResult(stream).keyword
                        if (keyword.isBlank()) continue
                        // A match stays in the result until the stream is reset, so reset
                        // immediately or every later decode reports the same detection.
                        spotter.reset(stream)
                        if (!isConversationActive && !isAppSpeaking()) {
                            val level = frameRms(buffer, read)
                            handler.post {
                                triggerConversation(UserPreferences.TRIGGER_SOURCE_KWS, 0, level)
                            }
                        } else if (isAppSpeaking()) {
                            // Almost certainly the app hearing its own TTS say "brain". Keep the
                            // loop running, just don't act on it.
                            Log.d(TAG, "Wake word ignored — app is speaking")
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Audio loop error: ${e.message}")
            } finally {
                releaseAudioEffects()
                record.stop()
                record.release()
                audioRecord = null
                stream.release()
                spotter.release()
                keywordSpotter = null
                isListening = false
                // Use wakeWordActive here, NOT DataStore. wakeWordActive is the runtime
                // "am I intentionally running" flag. stopWakeWordLoop() sets it false to
                // pause for Chat — if we ignored it and used DataStore instead, the spotter
                // would restart immediately every time Chat borrows the mic, then steal the
                // mic back and trigger Hey Brain mid-chat-session.
                if (wakeWordActive) {
                    handler.postDelayed({
                        if (wakeWordActive && !isConversationActive && !isListening) startWakeWordLoop()
                    }, 1500)
                }
            }
        }, "kws-audio-thread")

        audioThread?.start()
    }

    /**
     * Attaches echo cancellation and noise suppression to the capture session so the app is
     * far less likely to hear its own TTS. Both are optional hardware effects — every step is
     * wrapped so an unsupported device silently keeps the plain, working audio path.
     */
    private fun attachAudioEffects(sessionId: Int) {
        runCatching {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w(TAG, "AcousticEchoCanceler unavailable: ${it.message}") }
        runCatching {
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(sessionId)?.apply { enabled = true }
            }
        }.onFailure { Log.w(TAG, "NoiseSuppressor unavailable: ${it.message}") }
    }

    /** Released alongside the AudioRecord they were attached to. */
    private fun releaseAudioEffects() {
        runCatching { echoCanceler?.release() }
        echoCanceler = null
        runCatching { noiseSuppressor?.release() }
        noiseSuppressor = null
    }

    /**
     * True while the app itself has the audio floor. Covers this service's own TTS via
     * [isSpeaking], plus the engine's own view of it in case an utterance callback is missed.
     *
     * Deliberately does NOT consult AudioManager.isMusicActive(): TTS shares STREAM_MUSIC with
     * ordinary playback, so that check would also kill Hey Brain any time Carl plays music.
     */
    private fun isAppSpeaking(): Boolean =
        isSpeaking || runCatching { tts?.isSpeaking == true }.getOrDefault(false)

    /**
     * Simple RMS of a captured frame — recorded with wake-word triggers as evidence of level.
     *
     * [count] is the number of samples AudioRecord actually returned; anything past it is stale
     * data from an earlier read and would skew the level.
     */
    private fun frameRms(buffer: ShortArray, count: Int = buffer.size): Int {
        val n = count.coerceIn(0, buffer.size)
        if (n == 0) return 0
        var sum = 0.0
        for (i in 0 until n) {
            val v = buffer[i].toDouble()
            sum += v * v
        }
        return Math.sqrt(sum / n).toInt()
    }

    /** Fire-and-forget append to the diagnostics ring buffer. Never blocks the caller. */
    private fun logTrigger(source: String, keywordIndex: Int = -1, rms: Int = -1) {
        serviceScope.launch {
            runCatching {
                CarlsBrainApp.userPreferences.logWakeTrigger(source, keywordIndex, rms)
            }.onFailure { Log.w(TAG, "Trigger log write failed: ${it.message}") }
        }
    }

    private fun stopWakeWordLoop() {
        wakeWordActive = false
        // Stop only the wake-word audio thread — do NOT removeCallbacksAndMessages(null)
        // as that would also kill any in-flight TTS onDone and speak() continuations.
        isListening = false
    }

    // ── Conversation pipeline ─────────────────────────────────────────────────

    private fun triggerConversation(
        source: String = UserPreferences.TRIGGER_SOURCE_EXTERNAL_INTENT,
        keywordIndex: Int = -1,
        rms: Int = -1
    ) {
        logTrigger(source, keywordIndex, rms)
        // The wake-word AudioRecord thread will release the mic in its finally block.
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
            memoryLoadJob = serviceScope.launch {
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
                    // of the spotter treating it as a wake word. One clean end + 45 s resume
                    // window is the better UX: conversation ends clearly, user says Hey Brain.
                    endConversation(intentional = false)
                }
                error == SpeechRecognizer.ERROR_NO_MATCH -> {
                    consecutiveNoMatch++
                    if (consecutiveNoMatch >= 2) {
                        consecutiveNoMatch = 0
                        endConversation(intentional = false)
                    } else {
                        startServiceSpeechRecognition()
                    }
                }
                error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY ||
                error == SpeechRecognizer.ERROR_CLIENT ||
                error == SpeechRecognizer.ERROR_AUDIO -> handler.postDelayed({ startServiceSpeechRecognition() }, 800)
                else -> handler.postDelayed({ startServiceSpeechRecognition() }, 1000)
            }
        }

        override fun onResults(results: Bundle?) {
            consecutiveNoMatch = 0
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
            // Ensure memory.md has finished loading before building the system prompt
            memoryLoadJob?.join()
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

Save a note to the app:
[NOTE: title | bucket]
Only use [NOTE:] when Carl explicitly asks you to save something as a note (e.g. "save that", "note that down", "add that to my notes"). Do NOT save conversation context or things discussed — those go to memory automatically. If unsure, ask before saving.

Mark a to-do as done (fuzzy title match):
[DONE: title of the todo]
Only use [DONE:] when Carl says he has ALREADY completed something (e.g. "I've done the vehicle check", "mark the roster off"). Never use it for something he is asking you to create, plan or remind him about. Never emit [DONE:] for a to-do you created in the same reply. The match is a loose substring search, so use the most distinctive words of the title — a short or generic query can complete the wrong to-do, and that is silent and hard to notice.

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

            val result = claude.chat(messages = conversationHistory.toList(), systemPrompt = systemPrompt, maxTokens = 2048)
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

            val memoryContext = "Voice: \"$text\" → \"${displayText.take(200)}\""
            if (isExplicitRemember(text)) {
                MemoryLearner.forceLearnFrom(applicationContext, memoryContext)
            } else {
                MemoryLearner.learnFrom(applicationContext, memoryContext, "voice")
            }

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

        // Todos created by THIS response. [DONE:] matches on a fuzzy substring across every
        // active todo, and it runs after [TODO:] on the same response, so without this a
        // brand-new todo is a candidate for immediate completion by the very reply that made
        // it — after which the nightly cleanup archives it and it vanishes from the list
        // entirely, having apparently never been created at all.
        val createdTodoIds = mutableSetOf<Long>()

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
            createdTodoIds += todoId
            // Records where it actually landed. A todo sorted into a vault bucket is invisible
            // on the Todos screen while the vault is closed, which looks identical to the save
            // having failed — this line is what distinguishes the two after the fact.
            Log.i(
                TAG,
                "Voice todo #$todoId -> bucket '${bucket.name}'" +
                    "${if (bucket.isVault) " (VAULT — hidden unless the vault is open)" else ""}" +
                    ", priority ${priority.name}"
            )
            postSavedNotification("Task added", title, todoId, true, bucket.isVault)
        }

        noteRegex.findAll(response).forEach { match ->
            val parts = match.groupValues[1].split("|").map { it.trim() }
            val title = parts.getOrElse(0) { "" }.ifBlank { return@forEach }
            val bucketName = parts.getOrElse(1) { "Other" }
            val bucket = buckets.find { it.name.equals(bucketName, ignoreCase = true) } ?: defaultBucket
            val noteId = db.noteDao().insertNote(
                NoteEntity(title = title, content = userText, bucketId = bucket.id)
            )
            postSavedNotification("Note saved", title, noteId, false, bucket.isVault)
        }

        doneRegex.findAll(response).forEach { match ->
            val titleQuery = match.groupValues[1].trim().ifBlank { return@forEach }
            val todo = db.todoDao().searchTodos(titleQuery)
                .firstOrNull { !it.isDone && it.id !in createdTodoIds }
                ?: return@forEach
            // Which todo a fuzzy match actually landed on is otherwise unknowable after the
            // fact, and completing the wrong one is silent — the right todo simply disappears.
            Log.i(TAG, "Voice [DONE: $titleQuery] matched todo #${todo.id} '${todo.title}'")
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

    private fun isExplicitRemember(text: String): Boolean {
        val lower = text.lowercase()
        return "remember this" in lower || "remember that" in lower ||
               "remember our" in lower || "save this to memory" in lower ||
               "save that to memory" in lower || "save to memory" in lower ||
               lower.startsWith("remember ") || lower == "remember"
    }

    private fun isExitIntent(text: String): Boolean {
        val lower = text.lowercase().trim()
        // Short unambiguous exits — exact match only
        if (lower == "stop" || lower == "bye" || lower == "exit") return true
        // "goodbye" is unambiguous in a voice conversation — substring match handles
        // "goodbye brain", "say goodbye", "good bye", etc.
        if ("goodbye" in lower || "good bye" in lower) return true
        // "thanks"/"thank you" as the whole utterance or at the tail of a sentence
        // (e.g. "okay thanks", "alright thank you", "great thank you brain").
        // NOT triggered mid-sentence: "thank you and also add a task" won't end with these.
        if (lower == "thanks" || lower == "thank you") return true
        if (lower.endsWith(" thanks") || lower.endsWith(" thank you")) return true
        // Explicit sign-off phrases
        if (lower in setOf("that's all", "thats all", "that's it", "thats it", "all done")) return true
        return false
    }

    /**
     * @param intentional true when the user explicitly ended the session (goodbye/stop/thank you).
     *   Clears the resume timestamp so the next Hey Brain starts fresh.
     *   false (timeout) preserves the timestamp so Hey Brain within 45 s resumes the conversation.
     */
    private fun endConversation(intentional: Boolean = false) {
        isConversationActive = false
        isSpeaking = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        lastConversationEndTime = if (intentional) 0L else System.currentTimeMillis()
        // Refresh the cached follow-up window here as well as on wake-word loop start. A
        // conversation can be started from the notification or the Quick Settings tile with the
        // wake word switched off entirely, in which case the loop never runs and the cached
        // value would sit at its default forever. Reading it now — rather than when the resume
        // is checked — keeps triggerConversation free of suspending work on the main thread.
        serviceScope.launch {
            conversationResumeWindowMs =
                CarlsBrainApp.userPreferences.wakeResumeWindowSec.first() * 1000L
        }
        playEndToneIfEnabled()
        updateNotification("Brain is ready")
        // Always attempt wake-word restart via DataStore, not wakeWordActive.
        // wakeWordActive is set to false by ACTION_STOP_WAKE_WORD when the Chat screen
        // borrows the mic, so checking it here would permanently block Hey Brain from
        // restarting after any conversation that followed a Chat session.
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

    // ── Text-to-Speech ────────────────────────────────────────────────────────

    private fun initTtsIfNeeded() {
        if (tts != null) return
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(id: String?) {}
                    override fun onDone(id: String?) {
                        isSpeaking = false
                        handler.post {
                            val cb = pendingTtsOnDone
                            pendingTtsOnDone = null
                            cb?.invoke()
                        }
                    }
                    @Deprecated("Deprecated in Java")
                    override fun onError(id: String?) {
                        isSpeaking = false
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
            // Set before queueing so a detection can never slip through between the two.
            // Cleared by onDone/onError, and defensively by endConversation/onDestroy so it
            // can never latch on and mute the wake word permanently.
            isSpeaking = true
            runCatching {
                tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "brain_${System.currentTimeMillis()}")
            }.onFailure {
                // speak() itself threw before the utterance was queued — fire the callback directly
                isSpeaking = false
                pendingTtsOnDone = null
                handler.post { onDone() }
            }
        } else {
            // TTS engine not ready yet — skip audio and proceed after a short delay.
            isSpeaking = false
            handler.postDelayed({ onDone() }, 800)
        }
    }

    // ── Notifications ─────────────────────────────────────────────────────────

    /**
     * Confirmation notification for something saved by voice.
     *
     * [isVault] suppresses the item's own text. Notifications render on the lock screen, which
     * is outside the app's biometric gate, so a vault item's title must never be placed in one —
     * the standing rule is that vault content never appears in notifications. The notification
     * is still posted and still opens the item, because the destination is behind the app lock;
     * only the text is withheld.
     */
    private fun postSavedNotification(
        title: String,
        body: String,
        itemId: Long,
        isTodo: Boolean,
        isVault: Boolean = false
    ) {
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
            .setContentTitle(if (isVault) "Saved to a private bucket" else title)
            .setContentText(if (isVault) "Open the app to view it" else body)
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

    /**
     * Conversation-end beep, if Settings has it on.
     *
     * The preference is read here rather than cached: this runs at most once per conversation,
     * so the DataStore read is free, and reading it live means a change applies immediately
     * instead of on the next wake-word loop start.
     */
    private fun playEndToneIfEnabled() {
        serviceScope.launch {
            if (CarlsBrainApp.userPreferences.conversationEndTone.first()) {
                handler.post { playEndTone() }
            }
        }
    }

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
        wakeWordActive = false
        isSpeaking = false
        handler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        tts?.stop()
        tts?.shutdown()
        tts = null
        // AudioRecord is normally released by the audio thread's finally block, but if
        // onDestroy races with the thread (OS force-kill), release it here as a safety net.
        releaseAudioEffects()
        runCatching { audioRecord?.stop() }
        runCatching { audioRecord?.release() }
        audioRecord = null
        runCatching { keywordSpotter?.release() }
        keywordSpotter = null
        getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
    }

    private fun buildNotification(contentText: String): Notification {
        // Body tap opens the app. It must NOT start a conversation: this notification is
        // permanently in the shade, so wiring the body to ACTION_TRIGGER_CONVERSATION meant a
        // pocket touch or stray tap started Hey Brain with no wake word ever spoken.
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        // Deliberate, labelled control — kept, because tapping "Speak" is unambiguous intent.
        val speakIntent = PendingIntent.getService(
            this, 1,
            Intent(this, VoiceCaptureService::class.java).apply {
                action = ACTION_TRIGGER_CONVERSATION
                putExtra(EXTRA_TRIGGER_SOURCE, UserPreferences.TRIGGER_SOURCE_NOTIFICATION_ACTION)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Brain is ready")
            .setContentText(contentText)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(openIntent)
            .addAction(android.R.drawable.ic_btn_speak_now, "Speak", speakIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build()
    }

    private fun updateNotification(contentText: String) {
        getSystemService(NotificationManager::class.java)
            .notify(NOTIFICATION_ID, buildNotification(contentText))
    }
}
