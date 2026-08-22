package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

class ClaudeClient(private val prefs: UserPreferences) {

    private val httpClient = CarlsBrainApp.httpClient.newBuilder()
        .callTimeout(60, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()
    private val json = appJson

    /**
     * @param adaptiveThinking lets Claude decide when and how hard to think before answering.
     *   Only for Sonnet 5 and above — Haiku 4.5 predates adaptive thinking and rejects it, and
     *   every cheap background call in this app runs on Haiku, so this defaults to off.
     */
    suspend fun chat(
        messages: List<ApiMessage>,
        systemPrompt: String,
        model: String = HAIKU,
        maxTokens: Int = 1024,
        adaptiveThinking: Boolean = false
    ): Result<String> {
        val apiKey = prefs.anthropicApiKey.first()
        if (apiKey.isBlank()) {
            return Result.failure(Exception("No Anthropic API key — add it in Settings."))
        }

        val requestJson = json.encodeToString(
            MessagesRequest(
                model = model,
                maxTokens = maxTokens,
                system = systemPrompt,
                messages = messages,
                // Null fields are omitted from the JSON entirely (kotlinx does not encode
                // defaults), so a Haiku request looks exactly as it always did.
                thinking = if (adaptiveThinking) ThinkingConfig() else null
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
        /**
         * The cheap, fast model. Everything that runs without Carl asking — auto-tagging a
         * capture, the daily briefing, memory learning, meeting summaries — stays here, because
         * those are frequent, short, and not where thinking quality shows.
         *
         * No date suffix: the bare id is the complete, current identifier.
         */
        const val HAIKU = "claude-haiku-4-5"

        /**
         * What Chat runs on. Carl uses Chat as a thinking tool rather than a capture tool, and
         * Haiku was the ceiling he kept hitting.
         */
        const val SONNET = "claude-sonnet-5"

        /** Reserved for the unleashed chat mode, where the ceiling matters more than the cost. */
        const val OPUS = "claude-opus-5"
    }
}

@Serializable
data class ApiMessage(val role: String, val content: String)

@Serializable
private data class MessagesRequest(
    val model: String,
    @SerialName("max_tokens") val maxTokens: Int,
    val system: String,
    val messages: List<ApiMessage>,
    val thinking: ThinkingConfig? = null
)

/**
 * Adaptive thinking: Claude decides for itself when a question is worth thinking about and how
 * hard, rather than being given a fixed token budget.
 *
 * The older `budget_tokens` form is rejected outright by Sonnet 5 and the Opus 5 family, so this
 * is the only shape worth writing. The reasoning itself is never returned — responses still carry
 * a single text block, which is what [ClaudeClient.chat] reads.
 */
@Serializable
private data class ThinkingConfig(val type: String = "adaptive")

@Serializable
private data class MessagesResponse(val content: List<ContentBlock>)

@Serializable
private data class ContentBlock(val type: String, val text: String = "")
