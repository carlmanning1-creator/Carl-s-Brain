package com.carlmanning.carlsbrain.domain.chat

import android.media.AudioAttributes

/**
 * How this app's spoken replies describe themselves to the audio system.
 *
 * One definition, shared by both engines. [Speaker] applies it to the OpenAI MediaPlayer, and
 * both TextToSpeech instances apply it to the on-device engine — because two engines routing
 * differently produces the worst kind of fault to diagnose: audible in the car through one and
 * silent through the other, with nothing in the app to say which one spoke.
 *
 * ## Why USAGE_MEDIA rather than USAGE_ASSISTANT
 *
 * `USAGE_ASSISTANT` is the more precise label, and was the original choice. Car head units
 * route it inconsistently, though — some treat it as a notification-class stream and play it
 * quietly, or not at all, while sitting on a different source. `USAGE_MEDIA` is the one every
 * car plays, and being heard while driving matters more here than being semantically exact.
 *
 * `CONTENT_TYPE_SPEECH` is kept either way: it tells the system this is a voice, not music, so
 * music-oriented processing is not applied to it.
 */
object SpeechAudio {

    val ATTRIBUTES: AudioAttributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()
}
