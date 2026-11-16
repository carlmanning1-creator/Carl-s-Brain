package com.carlmanning.carlsbrain.data.voice

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.core.content.ContextCompat
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.remote.WhisperClient
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * Tap-to-talk for the in-app microphone buttons: records with our own AudioRecord, ends on
 * silence with the Silero VAD, and transcribes with OpenAI — the same path "Hey Brain" uses for
 * its command, so a spoken note sounds the same wherever it was said.
 *
 * These paths run with the app on screen, where [MicCoordinator]'s visibility rule has already
 * stood the wake word and the ambient buffer down. A meeting still owns the microphone
 * outright, so [record] refuses while one is recording.
 *
 * The AudioRecord and the Vad are created inside [record] and released in its `finally`, so
 * every way out — speech end, no speech, [finish], failure, or the caller's coroutine being
 * cancelled by back-navigation or `onCleared` — lets go of the microphone.
 *
 * One recording at a time per instance; callers own the sequencing.
 *
 * @param owner name used in [MicCoordinator]'s event history.
 */
class TapToTalkRecorder(context: Context, private val owner: String) {

    private val appContext = context.applicationContext

    sealed class Recording {
        class Speech(val pcm: ShortArray) : Recording()
        object NoSpeech : Recording()
        /** A meeting holds the microphone. */
        class Busy(val message: String) : Recording()
        class Failed(val message: String) : Recording()
    }

