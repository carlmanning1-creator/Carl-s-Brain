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
}
