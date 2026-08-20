package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.ui.screens.meetings.TranscriptSource
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

        // Delimiters for the one section of memory.md this worker owns. Everything outside
        // this marker pair is Carl's — hand-written in Settings — and is never touched.
        // We match on the markers, never on the heading text, because Carl could plausibly
        // type that heading himself.
        private const val AUTO_START = "<!-- carlsbrain:auto:fireflies:start -->"
        private const val AUTO_END = "<!-- carlsbrain:auto:fireflies:end -->"
        private const val AUTO_HEADING = "## Recent Fireflies meetings (auto-synced)"
        private const val MAX_MEMORY_MEETINGS = 10

        /** Shape of a bullet this worker writes: `- **Title** (1 Jan 2026): overview…` */
        private val AUTO_BULLET = Regex("""^- \*\*.*\*\* \(.*\):""")
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
                transcriptSource = TranscriptSource.FIREFLIES,
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
            transcriptSource = TranscriptSource.FIREFLIES,
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

        val newBullets = summaries.mapNotNull { t ->
            val dateMs = t.date ?: return@mapNotNull null
            val date = java.text.SimpleDateFormat("d MMM yyyy", java.util.Locale.getDefault())
                .format(java.util.Date(dateMs))
            "- **${t.title ?: "Meeting"}** ($date): ${t.summary?.overview?.take(200)}"
        }
        if (newBullets.isEmpty()) return

        // memory.md is precious and is prepended to every Claude call. Any failure here leaves
        // the remote file exactly as it was — we only ever write a value we fully built first.
        runCatching {
            val current = drive.getMemoryMd() ?: ""
            val updated = rewriteAutoSection(current, newBullets)
            if (updated != current) drive.updateMemoryMd(updated)
        }
    }

    /**
     * Returns [current] with this worker's auto-generated section replaced in place (or appended
     * once if absent), newest meetings first and capped at [MAX_MEMORY_MEETINGS].
     *
     * Only content between [AUTO_START] and [AUTO_END], plus legacy unmarked sections that
     * exactly match the shape this worker used to append, is ever removed. Anything Carl wrote
     * by hand is carried through untouched.
     */
    private fun rewriteAutoSection(current: String, newBullets: List<String>): String {
        val startIdx = current.indexOf(AUTO_START)
        val endIdx = if (startIdx >= 0) current.indexOf(AUTO_END, startIdx) else -1

        var before: String
        var after: String
        val previousBullets: List<String>

        if (startIdx >= 0 && endIdx > startIdx) {
            before = current.substring(0, startIdx)
            after = current.substring(endIdx + AUTO_END.length)
            previousBullets = current.substring(startIdx + AUTO_START.length, endIdx)
                .lines()
                .map { it.trim() }
                .filter { AUTO_BULLET.containsMatchIn(it) }
        } else {
            // No marked section yet. Strip any legacy unmarked sections we ourselves wrote,
            // keeping their bullets so history is not lost, then append the marked section.
            val (stripped, legacyBullets) = stripLegacyAutoSections(current)
            before = stripped
            after = ""
            previousBullets = legacyBullets
        }

        // Newest first, de-duplicated by bullet text, capped.
        val bullets = (newBullets + previousBullets).distinct().take(MAX_MEMORY_MEETINGS)

        val section = buildString {
            appendLine(AUTO_START)
            appendLine(AUTO_HEADING)
            bullets.forEach { appendLine(it) }
            append(AUTO_END)
        }

        before = before.trimEnd('\n')
        after = after.trimStart('\n')
        return buildString {
            if (before.isNotEmpty()) {
                append(before)
                append("\n\n")
            }
            append(section)
            if (after.isNotEmpty()) {
                append("\n\n")
                append(after)
            }
            append("\n")
        }
    }

    /**
     * Removes runs of lines that clearly match what this worker previously appended: the exact
     * [AUTO_HEADING] line followed only by blank lines and bullets in [AUTO_BULLET] shape. The
     * run stops at the first line that is neither — in particular at the next heading — so any
     * prose Carl added under that heading is left in place (and the heading with it).
     *
     * Returns the cleaned text plus the bullets that were removed, so they can be re-listed
     * inside the new marked section.
     */
    private fun stripLegacyAutoSections(current: String): Pair<String, List<String>> {
        val lines = current.lines()
        val kept = mutableListOf<String>()
        val harvested = mutableListOf<String>()
        var i = 0
        while (i < lines.size) {
            if (lines[i].trim() != AUTO_HEADING) {
                kept += lines[i]
                i++
                continue
            }
            // Look ahead over the candidate section before committing to removing anything.
            var j = i + 1
            val sectionBullets = mutableListOf<String>()
            while (j < lines.size) {
                val line = lines[j].trim()
                when {
                    line.isEmpty() -> j++
                    AUTO_BULLET.containsMatchIn(line) -> { sectionBullets += line; j++ }
                    else -> break
                }
            }
            if (sectionBullets.isEmpty()) {
                // Heading with no recognisable bullets under it — not clearly ours. Leave it.
                kept += lines[i]
                i++
            } else {
                harvested += sectionBullets
                i = j
            }
        }
        return kept.joinToString("\n") to harvested
    }
}
