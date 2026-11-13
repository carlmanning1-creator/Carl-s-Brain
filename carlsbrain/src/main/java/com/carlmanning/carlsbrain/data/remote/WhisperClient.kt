package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.Request
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.util.concurrent.TimeUnit

class WhisperClient(private val prefs: UserPreferences) {

    private val httpClient = CarlsBrainApp.httpClient.newBuilder()
        .callTimeout(10, TimeUnit.MINUTES)
        .readTimeout(10, TimeUnit.MINUTES)
        .build()

    suspend fun transcribe(audioFile: File): Result<String> {
        val apiKey = prefs.openaiApiKey.first()
        if (apiKey.isBlank()) return Result.failure(Exception("No OpenAI API key configured"))

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("model", "whisper-1")
            .addFormDataPart(
                "file", audioFile.name,
                audioFile.asRequestBody("audio/m4a".toMediaType())
            )
            .addFormDataPart("response_format", "text")
            .build()

        val request = Request.Builder()
            .url("https://api.openai.com/v1/audio/transcriptions")
            .addHeader("Authorization", "Bearer $apiKey")
            .post(requestBody)
            .build()

        return runCatching {
            withContext(Dispatchers.IO) {
                // use {} — the response was never closed, on the one client with a ten-minute
                // read timeout, so every transcription held a socket open for up to ten minutes
                // whether it succeeded or threw. FirefliesRepository fixed exactly this and
                // Whisper was missed.
                httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string()?.trim() ?: error("Empty Whisper response")
                    if (!response.isSuccessful) error("Whisper API ${response.code}: $body")
                    body  // response_format=text returns plain text directly
                }
            }
        }
    }

    /**
     * Transcribes a short spoken command held in memory — the wake-word path.
     *
     * Separate from [transcribe] because everything about it is different: seconds of audio
     * rather than an hour, a caller who is standing there waiting, and a timeout measured
     * accordingly. A meeting can wait ten minutes for Whisper; Carl in the car cannot.
     *
     * Tries `gpt-4o-transcribe` and steps down to `whisper-1` only when the API says the model
     * itself is the problem. A timeout or a dropped connection fails at once — trying a second
     * model over a link that just failed doubles the silence for nothing.
     *
     * @param vocabulary names and terms the recogniser should prefer. Must never contain a
     *   vault bucket name: this string leaves the device.
     */
    suspend fun transcribeCommand(pcm: ShortArray, sampleRate: Int, vocabulary: String): Result<String> {
        val apiKey = prefs.openaiApiKey.first()
        if (apiKey.isBlank()) return Result.failure(Exception("No OpenAI API key configured"))
        val wav = com.carlmanning.carlsbrain.data.voice.Wav.encode(pcm, sampleRate)
        // Upload time scales with length; the floor covers the model's own thinking.
        val seconds = pcm.size / sampleRate
        val client = CarlsBrainApp.httpClient.newBuilder()
            .callTimeout(8L + seconds / 4, TimeUnit.SECONDS)
            .build()

        suspend fun attempt(model: String): Pair<Int, String> = withContext(Dispatchers.IO) {
            val body = MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", model)
                .addFormDataPart("language", "en")
                .addFormDataPart("response_format", "json")
                .apply { if (vocabulary.isNotBlank()) addFormDataPart("prompt", vocabulary) }
                .addFormDataPart(
                    "file", "command.wav",
                    wav.toRequestBody("audio/wav".toMediaType())
                )
                .build()
            val request = Request.Builder()
                .url("https://api.openai.com/v1/audio/transcriptions")
                .addHeader("Authorization", "Bearer $apiKey")
                .post(body)
                .build()
            client.newCall(request).execute().use { response ->
                response.code to (response.body?.string().orEmpty())
            }
        }

        return runCatching {
            var (code, body) = attempt("gpt-4o-transcribe")
            // 400/403/404 here mean "not this model for this key", not "bad audio" — the same
            // audio is about to be accepted by whisper-1.
            if (code == 400 || code == 403 || code == 404) {
                val second = attempt("whisper-1")
                code = second.first
                body = second.second
            }
            if (code !in 200..299) error("Transcription API $code: ${body.take(200)}")
            org.json.JSONObject(body).optString("text").trim()
        }
    }
}
