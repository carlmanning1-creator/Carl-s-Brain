package com.carlmanning.carlsbrain.data.remote

import android.content.Context
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Speech from OpenAI, so the app sounds like a person rather than a screen reader.
 *
 * Uses the OpenAI key that is already in Settings for Whisper — no second account, no second
 * subscription. At Carl's volume this is cents a month.
 *
 * ## Why a file rather than a stream
 *
 * The response is written to the cache and played with MediaPlayer. Streaming PCM into an
 * AudioTrack would start sooner, but it means owning sample-rate handling, a playback thread,
 * and stop/flush semantics — three more places for the "speaking finished" callback to be lost,
 * and that callback is what hands the microphone back to the wake word. For the two or three
 * sentences this app now aims at, generation is a second or so.
 *
 * ## Failure is normal, and must be cheap
 *
 * Every failure — no key, no signal, a slow response, a bad status — returns a failure Result
 * rather than throwing, because the caller's job is to fall back to the on-device engine and
 * keep talking. Carl in the car does not care which engine spoke; he cares that something did.
 */
class OpenAiSpeechClient(
    private val context: Context,
    private val prefs: UserPreferences
) {

    /**
     * A tight ceiling, deliberately. This sits between Carl finishing a sentence and hearing a
     * reply, so waiting is worse than a plainer voice: past this we abandon the request and let
     * the on-device engine speak immediately.
     */
    private val httpClient = CarlsBrainApp.httpClient.newBuilder()
        .callTimeout(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(SYNTHESIS_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * Renders [text] to an audio file.
     *
     * @param voice one of [VOICES].
     * @param instructions how it should be delivered — the thing that separates this from a
     *   read-aloud. Ignored by older models, honoured by gpt-4o-mini-tts.
     * @return the audio file, or a failure the caller should treat as "use the device engine".
     */
    suspend fun synthesise(
        text: String,
        voice: String,
        instructions: String
    ): Result<File> {
        if (text.isBlank()) return Result.failure(IllegalArgumentException("Nothing to speak"))
        val apiKey = prefs.openaiApiKey.first()
        if (apiKey.isBlank()) return Result.failure(Exception("No OpenAI API key configured"))

        val payload = appJson.encodeToString(
            SpeechRequest(
                model = MODEL,
                input = text.take(MAX_CHARS),
                voice = voice.ifBlank { DEFAULT_VOICE },
                instructions = instructions.ifBlank { null }
            )
        )

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/speech")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(payload.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            withContext(Dispatchers.IO) {
                // A fixed filename, overwritten each time: only one utterance plays at once, and
                // a per-utterance name would quietly fill the cache with audio nothing reads.
                val out = File(cacheDir(), "speech.mp3")
                httpClient.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        val detail = response.body?.string()?.take(300).orEmpty()
                        error("OpenAI speech ${response.code}: $detail")
                    }
                    val body = response.body ?: error("Empty speech response")
                    out.outputStream().use { sink -> body.byteStream().copyTo(sink) }
                }
                // A zero-byte file plays as silence, which is indistinguishable from the app
                // having ignored Carl. Treat it as a failure so the device engine speaks.
                if (out.length() == 0L) error("Speech response was empty")
                out
            }
        }
    }

    private fun cacheDir(): File =
        File(context.cacheDir, "speech").apply { mkdirs() }

    @Serializable
    private data class SpeechRequest(
        val model: String,
        val input: String,
        val voice: String,
        // Omitted when null: kotlinx does not encode defaults, and an empty instruction string
        // is not the same as no instruction.
        val instructions: String? = null,
        @kotlinx.serialization.SerialName("response_format")
        val responseFormat: String = RESPONSE_FORMAT
    )

    companion object {
        /**
         * The steerable speech model — it takes an `instructions` string describing *how* to
         * speak, which is most of the difference between a voice that sounds like a person and
         * one that sounds like a station announcement.
         */
        const val MODEL = "gpt-4o-mini-tts"

        /**
         * Explicit rather than defaulted, for the same reason ThinkingConfig.type is: a property
         * equal to its default is omitted from the JSON entirely, and this one is not optional.
         */
        private const val RESPONSE_FORMAT = "mp3"

        private const val SYNTHESIS_TIMEOUT_SECONDS = 12L

        /** The API's own input ceiling. Replies are far shorter, but a runaway must not 400. */
        private const val MAX_CHARS = 4096

        /**
         * Carl's default. Warm and unhurried — it has to be bearable at 6:30 in the morning,
         * which rules out the brighter voices however good they sound in a demo.
         */
        const val DEFAULT_VOICE = "sage"

        /** Everything Settings offers. Ordered roughly warm to neutral. */
        val VOICES = listOf(
            "sage" to "Sage — warm, calm (default)",
            "shimmer" to "Shimmer — gentle, soft",
            "coral" to "Coral — bright, friendly",
            "alloy" to "Alloy — neutral, even",
            "echo" to "Echo — steady, measured",
            "ballad" to "Ballad — expressive",
            "ash" to "Ash — low, unhurried",
            "verse" to "Verse — conversational"
        )

        /**
         * How to speak, not what to say.
         *
         * Written for the room rather than for a demo: this is a second brain talking to someone
         * with ADHD, often first thing in the morning or hands-free in a car. Calm and certain
         * beats performed warmth, and pace matters more than character.
         */
        const val DEFAULT_INSTRUCTIONS =
            "Speak warmly and calmly, like a friend who knows Carl well and is not in a hurry. " +
                "Natural conversational pace — unhurried but not slow. Let sentences breathe. " +
                "Stay even and reassuring rather than bright or performative, and never sound " +
                "like you are reading aloud from a page."
    }
}
