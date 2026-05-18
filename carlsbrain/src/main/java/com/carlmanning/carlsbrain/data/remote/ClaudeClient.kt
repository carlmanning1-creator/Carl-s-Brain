package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ClaudeClient(private val prefs: UserPreferences) {

    private val httpClient = OkHttpClient.Builder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = appJson

    suspend fun chat(
        messages: List<ApiMessage>,
        systemPrompt: String,
        model: String = HAIKU
    ): Result<String> {
        val apiKey = prefs.anthropicApiKey.first()
        if (apiKey.isBlank()) {
            return Result.failure(Exception("No Anthropic API key — add it in Settings."))
        }

        val requestJson = json.encodeToString(
            MessagesRequest(
                model = model,
                maxTokens = 1024,
                system = systemPrompt,
                messages = messages
            )
        )

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(requestJson.toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            withContext(Dispatchers.IO) {
                val response = httpClient.newCall(request).execute()
                val bodyStr = response.body?.string() ?: error("Empty response")
                if (!response.isSuccessful) error("Claude API ${response.code}: $bodyStr")
                json.decodeFromString<MessagesResponse>(bodyStr)
                    .content
                    .firstOrNull { it.type == "text" }
                    ?.text
                    ?: error("No text content in response")
            }
        }
    }

    companion object {
        const val HAIKU = "claude-haiku-4-5-20251001"
        const val SONNET = "claude-sonnet-4-6"
    }
}

@Serializable
data class ApiMessage(val role: String, val content: String)

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ApiMessage>
)

@Serializable
private data class MessagesResponse(val content: List<ContentBlock>)

@Serializable
private data class ContentBlock(val type: String, val text: String = "")
