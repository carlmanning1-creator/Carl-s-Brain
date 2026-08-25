package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.first

class MidnightCleanupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        return runCatching {
            val db = AppDatabase.getInstance(applicationContext)
            db.todoDao().archiveAllCompleted()

            val cutoff = System.currentTimeMillis() - (90L * 24 * 60 * 60 * 1000)

            // Write tombstones before hard-purging so pull sync never re-inserts auto-expired items
            val expiredTodos = db.todoDao().getDeletedTodos().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            val expiredNotes = db.noteDao().getDeletedNotes().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            val expiredJournal = db.journalDao().getDeletedEntries().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            db.tombstoneDao().insertAll(
                expiredTodos.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_TODO) } +
                expiredNotes.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_NOTE) } +
                expiredJournal.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_JOURNAL) }
            )

            // Meetings own files outside the database — a Drive folder and a local audio
            // file — and nothing used to remove either. A meeting deleted on the phone stayed
            // visible on the web app forever, because the web reads Drive folders directly and
            // never knew it had been deleted, and its audio sat on the phone indefinitely.
            // Collect them BEFORE the rows are purged, or the ids are gone.
            val expiredMeetings = db.meetingDao().getDeletedMeetings().first()
                .filter { (it.deletedAt ?: Long.MAX_VALUE) < cutoff }
            val drive = DriveRepository(applicationContext)
            expiredMeetings.forEach { meeting ->
                // Best-effort each: one failure must not block the rest of the cleanup, and
                // the row is purged regardless — a stranded file is better than a stuck queue.
                runCatching { drive.deleteMeetingFolder(meeting.driveFolderId) }
                runCatching {
                    if (meeting.localAudioPath.isNotBlank()) {
                        java.io.File(meeting.localAudioPath).delete()
                    }
                }
            }

            // Meeting audio now lives in filesDir rather than cacheDir, so that a recording
            // waiting to upload can no longer be cleared by the system out from under us. The
            // cost is that nothing reclaims it automatically, so prune it here — but only for
            // meetings whose audio is confirmed to be on Drive, since until then the local
            // file is the only copy in existence.
            val audioCutoff = System.currentTimeMillis() -
                (MeetingAudioStore.KEEP_LOCAL_DAYS * 24 * 60 * 60 * 1000)
            db.meetingDao().getAllMeetings().first()
                .filter {
                    it.recordedAt < audioCutoff &&
                        it.driveAudioFileId.isNotBlank() &&
                        it.localAudioPath.isNotBlank()
                }
                .forEach { meeting ->
                    runCatching { java.io.File(meeting.localAudioPath).delete() }
                    db.meetingDao().updateMeeting(meeting.copy(localAudioPath = ""))
                }

            // The Drive files for expired notes and journal entries go now, not at delete time.
            // The sync stamps them deleted instead of trashing them, so that a second device
            // learns about the deletion rather than re-uploading its own copy; this is where
            // the file is finally removed, in step with the row being purged below.
            //
            // Best-effort each, as with meetings: one failure must not block the cleanup, and a
            // stranded file is better than a stuck queue. A stamped file that survives is
            // hidden by every reader anyway.
            expiredNotes.forEach { note ->
                runCatching { drive.deleteNoteFile(note.id) }
            }
            expiredJournal.forEach { entry ->
                runCatching { drive.deleteJournalEntry(entry.id) }
            }

            db.noteDao().purgeOldDeletedNotes(cutoff)
            db.todoDao().purgeOldDeletedTodos(cutoff)
            db.meetingDao().purgeOldDeletedMeetings(cutoff)
            db.journalDao().purgeOldDeletedEntries(cutoff)

            // Purge tombstones older than 180 days — they're no longer needed once Drive
            // has been synced well past the item's deletion date
            val tombstoneCutoff = System.currentTimeMillis() - (180L * 24 * 60 * 60 * 1000)
            db.tombstoneDao().purgeOld(tombstoneCutoff)
        }.fold(
            onSuccess = { Result.success() },
            onFailure = { Result.retry() }
        )
    }
}
