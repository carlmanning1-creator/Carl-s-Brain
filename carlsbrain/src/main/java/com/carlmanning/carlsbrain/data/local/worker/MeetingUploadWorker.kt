package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Uploads a meeting's audio, transcript and summary to Drive.
 *
 * This used to run as `viewModelScope.launch { uploadToDrive(done) }` straight after analysis.
 * Two things were wrong with that, and both meant a meeting could exist on the phone and never
 * appear on the web app:
 *
 *  1. **viewModelScope is cancelled on navigation.** Leaving the Meetings screen while the
 *     upload was in flight killed it silently — no error, no retry, no record. The same trap
 *     already cost this project a Claude auto-tag and a truncated export.
 *  2. **No retry.** A dropped connection at that moment meant the meeting never reached Drive,
 *     ever, because nothing revisited it.
 *
 * Carl reads meetings on a locked-down work device where the web app is his only access, so a
 * meeting that silently fails to upload is not a cosmetic problem — it is the recording being
 * unavailable exactly where he needs it.
 *
 * WorkManager solves both: it survives the process, waits for a network, and retries with
 * backoff. It is also idempotent, so a retry after a partial upload re-uses the existing folder
 * rather than creating a second one.
 */
class MeetingUploadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val meetingId = inputData.getLong(KEY_MEETING_ID, -1L)
        if (meetingId <= 0L) return Result.failure()

        // WorkManager retries indefinitely by default. A permanent failure — revoked Drive
        // access, a deleted folder — would otherwise retry forever, burning battery and
        // network on something that cannot succeed. Give up after MAX_ATTEMPTS; the meeting
        // is still on the phone, and re-processing it queues a fresh attempt.
        if (runAttemptCount >= MAX_ATTEMPTS) {
            android.util.Log.w(TAG, "Meeting $meetingId upload gave up after $runAttemptCount attempts")
            return Result.failure()
        }

        val db = AppDatabase.getInstance(applicationContext)
        val drive = DriveRepository(applicationContext)
        val meeting = db.meetingDao().getMeetingById(meetingId) ?: return Result.success()

        // Deleted meetings still need ONE thing uploaded: a meta.json marked deleted, so the
        // web app stops showing it. Returning early here would have meant a meeting deleted on
        // the phone stayed on the laptop until its files were purged three months later.
        if (meeting.deletedAt != null) {
            return runCatching { publishDeletedMarker(db, drive, meeting) }
                .getOrElse { Result.retry() }
        }

        return runCatching { upload(db, drive, meeting) }
            .getOrElse { Result.retry() }
    }

    /**
     * Marks an already-uploaded meeting as deleted on Drive without touching its files.
     *
     * Files stay for the 90-day Recently Deleted window — restoring re-uploads a meta.json
     * without the flag — and MidnightCleanupWorker removes them at purge.
     */
    private suspend fun publishDeletedMarker(
        db: AppDatabase,
        drive: DriveRepository,
        meeting: MeetingEntity
    ): Result {
        // Never uploaded, so there is nothing on Drive to hide.
        if (meeting.driveFolderId.isBlank()) return Result.success()
        val bucketName = meeting.bucketId
            ?.let { db.bucketDao().getBucketById(it)?.name }
            .orEmpty()
        val meta = MeetingMeta(
            title = meeting.title.ifBlank { "Untitled meeting" },
            recordedAt = meeting.recordedAt,
            durationMs = meeting.durationMs,
            bucket = bucketName,
            status = meeting.status,
            deletedAt = meeting.deletedAt,
            updatedAt = meeting.updatedAt
        )
        val ok = drive.uploadMeetingTextFile(
            meeting.driveFolderId,
            "meta.json",
            metaJson.encodeToString(meta),
            "application/json"
        )
        return if (ok) Result.success() else Result.retry()
    }

    private suspend fun upload(
        db: AppDatabase,
        drive: DriveRepository,
        meeting: MeetingEntity
    ): Result {
        // Re-use the folder from a previous partial attempt rather than making another one.
        val folderId = meeting.driveFolderId.ifBlank {
            val date = SimpleDateFormat(FOLDER_DATE_FORMAT, Locale.US)
                .format(Date(meeting.recordedAt))
            val safeName = meeting.title.take(40).replace(Regex("[^a-zA-Z0-9 ]"), "").trim()
            // Locale.US, not default: the folder name is a data format the web app parses, so
            // it must not change shape with the device locale.
            drive.createMeetingFolder(if (safeName.isBlank()) date else "$date $safeName")
                ?: return Result.retry()
        }

        if (folderId != meeting.driveFolderId) {
            db.meetingDao().getMeetingById(meeting.id)?.let {
                db.meetingDao().updateMeeting(it.copy(driveFolderId = folderId))
            }
        }

        // Audio first: it is the irreplaceable part. A transcript can be regenerated from audio,
        // but audio only exists on the phone until this succeeds.
        var audioId = meeting.driveAudioFileId
        if (audioId.isBlank()) {
            val audioFile = File(meeting.localAudioPath)
            if (audioFile.exists() && audioFile.length() > 0) {
                audioId = drive.uploadMeetingAudio(folderId, audioFile.readBytes())
                    ?: return Result.retry()
                db.meetingDao().getMeetingById(meeting.id)?.let {
                    db.meetingDao().updateMeeting(it.copy(driveAudioFileId = audioId))
                }
            }
        }

        // Text files are written even when they are empty, and regardless of analysis status.
        // Previously only a fully analysed meeting uploaded at all, so a failed or pending
        // transcription meant nothing whatsoever appeared on the web app — not even the fact
        // that a meeting had happened. The web app reads status from which files have content.
        val title = meeting.title.ifBlank { "Untitled meeting" }
        val transcriptOk = drive.uploadMeetingTextFile(
            folderId,
            "transcript.md",
            "# Transcript\n\n${meeting.transcript}"
        )
        val summaryOk = drive.uploadMeetingTextFile(
            folderId,
            "summary.md",
            "# $title\n\n${meeting.summary}"
        )
        if (!transcriptOk || !summaryOk) return Result.retry()

        // meta.json carries what the file names and markdown cannot express. Without it the web
        // app had to infer the recording time from the folder name, showed every duration as
        // zero, and had no idea meetings have a bucket at all — so it could not apply the vault
        // to them. Best-effort: an older meeting without meta.json still renders from its files.
        val bucketName = meeting.bucketId
            ?.let { db.bucketDao().getBucketById(it)?.name }
            .orEmpty()
        val meta = MeetingMeta(
            title = title,
            recordedAt = meeting.recordedAt,
            durationMs = meeting.durationMs,
            bucket = bucketName,
            status = meeting.status,
            deletedAt = meeting.deletedAt,
            updatedAt = meeting.updatedAt
        )
        // Action items Claude extracted but Carl has not yet approved. They live in a Room
        // column that never reached Drive, while the web app scraped [ACTION:] markers out of
        // summary.md — markers the phone strips before saving. So every phone-recorded meeting
        // showed zero action items on the laptop, which is the single most useful thing in a
        // meeting. Published as their own file rather than stuffed back into the summary, so
        // neither side has to parse prose to find them.
        runCatching {
            drive.uploadMeetingTextFile(
                folderId,
                "actions.json",
                meeting.pendingActionItems.ifBlank { "[]" },
                "application/json"
            )
        }

        runCatching {
            drive.uploadMeetingTextFile(
                folderId,
                "meta.json",
                metaJson.encodeToString(meta),
                "application/json"
            )
        }

        db.meetingDao().getMeetingById(meeting.id)?.let {
            db.meetingDao().updateMeeting(it.copy(updatedAt = System.currentTimeMillis()))
        }
        return Result.success()
    }

    /**
     * Machine-readable meeting facts for the web app. Additive only — the web app tolerates a
     * missing file and unknown fields, so new fields are safe but removals are not.
     */
    @Serializable
    data class MeetingMeta(
        val title: String,
        val recordedAt: Long,
        val durationMs: Long,
        /** Empty when unsorted. The web app hides vault-bucket meetings while locked. */
        val bucket: String,
        val status: String,
        /**
         * Set when the meeting is in Recently Deleted. The web app hides it, so a meeting
         * deleted on the phone disappears from the laptop straight away instead of lingering
         * until the files are purged 90 days later.
         */
        val deletedAt: Long? = null,
        /**
         * When this meeting was last edited, by whoever wrote the file.
         *
         * The web app stamps it when Carl corrects a transcript or renames a meeting, and the
         * phone compares it against its own row to decide whether to pull those edits in.
         * Absent on anything written before the web app could edit meetings, which reads as
         * "older than anything local" and is the safe answer.
         */
        val updatedAt: Long = 0L
    )

    companion object {
        private val metaJson = Json { encodeDefaults = true }

        private const val TAG = "MeetingUploadWorker"

        /** Roughly a day of exponential backoff before giving up. */
        private const val MAX_ATTEMPTS = 8

        const val KEY_MEETING_ID = "meeting_id"

        /**
         * Folder name prefix, e.g. `2026-08-18 14-30`. The web app parses this to date a
         * meeting, so treat it as a wire format: changing it breaks meeting dates on the web.
         */
        const val FOLDER_DATE_FORMAT = "yyyy-MM-dd HH-mm"

        /** Unique work name per meeting, so re-queuing replaces rather than duplicates. */
        fun workName(meetingId: Long) = "meeting_upload_$meetingId"
    }
}
