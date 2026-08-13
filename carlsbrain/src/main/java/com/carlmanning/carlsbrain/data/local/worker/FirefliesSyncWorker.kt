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
        private val CB_PREFIX = Regex("""^CB(\d+)\s""")
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
        val bucketNameList = buckets.map { it.name }
        // name (lowercased) → id, for resolving guessBucket() output to a bucket id.
        // Only non-vault buckets are here, so a meeting can never be filed into the vault.
        val bucketIdsByName = buckets.associate { it.name.lowercase() to it.id }

        for (transcript in newTranscripts) {
            val cbMatch = CB_PREFIX.find(transcript.title ?: "")
            val localMeetingId = cbMatch?.groupValues?.get(1)?.toLongOrNull()
            if (localMeetingId != null) {
                updateExistingMeeting(localMeetingId, transcript, bucketNameList, bucketIdsByName)
            } else {
                importTranscript(transcript, bucketNameList, bucketIdsByName)
            }
        }

        updateMemoryIfNeeded(newTranscripts)

        return Result.success()
    }

    private suspend fun updateExistingMeeting(
        meetingId: Long,
        transcript: FirefliesTranscript,
        bucketNames: List<String>,
        bucketIdsByName: Map<String, Long>
    ) {
        val existing = db.meetingDao().getMeetingById(meetingId) ?: run {
            importTranscript(transcript, bucketNames, bucketIdsByName)
            return
        }
        val transcriptText = fireflies.buildTranscriptText(transcript.sentences)
        val summary = transcript.summary?.overview ?: ""
        val actionItems = parseActionItems(transcript.summary?.actionItems, bucketNames)
        val pendingJson = if (actionItems.isNotEmpty()) appJson.encodeToString(actionItems) else ""
        val dateStr = transcript.date?.let {
            java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(it))
        } ?: java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
            .format(java.util.Date(existing.recordedAt))
        val title = if (!transcript.title.isNullOrBlank() && !transcript.title.startsWith("CB"))
            transcript.title else "Meeting $dateStr"

        db.meetingDao().updateMeeting(
            existing.copy(
                title = title,
                transcript = transcriptText,
                summary = summary,
                pendingActionItems = pendingJson,
                status = "DONE",
                firefliesId = transcript.id,
                // Only auto-assign when the meeting has no bucket yet — a bucket Carl set by
                // hand (MeetingDao.setBucket) must never be overwritten by a keyword guess.
                bucketId = existing.bucketId
                    ?: resolveMeetingBucketId(title, summary, bucketNames, bucketIdsByName),
                updatedAt = System.currentTimeMillis()
            )
        )
    }

    private suspend fun importTranscript(
        transcript: FirefliesTranscript,
        bucketNames: List<String>,
        bucketIdsByName: Map<String, Long>
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
            firefliesId = transcript.id,
            bucketId = resolveMeetingBucketId(title, summary, bucketNames, bucketIdsByName)
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

    /**
     * Picks a bucket for the meeting itself from its title + summary using the same cheap
     * keyword matcher used for action items, then resolves the name to a bucket id.
     * `bucketIdsByName` only ever contains non-vault buckets, so a meeting is never
     * auto-filed into a vault bucket.
     *
     * Returns null only when there are no non-vault buckets at all; otherwise [guessBucket]
     * always yields a name from `bucketNames` (Work → first bucket fallback), so callers
     * should treat this as "a guess" and never use it to replace a bucket already set.
     */
    private fun resolveMeetingBucketId(
        title: String,
        summary: String,
        bucketNames: List<String>,
        bucketIdsByName: Map<String, Long>
    ): Long? {
        if (bucketNames.isEmpty()) return null
        val name = guessBucket("$title $summary", bucketNames)
        return bucketIdsByName[name.lowercase()]
    }

    private fun guessBucket(text: String, bucketNames: List<String>): String {
        // Word-boundary match: plain substring matching filed "business processes" into "SES"
        // (proce-sses / cour-ses / respon-ses).
        return bucketNames.firstOrNull { name ->
            name.isNotBlank() &&
                Regex("\\b${Regex.escape(name)}\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
        }
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
