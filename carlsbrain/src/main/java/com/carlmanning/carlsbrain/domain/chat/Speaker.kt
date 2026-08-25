package com.carlmanning.carlsbrain.domain.chat

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
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
 * ## Audio focus
 *
 * Speech requests transient audio focus and gives it back when the utterance ends. This is not
 * politeness — it is how the phone tells whatever else is playing to get out of the way, and
 * how a car head unit knows to switch source. Without it the reply is technically playing and
 * inaudible, which is exactly what it looks like when it is broken.
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

    private val audioManager =
        context.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

    /** Shared with the device engine, so the two cannot route differently — see [SpeechAudio]. */
    private val attributes = SpeechAudio.ATTRIBUTES

    /**
     * Focus lost outright — a phone call, another assistant taking over. Abandon the utterance
     * rather than talking underneath whatever now owns the output.
     *
     * [release] rather than a completion, deliberately: whatever took focus is in charge now,
     * and handing the microphone back so the wake word starts listening into a phone call is
     * the last thing that should happen.
     *
     * **Only permanent loss.** A transient loss — a navigation prompt, and notably the churn a
     * Bluetooth device can produce while switching profiles — is ridden out instead. Replies
     * are two or three sentences, so the alternative is cancelling most of them for a blip;
     * worse, reacting to transient loss risks this cancelling its own utterance during exactly
     * the car handover this focus handling exists to fix.
     */
    private val focusListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change == AudioManager.AUDIOFOCUS_LOSS) release()
    }

    /**
     * TRANSIENT rather than TRANSIENT_MAY_DUCK: ducked under music in a car this is a mumble,
     * and the whole point is that Carl hears the answer without looking. Music resumes by
     * itself when focus is handed back.
     */
    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener(focusListener)
        .build()

    private val holdingFocus = AtomicBoolean(false)

    /**
     * Takes audio focus for the utterance about to start.
     *
     * A refusal is not fatal — something with a stronger claim is playing, and speaking anyway
     * is better than silently doing nothing — so this reports rather than blocks. It is the
     * *request* that makes the car switch source, not the answer.
     */
    private fun requestFocus() {
        val manager = audioManager ?: return
        if (holdingFocus.compareAndSet(false, true)) {
            runCatching { manager.requestAudioFocus(focusRequest) }
        }
    }

    /** Hands focus back, so music resumes and the car returns to whatever it was doing. */
    private fun abandonFocus() {
        val manager = audioManager ?: return
        if (holdingFocus.compareAndSet(true, false)) {
            runCatching { manager.abandonAudioFocusRequest(focusRequest) }
        }
    }

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
        // Keeps focus across the handover — see releaseInternal.
        releaseInternal(giveBackFocus = false)
        val utterance = Utterance(onDone)
        current = utterance
        // Before either engine starts, and before the network wait: the request is what makes
        // the car switch source, and doing it late means the first words are lost while the
        // head unit catches up.
        requestFocus()

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
                    setAudioAttributes(attributes)
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
        releaseInternal(giveBackFocus = true)
    }

    /**
     * @param giveBackFocus false only when another utterance is starting immediately. Dropping
     *   focus and re-taking it a millisecond later makes a car switch source and back, which is
     *   audible as a gap at the start of the new reply.
     */
    private fun releaseInternal(giveBackFocus: Boolean) {
        val utterance = current ?: run {
            if (giveBackFocus) abandonFocus()
            return
        }
        current = null
        runCatching {
            utterance.player?.apply { if (isPlaying) stop(); release() }
        }
        utterance.player = null
        utterance.finish()
        if (giveBackFocus) abandonFocus()
    }

    private fun complete(utterance: Utterance) {
        runCatching { utterance.player?.release() }
        utterance.player = null
        if (current === utterance) current = null
        // Focus goes back before the callback, not after: the callback restarts the wake word,
        // and holding focus while listening keeps the car parked on this app's source.
        abandonFocus()
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
