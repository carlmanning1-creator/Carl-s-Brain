package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

private val firefliesJson = Json { ignoreUnknownKeys = true }

@Serializable
data class FirefliesSummary(
    val overview: String? = null,
    @SerialName("action_items") val actionItems: String? = null,
    val keywords: String? = null
)

@Serializable
data class FirefliesTranscript(
    val id: String,
    val title: String? = null,
    val date: Long? = null,
    val duration: Long? = null,
    val summary: FirefliesSummary? = null,
    val sentences: List<FirefliesSentence>? = null
)

@Serializable
data class FirefliesSentence(
    val text: String? = null,
    @SerialName("speaker_name") val speakerName: String? = null
)

class FirefliesRepository {

    private val client = CarlsBrainApp.httpClient

    private val endpoint = "https://api.fireflies.ai/graphql"
    private val mediaType = "application/json; charset=utf-8".toMediaType()

    /**
     * Every network call goes through here, on IO, with the response closed.
     *
     * Confined in the repository rather than left to callers: `execute()` is blocking, and the
     * Settings connection test called it from viewModelScope — which is the main thread. That
     * throws NetworkOnMainThreadException, whose message is null, so the diagnostic built to
     * explain Fireflies failures reported "Failed, with no message" and never reached the
     * network at all. The two older methods only escaped it by luck: their callers happened to
     * be a CoroutineWorker and appScope.
     *
     * Also closes the response body, which none of the three did — every call leaked a
     * connection.
     */
    private suspend fun post(apiKey: String, body: String): Pair<Int, String> =
        withContext(Dispatchers.IO) {
            val request = Request.Builder()
                .url(endpoint)
                .addHeader("Authorization", "Bearer $apiKey")
                .addHeader("Content-Type", "application/json")
                .post(body.toRequestBody(mediaType))
                .build()
            client.newCall(request).execute().use { response ->
                response.code to (response.body?.string() ?: "")
            }
        }

    /**
     * Turns a GraphQL response body into the error it is reporting, or null if it is clean.
     *
     * GraphQL does not use HTTP status codes for application-level failures. An unknown input
     * field, a rejected argument, an expired key or a plan restriction all come back as
     * **HTTP 200 with an `errors` array and `data: null`**. Checking only `isSuccessful`
     * therefore walks straight past every one of them.
     *
     * That is not hypothetical: it is why a meeting could come back titled but with no
     * transcript, no summary and no action items, and no trace anywhere of what went wrong.
     * Fireflies had refused the upload and said why, and the app discarded the message.
     */
    private fun graphQlError(root: JsonObject): String? {
        val errors = root["errors"]?.jsonArray ?: return null
        if (errors.isEmpty()) return null
        return errors.joinToString("; ") { el ->
            el.jsonObject["message"]?.jsonPrimitive?.content ?: el.toString()
        }
    }

    suspend fun getRecentTranscripts(apiKey: String, limit: Int = 25): Result<List<FirefliesTranscript>> {
        val query = """
            {
              "query": "{ transcripts(limit: $limit) { id title date duration summary { overview action_items keywords } sentences { text speaker_name } } }"
            }
        """.trimIndent()

        return runCatching {
            val (code, body) = post(apiKey, query)
            if (body.isBlank()) error("Empty response from Fireflies")
            if (code !in 200..299) error("Fireflies API error $code: ${body.take(300)}")

            val root = firefliesJson.parseToJsonElement(body).jsonObject
            graphQlError(root)?.let { error("Fireflies: $it") }
            val transcriptsArray = root["data"]?.jsonObject?.get("transcripts")?.jsonArray
                ?: error("Missing transcripts in Fireflies response")

            transcriptsArray.map { el ->
                val obj = el.jsonObject
                val summaryObj = obj["summary"]?.jsonObject
                val sentencesList = obj["sentences"]?.jsonArray?.mapNotNull { s ->
                    val so = s.jsonObject
                    FirefliesSentence(
                        text = so["text"]?.jsonPrimitive?.content,
                        speakerName = so["speaker_name"]?.jsonPrimitive?.content
                    )
                }
                FirefliesTranscript(
                    id = obj["id"]!!.jsonPrimitive.content,
                    title = obj["title"]?.jsonPrimitive?.content,
                    date = obj["date"]?.jsonPrimitive?.longOrNull,
                    duration = obj["duration"]?.jsonPrimitive?.longOrNull,
                    summary = summaryObj?.let {
                        FirefliesSummary(
                            overview = it["overview"]?.jsonPrimitive?.content,
                            actionItems = it["action_items"]?.jsonPrimitive?.content,
                            keywords = it["keywords"]?.jsonPrimitive?.content
                        )
                    },
                    sentences = sentencesList
                )
            }
        }
    }

