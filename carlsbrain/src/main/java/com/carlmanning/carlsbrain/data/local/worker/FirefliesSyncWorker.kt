package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.remote.ActionItem
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.FirefliesRepository
import com.carlmanning.carlsbrain.data.remote.FirefliesTranscript
import com.carlmanning.carlsbrain.data.remote.appJson
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString

class FirefliesSyncWorker(
    ctx: Context,
    params: WorkerParameters
) : CoroutineWorker(ctx, params) {

    companion object {
        const val WORK_NAME = "fireflies_sync"
    }

    private val db = AppDatabase.getInstance(applicationContext)
    private val fireflies = FirefliesRepository()
    private val drive = DriveRepository(applicationContext)
    private val prefs = CarlsBrainApp.userPreferences

    override suspend fun doWork(): Result {
        val apiKey = prefs.firefliesApiKey.first()
        if (apiKey.isBlank()) return Result.success()

        val transcriptsResult = fireflies.getRecentTranscripts(apiKey)
        val transcripts = transcriptsResult.getOrNull() ?: return Result.retry()

        val existingIds = db.meetingDao().getAllFirefliesIds().toSet()
        val newTranscripts = transcripts.filter { it.id !in existingIds }
        if (newTranscripts.isEmpty()) return Result.success()

        val buckets = db.bucketDao().getNonVaultBuckets().first()
        val bucketNames = buckets.joinToString(", ") { it.name }.ifEmpty { "Work, Other" }

        for (transcript in newTranscripts) {
            importTranscript(transcript, buckets.map { it.name })
        }

        updateMemoryIfNeeded(newTranscripts)

        return Result.success()
    }

    private suspend fun importTranscript(
        transcript: FirefliesTranscript,
        bucketNames: List<String>
    ) {
        val recordedAt = transcript.date ?: System.currentTimeMillis()
        val durationMs = (transcript.duration ?: 0L) * 1000L
        val title = transcript.title?.takeIf { it.isNotBlank() } ?: "Fireflies meeting"
        val summary = transcript.summary?.overview ?: ""
        val transcriptText = fireflies.buildTranscriptText(transcript.sentences)

        val actionItems = parseActionItems(transcript.summary?.actionItems, bucketNames)
        val pendingJson = if (actionItems.isNotEmpty()) {
            appJson.encodeToString(actionItems)
        } else ""

        val entity = MeetingEntity(
            title = title,
            recordedAt = recordedAt,
            durationMs = durationMs,
            transcript = transcriptText,
            summary = summary,
            pendingActionItems = pendingJson,
            status = "DONE",
            updatedAt = System.currentTimeMillis(),
            firefliesId = transcript.id
        )
        db.meetingDao().insertMeeting(entity)
    }

    private fun parseActionItems(raw: String?, bucketNames: List<String>): List<ActionItem> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.lines()
            .map { it.trimStart('-', '*', '•', ' ', '\t').trim() }
            .filter { it.isNotBlank() }
            .map { line -> ActionItem(title = line, bucket = guessBucket(line, bucketNames)) }
    }

    private fun guessBucket(text: String, bucketNames: List<String>): String {
        val lower = text.lowercase()
        return bucketNames.firstOrNull { lower.contains(it.lowercase()) }
            ?: bucketNames.firstOrNull { it.equals("Work", ignoreCase = true) }
            ?: bucketNames.firstOrNull()
            ?: "Other"
    }

    private suspend fun updateMemoryIfNeeded(newTranscripts: List<FirefliesTranscript>) {
        if (newTranscripts.isEmpty()) return
        val summaries = newTranscripts
            .filter { !it.summary?.overview.isNullOrBlank() }
            .take(3)
        if (summaries.isEmpty()) return

        val memoryUpdate = buildString {
            appendLine()
            appendLine("## Recent Fireflies meetings (auto-synced)")
            for (t in summaries) {
                val dateMs = t.date ?: continue
                val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                    .format(java.util.Date(dateMs))
                appendLine("- **${t.title ?: "Meeting"}** ($date): ${t.summary?.overview?.take(200)}")
            }
        }

        runCatching {
            val current = drive.getMemoryMd() ?: ""
            drive.updateMemoryMd(current + memoryUpdate)
        }
    }
}