    companion object {
        const val SAMPLE_RATE = CommandCapture.SAMPLE_RATE
        /** Longer than after a wake word: Carl tapped the button deliberately and may pause. */
        const val NO_SPEECH_MS = 6_000L
        /** Matches the service: the start tone plays into this same microphone. */
        const val TONE_SKIP_MS = 300L
        /** 100 ms reads, as the wake-word loop uses. */
        private const val FRAME_SAMPLES = 1_600
        private const val MAX_READ_ERRORS = 5

        /**
         * Null when the OpenAI path can be used; otherwise why the caller should use the
         * on-device recogniser instead. Decided *before* recording, because a recording cannot
         * be handed to the recogniser afterwards. Touches disk (the VAD asset), so call it off
         * the main thread or from a coroutine — it switches to IO itself.
         */
        suspend fun fallbackReason(context: Context): String? = withContext(Dispatchers.IO) {
            if (CarlsBrainApp.userPreferences.openaiApiKey.first().isBlank()) return@withContext "no OpenAI key"
            if (!isOnline(context)) return@withContext "offline"
            if (WakeWordModel.prepareVad(context.applicationContext) == null) return@withContext "no voice-activity model"
            null
        }

        /**
         * True when the active network claims internet. If the check itself is not permitted,
         * assume online: the transcription will then fail honestly rather than silently
         * routing everything to the recogniser.
         */
        fun isOnline(context: Context): Boolean = runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching true
            val network = cm.activeNetwork ?: return@runCatching false
            val caps = cm.getNetworkCapabilities(network) ?: return@runCatching false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }.getOrDefault(true)
    }

    private val _level = MutableStateFlow(0f)
    /** Live input level, 0..1, for a level indicator. 0 when not recording. */
    val level: StateFlow<Float> = _level.asStateFlow()

    @Volatile private var finishRequested = false

    /** Ends the current recording now, keeping what was said. Safe to call at any time. */
    fun finish() {
        finishRequested = true
    }

    /**
     * Records one utterance. Suspends until speech ends, nobody speaks for [NO_SPEECH_MS],
     * [finish] is called, or the microphone fails. Cancellation releases the microphone.
     */
    suspend fun record(skipVadMs: Long = 0): Recording = withContext(Dispatchers.IO) {
        finishRequested = false
        if (MicCoordinator.recordingActive()) {
            MicCoordinator.note(owner, "refused — a meeting is recording")
            return@withContext Recording.Busy("A meeting is recording — the microphone is in use.")
        }
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext Recording.Failed("Microphone permission is needed.")
        }
        val vadPath = WakeWordModel.prepareVad(appContext)
            ?: return@withContext Recording.Failed("Voice detection is unavailable.")

        var vad: Vad? = null
        var record: AudioRecord? = null
        var opened = false
        try {
            // Same configuration as VoiceCaptureService, so speech ends the same way.
            val v = Vad(
                assetManager = null,
                config = VadModelConfig(
                    sileroVadModelConfig = SileroVadModelConfig(
                        model = vadPath,
                        threshold = 0.5f,
                        minSilenceDuration = CommandCapture.VAD_MIN_SILENCE_SEC,
                        minSpeechDuration = 0.25f,
                        windowSize = 512,
                        maxSpeechDuration = 120f
                    ),
                    sampleRate = SAMPLE_RATE,
                    numThreads = 1,
                    provider = "cpu"
                )
            )
            vad = v

            val minBuffer = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            if (minBuffer <= 0) {
                MicCoordinator.fail(owner, "no usable buffer size ($minBuffer)")
                return@withContext Recording.Failed("The microphone isn't available.")
            }
            val r = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                maxOf(minBuffer, FRAME_SAMPLES * 2 * 4)
            )
            record = r
            if (r.state != AudioRecord.STATE_INITIALIZED) {
                MicCoordinator.fail(owner, "AudioRecord did not initialise")
                return@withContext Recording.Failed("The microphone isn't available.")
            }
            r.startRecording()
            opened = true
            if (r.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                MicCoordinator.fail(owner, "AudioRecord did not start recording")
                return@withContext Recording.Failed("The microphone is in use by something else.")
            }
            MicCoordinator.note(owner, "microphone opened")

            val capture = CommandCapture(v, NO_SPEECH_MS, skipVadMs = skipVadMs)
            // A copy of everything heard, for an early [finish]. CommandCapture keeps its own
            // and does not expose it until it decides the capture is over.
            val heard = PcmAccumulator()
            var heardSpeech = false
            val buffer = ShortArray(FRAME_SAMPLES)
            var readErrors = 0
            var outcome: Recording? = null
            while (outcome == null) {
                ensureActive()
                if (finishRequested) {
                    outcome = if (heardSpeech) Recording.Speech(heard.toArray()) else Recording.NoSpeech
                    break
                }
                val n = r.read(buffer, 0, buffer.size)
                if (n <= 0) {
                    if (++readErrors >= MAX_READ_ERRORS) {
                        MicCoordinator.fail(owner, "microphone lost while recording (read=$n)")
                        outcome = Recording.Failed("Lost the microphone while recording.")
                    }
                    continue
                }
                readErrors = 0
                _level.value = levelOf(buffer, n)
                heard.append(buffer, n)
                val result = capture.feed(buffer, n)
                if (v.isSpeechDetected()) heardSpeech = true
                outcome = when (result) {
                    null -> null
                    is CommandCapture.Result.Speech -> Recording.Speech(result.pcm)
                    CommandCapture.Result.NoSpeech -> Recording.NoSpeech
                    CommandCapture.Result.Failed -> {
                        MicCoordinator.fail(owner, "capture failed")
                        Recording.Failed("Lost the microphone while recording.")
                    }
                }
            }
            outcome ?: Recording.NoSpeech
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            MicCoordinator.fail(owner, "recording failed: ${e.javaClass.simpleName}: ${e.message}")
            Recording.Failed("The microphone isn't available.")
        } finally {
            runCatching { if (opened) record?.stop() }
            runCatching { record?.release() }
            runCatching { vad?.release() }
            _level.value = 0f
            if (opened) MicCoordinator.note(owner, "microphone released")
        }
    }

    /** Sends a recording to OpenAI with the shared, vault-free vocabulary. */
    suspend fun transcribe(pcm: ShortArray): Result<String> {
        val vocabulary = Vocabulary.forTranscription(AppDatabase.getInstance(appContext))
        return WhisperClient(CarlsBrainApp.userPreferences)
            .transcribeCommand(pcm, SAMPLE_RATE, vocabulary)
    }

    /** RMS mapped from -50..-10 dBFS onto 0..1 — speech sits in the upper half. */
    private fun levelOf(buffer: ShortArray, count: Int): Float {
        var sum = 0.0
        for (i in 0 until count) {
            val s = buffer[i].toDouble()
            sum += s * s
        }
        val rms = sqrt(sum / count) / 32768.0
        if (rms <= 0.0) return 0f
        val db = 20 * log10(rms)
        return ((db + 50) / 40).toFloat().coerceIn(0f, 1f)
    }
}