    /**
     * Hands Fireflies a URL to fetch and transcribe.
     *
     * Returns [Result.failure] with Fireflies' own wording on any rejection, rather than a bare
     * `false`. The caller needs the reason: "your plan does not allow this", "unknown field" and
     * "the network was down" want completely different responses, and they used to be
     * indistinguishable.
     */
    suspend fun uploadAudio(
        apiKey: String,
        audioUrl: String,
        title: String,
        bearerToken: String
    ): Result<Boolean> {
        val body = buildJsonObject {
            put("query", "mutation(\$input: AudioUploadInput) { uploadAudio(input: \$input) { success message } }")
            putJsonObject("variables") {
                putJsonObject("input") {
                    put("url", audioUrl)
                    put("title", title)
                    put("bypass_size_check", true)
                    putJsonObject("download_auth") {
                        put("type", "bearer_token")
                        putJsonObject("bearer") {
                            put("token", bearerToken)
                        }
                    }
                }
            }
        }.toString()

        return runCatching {
            val (code, responseBody) = post(apiKey, body)
            if (responseBody.isBlank()) error("Empty response from Fireflies")
            if (code !in 200..299) error("Fireflies upload error $code: ${responseBody.take(300)}")
            val root = firefliesJson.parseToJsonElement(responseBody).jsonObject
            graphQlError(root)?.let { error("Fireflies rejected the upload: $it") }

            val result = root["data"]?.jsonObject?.get("uploadAudio")?.jsonObject
                ?: error("Fireflies returned no uploadAudio result: $responseBody")
            val ok = result["success"]?.jsonPrimitive?.booleanOrNull == true
            if (!ok) {
                // success:false carries its own message — surface that rather than a bare false.
                val message = result["message"]?.jsonPrimitive?.content ?: "no reason given"
                error("Fireflies declined the upload: $message")
            }
            true
        }
    }

    /**
     * Cheapest possible authenticated call, for the Settings diagnostic.
     *
     * The API key only ever exists on Carl's phone, so neither a laptop nor a Claude session can
     * test this path — every diagnosis otherwise costs a full build, install and recording. This
     * turns it into a five-second check.
     *
     * Returns the account it authenticated as on success, or the failure reason.
     */
    suspend fun testConnection(apiKey: String): Result<String> {
        val query = """{"query": "{ user { name email num_transcripts } }"}"""
        return runCatching {
            val (code, body) = post(apiKey, query)
            if (body.isBlank()) error("Empty response from Fireflies")
            if (code !in 200..299) error("HTTP $code: ${body.take(300)}")
            val root = firefliesJson.parseToJsonElement(body).jsonObject
            graphQlError(root)?.let { error(it) }
            val user = root["data"]?.jsonObject?.get("user")?.jsonObject
                ?: error("Unexpected response: ${body.take(300)}")
            val name = user["name"]?.jsonPrimitive?.content ?: "unknown"
            val email = user["email"]?.jsonPrimitive?.content ?: ""
            val count = user["num_transcripts"]?.jsonPrimitive?.content ?: "?"
            "Connected as $name${if (email.isNotBlank()) " ($email)" else ""} · $count transcripts"
        }
    }

    fun buildTranscriptText(sentences: List<FirefliesSentence>?): String {
        if (sentences.isNullOrEmpty()) return ""
        return sentences.joinToString("\n") { s ->
            if (s.speakerName.isNullOrBlank()) s.text ?: ""
            else "${s.speakerName}: ${s.text ?: ""}"
        }
    }
}
