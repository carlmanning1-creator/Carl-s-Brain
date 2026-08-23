package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
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
     * @param webTools offers Claude web search and web fetch. These run on Anthropic's own
     *   servers, so there is no tool loop here: the request goes out once and the answer comes
     *   back with the searching already done. Off everywhere except unleashed Chat, because
     *   each search costs money and none of the background calls have any use for the web.
     */
    suspend fun chat(
        messages: List<ApiMessage>,
        systemPrompt: String,
        model: String = HAIKU,
        maxTokens: Int = 1024,
        adaptiveThinking: Boolean = false,
        webTools: Boolean = false
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
                thinking = if (adaptiveThinking) ThinkingConfig("adaptive") else null,
                tools = if (webTools) WEB_TOOLS else null
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
                // Every text block, joined — not just the first.
                //
                // Without tools a response is one text block and the two are the same. With
                // web search it is not: Claude typically says what it is looking for, then
                // the server-tool blocks appear, then the actual answer follows in a second
                // text block. Taking the first would show Carl "Let me look that up" and
                // nothing else, with no error to explain it.
                val text = json.decodeFromString<MessagesResponse>(bodyStr)
                    .content
                    .filter { it.type == "text" }
                    .joinToString("\n\n") { it.text }
                    .trim()
                if (text.isBlank()) error("No text content in response")
                text
            }
        }
    }

    /**
     * One request in a tool-using conversation.
     *
     * Raw JSON rather than the typed [chat] path, because tool use needs message content to be
     * either a string or an array of blocks, and the assistant's blocks must go back verbatim
     * on the next turn — the API matches each tool result to the tool_use block that asked for
     * it, and a reconstructed approximation is rejected.
     *
     * This performs exactly one round trip. The loop that feeds results back lives in
     * ChatViewModel, where it can be bounded and where a failing tool is Carl's problem to see
     * rather than something retried invisibly.
     */
    suspend fun chatTurn(
        messages: JsonArray,
        systemPrompt: String,
        model: String,
        maxTokens: Int,
        tools: JsonArray,
        adaptiveThinking: Boolean = true
    ): Result<ToolTurn> {
        val apiKey = prefs.anthropicApiKey.first()
        if (apiKey.isBlank()) {
            return Result.failure(Exception("No Anthropic API key — add it in Settings."))
        }

        val payload = buildJsonObject {
            put("model", JsonPrimitive(model))
            put("max_tokens", JsonPrimitive(maxTokens))
            put("system", JsonPrimitive(systemPrompt))
            put("messages", messages)
            if (tools.isNotEmpty()) put("tools", tools)
            if (adaptiveThinking) {
                put("thinking", buildJsonObject { put("type", JsonPrimitive("adaptive")) })
            }
        }

        val request = Request.Builder()
            .url("https://api.anthropic.com/v1/messages")
            .addHeader("x-api-key", apiKey)
            .addHeader("anthropic-version", "2023-06-01")
            .post(json.encodeToString(JsonObject.serializer(), payload)
                .toRequestBody("application/json".toMediaType()))
            .build()

        return runCatching {
            withContext(Dispatchers.IO) {
                val bodyStr = httpClient.newCall(request).execute().use { response ->
                    val body = response.body?.string() ?: error("Empty response")
                    if (!response.isSuccessful) error("Claude API ${response.code}: $body")
                    body
                }
                val root = json.parseToJsonElement(bodyStr).jsonObject
                val content = root["content"]?.jsonArray ?: JsonArray(emptyList())

                val text = content.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content == "text") {
                        obj["text"]?.jsonPrimitive?.content
                    } else null
                }.joinToString("\n\n").trim()

                val toolUses = content.mapNotNull { block ->
                    val obj = block.jsonObject
                    if (obj["type"]?.jsonPrimitive?.content != "tool_use") return@mapNotNull null
                    ToolUse(
                        id = obj["id"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        name = obj["name"]?.jsonPrimitive?.content ?: return@mapNotNull null,
                        input = obj["input"]?.jsonObject ?: JsonObject(emptyMap())
                    )
                }

                ToolTurn(
                    content = content,
                    text = text,
                    toolUses = toolUses,
                    stopReason = root["stop_reason"]?.jsonPrimitive?.content.orEmpty()
                )
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

        /** Unleashed chat, where the ceiling matters more than the cost. */
        const val OPUS = "claude-opus-5"

        /**
         * Web search and fetch, run server-side by Anthropic.
         *
         * The dated `_20260209` versions filter results before they reach the context window.
         * The standalone code-execution tool is deliberately absent: it creates a second
         * execution environment competing with the one dynamic filtering already uses.
         */
        private val WEB_TOOLS = listOf(
            ServerTool(type = "web_search_20260209", name = "web_search"),
            ServerTool(type = "web_fetch_20260209", name = "web_fetch")
        )
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
    val thinking: ThinkingConfig? = null,
    val tools: List<ServerTool>? = null
)

/**
 * A server-side tool. Only `type` and `name` are needed — these run on Anthropic's servers,
 * so there is no schema for us to declare and no result for us to return.
 */
@Serializable
data class ServerTool(val type: String, val name: String)

/**
 * Adaptive thinking: Claude decides for itself when a question is worth thinking about and how
 * hard, rather than being given a fixed token budget.
 *
 * The older `budget_tokens` form is rejected outright by Sonnet 5 and the Opus 5 family, so this
 * is the only shape worth writing. The reasoning itself is never returned — responses still carry
 * a single text block, which is what [ClaudeClient.chat] reads.
 */
@Serializable
private data class ThinkingConfig(
    /**
     * No default value, deliberately.
     *
     * `appJson` leaves `encodeDefaults` off, so a property equal to its default is omitted from
     * the JSON entirely. With `type: String = "adaptive"` this class serialised as `{}` and
     * every Chat message came back as a 400 — "thinking.type: Field required". The same rule is
     * relied on one field up, where a null `thinking` is omitted rather than sent as null; it
     * cuts both ways, and a required field must never carry a default.
     */
    val type: String
)

@Serializable
private data class MessagesResponse(val content: List<ContentBlock>)

@Serializable
private data class ContentBlock(val type: String, val text: String = "")

/**
 * One turn of a tool-using conversation.
 *
 * @param content the assistant's content blocks, verbatim. They go back into the message list
 *   unchanged on the next turn — reconstructing them from [text] would drop the tool_use blocks
 *   the API needs to match results against, and the request would be rejected.
 * @param text every text block joined, for display.
 * @param toolUses the tools Claude wants run, in order.
 * @param stopReason "tool_use" while it still wants something, "end_turn" when it is finished.
 */
data class ToolTurn(
    val content: JsonArray,
    val text: String,
    val toolUses: List<ToolUse>,
    val stopReason: String
)

data class ToolUse(
    val id: String,
    val name: String,
    val input: JsonObject
)
