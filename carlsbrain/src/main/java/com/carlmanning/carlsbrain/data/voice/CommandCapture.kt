package com.carlmanning.carlsbrain.data.voice

import com.k2fsa.sherpa.onnx.Vad

/**
 * Records one spoken command from audio the wake-word loop is already reading.
 *
 * This exists so the microphone never changes hands between "Hey Brain" and the command. The
 * old path closed the AudioRecord, spoke a greeting, and asked Google's recogniser — another
 * process — to open the microphone again. Anything Carl said in that gap was lost, and every
 * way the handover could go wrong was a way for the wake word to end up dead.
 *
 * Not thread-safe: built, fed and discarded on the audio thread only.
 *
 * @param vad shared with the loop that owns it; reset here, never released here.
 * @param noSpeechTimeoutMs how long to wait for speech to *start*. Short after a wake word, so
 *   a false trigger costs a beep and a few seconds rather than a conversation.
 * @param preRoll audio from just before the trigger, placed at the front of what is sent for
 *   transcription. Never shown to the VAD: it contains the wake phrase itself, which would
 *   count as speech and end the capture before Carl had said anything.
 * @param skipVadMs audio at the start that is recorded but not judged — the start tone plays
 *   through the phone's own speaker into this microphone.
 */
class CommandCapture(
    private val vad: Vad,
    private val noSpeechTimeoutMs: Long,
    preRoll: ShortArray? = null,
    private val skipVadMs: Long = 0
) {
    sealed class Result {
        /** Speech was heard and has ended (or the cap was reached). */
        class Speech(val pcm: ShortArray) : Result()
        /** Nobody spoke. */
        object NoSpeech : Result()
        /** The microphone was lost before the capture could finish. */
        object Failed : Result()
    }

    companion object {
        const val SAMPLE_RATE = 16000
        /** Quiet after speech before the command is taken as finished, on top of the VAD's own. */
        private const val TRAILING_SILENCE_MS = 1_000L
        private const val MAX_MS = 60_000L
        /** Passed to the VAD as its own end-of-segment silence. */
        const val VAD_MIN_SILENCE_SEC = 0.5f
    }

    private val audio = PcmAccumulator()
    private var liveSamples = 0L
    private var speechSeen = false
    private var lastSpeechAt = 0L

    init {
        runCatching { vad.reset() }
        runCatching { vad.clear() }
        if (preRoll != null && preRoll.isNotEmpty()) audio.append(preRoll, preRoll.size)
    }

    private fun ms(samples: Long) = samples * 1000 / SAMPLE_RATE

    /** @return null to keep going, or the outcome once the capture is over. */
    fun feed(buffer: ShortArray, count: Int): Result? {
        if (count <= 0) return null
        audio.append(buffer, count)
        liveSamples += count

        if (ms(liveSamples) > skipVadMs) {
            val floats = FloatArray(count) { buffer[it] / 32768.0f }
            vad.acceptWaveform(floats)
            // Finished segments are not used — only the live "is anyone speaking" answer is —
            // but they queue up inside the detector until popped.
            while (!vad.empty()) vad.pop()
            if (vad.isSpeechDetected()) {
                speechSeen = true
                lastSpeechAt = liveSamples
            }
        }

        val elapsed = ms(liveSamples)
        return when {
            !speechSeen && elapsed >= noSpeechTimeoutMs -> Result.NoSpeech
            speechSeen && ms(liveSamples - lastSpeechAt) >= TRAILING_SILENCE_MS ->
                Result.Speech(audio.toArray())
            elapsed >= MAX_MS -> if (speechSeen) Result.Speech(audio.toArray()) else Result.NoSpeech
            else -> null
        }
    }
}
