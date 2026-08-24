package com.carlmanning.carlsbrain.domain.chat

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.remote.OpenAiSpeechClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Says something out loud, using the best engine available and always finishing.
 *
 * Two engines sit behind one call. OpenAI when it is switched on, there is a key, and the
 * network cooperates; Android's on-device engine otherwise. The caller does not choose, and
 * does not need to know which one spoke.
 *
 * ## The contract that matters
 *
 * **The completion callback fires at most once, and always once unless the utterance is
 * deliberately abandoned.** Never twice, and never zero times because a network call hung.
 *
 * In the voice service that callback is what hands the microphone back to the wake word. A path
 * that loses it leaves "Hey Brain" dead until the app is restarted, silently; a path that fires
 * it twice starts the recogniser on top of itself. Both failures are invisible until Carl needs
 * the thing and it is not there.
 *
 * The single exception is [release], which stops speech and abandons the callback on purpose —
 * teardown, the speaker being switched off, Carl reaching for the microphone. In every one of
 * those the continuation is unwanted; see [release].
 *
 * The guard is an AtomicBoolean rather than trust in the control flow, because MediaPlayer can
 * deliver completion and error for the same playback, and a network response can land at the
 * same moment as an abandonment.
 *
 * ## Falling back is normal
 *
 * No key, no signal, a slow response, a malformed file — all of them fall through to the device
 * engine rather than surfacing an error. Carl in the car does not care which engine spoke; he
 * cares that something did. The only thing worth reporting is silence, and silence is what this
 * is built to prevent.
 */
class Speaker(
    private val context: Context,
    private val scope: CoroutineScope,
    /** Speaks [text] on the device engine, invoking the callback when it finishes or fails. */
    private val deviceEngine: (text: String, onDone: () -> Unit) -> Unit
) {

    private val speechClient = OpenAiSpeechClient(context, CarlsBrainApp.userPreferences)
    private var current: Utterance? = null

    /**
     * One utterance in flight.
     *
     * Holds the player so [release] can cut it off mid-sentence, and the guard that keeps
     * [finish] to a single call.
     */
    private class Utterance(val onDone: () -> Unit) {
        var player: MediaPlayer? = null
        private val done = AtomicBoolean(false)

        /** @return true if this call is the one that finished the utterance. */
        fun finish(): Boolean = done.compareAndSet(false, true)
    }

    /**
     * Speaks [rawText], stripping markup first.
     *
     * @param onDone invoked exactly once when speech ends normally or fails. NOT invoked when the
     *   utterance is cut short by [release] or superseded by another [speak] — see [release].
     */
    fun speak(rawText: String, onDone: () -> Unit) {
        val text = SpeechText.forSpeaking(rawText).ifBlank { rawText }
        if (text.isBlank()) {
            onDone()
            return
        }

        // release(), not stop(): a new utterance supersedes the old one, and the old one's
        // continuation is no longer wanted. Firing it would hand the microphone back — starting
        // the recogniser — while the new reply is still being spoken.
        release()
        val utterance = Utterance(onDone)
        current = utterance

        scope.launch {
            val prefs = CarlsBrainApp.userPreferences
            val enabled = runCatching { prefs.openAiVoiceEnabled.first() }.getOrDefault(false)
            if (!enabled) {
                fallBack(utterance, text)
                return@launch
            }

            val voice = runCatching { prefs.openAiVoice.first() }
                .getOrDefault(OpenAiSpeechClient.DEFAULT_VOICE)
            val file = speechClient.synthesise(
                text = text,
                voice = voice,
                instructions = OpenAiSpeechClient.DEFAULT_INSTRUCTIONS
            ).getOrNull()

            // Interrupted while the request was in flight — Carl started talking again, or the
            // conversation ended. Do not start playing something he has moved on from; finish()
            // in stop() has already fired the callback.
            if (current !== utterance) return@launch

            if (file == null) {
                fallBack(utterance, text)
                return@launch
            }

            // Built outside the apply so a throw from prepare() or start() still has something
            // to release. Assigning at the end of the block would leak the player on failure —
            // and MediaPlayer holds a codec, which is a scarce, process-wide resource.
            val player = MediaPlayer()
            val started = runCatching {
                player.apply {
                    setAudioAttributes(
                        AudioAttributes.Builder()
                            .setUsage(AudioAttributes.USAGE_ASSISTANT)
                            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                            .build()
                    )
                    setDataSource(file.absolutePath)
                    setOnCompletionListener { complete(utterance) }
                    // Returning true marks the error handled; either way the utterance must end,
                    // and MediaPlayer will not call onCompletion after an error.
                    setOnErrorListener { _, _, _ -> complete(utterance); true }
                    prepare()
                    start()
                }
                utterance.player = player
            }.onFailure {
                runCatching { player.release() }
            }.isSuccess

            // A file that will not play is the same problem as no file. Do not leave Carl with
            // silence just because the bytes arrived.
            if (!started) fallBack(utterance, text)
        }
    }

    /**
     * Stops playback and **abandons** the callback.
     *
     * Abandoning rather than firing is the deliberate choice, and it is why there is no
     * "stop and complete" counterpart. Every caller here is cutting an utterance short —
     * teardown, the speaker being switched off, Carl reaching for the microphone — and in each
     * case the continuation is no longer wanted. Firing it would call
     * startServiceSpeechRecognition, which has no conversation guard of its own, and open the
     * microphone on a service that is going away or a screen Carl has left.
     *
     * If a barge-in feature ever wants "interrupted, now carry on", that is a new method with
     * its own callers, not a change to this one.
     *
     * Marks the utterance finished so a completion already in flight cannot fire it either, and
     * is safe to call when nothing is speaking.
     */
    fun release() {
        val utterance = current ?: return
        current = null
        runCatching {
            utterance.player?.apply { if (isPlaying) stop(); release() }
        }
        utterance.player = null
        utterance.finish()
    }

    private fun complete(utterance: Utterance) {
        runCatching { utterance.player?.release() }
        utterance.player = null
        if (current === utterance) current = null
        if (utterance.finish()) utterance.onDone()
    }

    /**
     * Hands the utterance to the device engine.
     *
     * The guard is checked first: if the utterance has already finished — interrupted while the
     * network request was in flight — this must not start a second one speaking.
     */
    private fun fallBack(utterance: Utterance, text: String) {
        if (current !== utterance) return
        deviceEngine(text) { complete(utterance) }
    }
}
