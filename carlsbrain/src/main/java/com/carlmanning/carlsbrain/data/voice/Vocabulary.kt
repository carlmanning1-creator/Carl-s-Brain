package com.carlmanning.carlsbrain.data.voice

import com.carlmanning.carlsbrain.data.local.AppDatabase
import kotlinx.coroutines.flow.first

/**
 * Names the transcriber is told to expect — household and place names, the things a general
 * model mishears.
 *
 * Kept in step with `VoiceCaptureService.BASE_VOCABULARY` (private there). Non-vault bucket
 * names are added at call time; nothing from a vault bucket is ever placed in this string,
 * because it leaves the device and for a vault bucket the name is the sensitive part.
 */
object Vocabulary {
    const val BASE =
        "Carl, Bec, Grace, Lucas, Dubbo, SES, Service NSW, SNSW, " +
            "Phoenix Strength and Recovery, CrossFit Dubbo, to-do, journal"

    /** [BASE] plus non-vault bucket names. Falls back to [BASE] if the database read fails. */
    suspend fun forTranscription(db: AppDatabase): String = runCatching {
        (listOf(BASE) + db.bucketDao().getNonVaultBuckets().first().map { it.name })
            .joinToString(", ")
    }.getOrDefault(BASE)
}
