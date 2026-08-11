package com.carlmanning.carlsbrain.data.remote

import com.carlmanning.carlsbrain.CarlsBrainApp
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
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

    suspend fun getRecentTranscripts(apiKey: String, limit: Int = 25): Result<List<FirefliesTranscript>> {
        val query = """
            {
              "query": "{ transcripts(limit: $limit) { id title date duration summary { overview action_items keywords } sentences { text speaker_name } } }"
            }
        """.trimIndent()

        val request = Request.Builder()
            .url(endpoint)
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(query.toRequestBody(mediaType))
            .build()

        return runCatching {
            val response = client.newCall(request).execute()
            val body = response.body?.string() ?: error("Empty response from Fireflies")
            if (!response.isSuccessful) error("Fireflies API error ${response.code}: $body")

            val root = firefliesJson.parseToJsonElement(body).jsonObject
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

    fun buildTranscriptText(sentences: List<FirefliesSentence>?): String {
        if (sentences.isNullOrEmpty()) return ""
        return sentences.joinToString("\n") { s ->
            if (s.speakerName.isNullOrBlank()) s.text ?: ""
            else "${s.speakerName}: ${s.text ?: ""}"
        }
    }
}
