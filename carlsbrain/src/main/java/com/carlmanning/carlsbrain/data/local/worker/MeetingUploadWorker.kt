package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
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

        val db = AppDatabase.getInstance(applicationContext)
        val drive = DriveRepository(applicationContext)
        val meeting = db.meetingDao().getMeetingById(meetingId) ?: return Result.success()

        // Deleted while the upload was queued — nothing to do, and re-uploading would put a
        // deleted meeting back in front of Carl on the web.
        if (meeting.deletedAt != null) return Result.success()

        return runCatching { upload(db, drive, meeting) }
            .getOrElse { Result.retry() }
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

        db.meetingDao().getMeetingById(meeting.id)?.let {
            db.meetingDao().updateMeeting(it.copy(updatedAt = System.currentTimeMillis()))
        }
        return Result.success()
    }

    companion object {
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
