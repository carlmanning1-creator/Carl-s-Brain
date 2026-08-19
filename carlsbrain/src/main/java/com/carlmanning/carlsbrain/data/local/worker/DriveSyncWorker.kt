package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.defaultBucket
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val json = Json { prettyPrint = true; ignoreUnknownKeys = true }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val drive = DriveRepository(applicationContext)

        val pushOk = withTimeoutOrNull(60_000L) {
            val pullOk = runCatching { pullFromDrive(db, drive) }.isSuccess
            if (!pullOk) return@withTimeoutOrNull false
            runCatching { pushToDrive(db, drive) }.getOrElse { false }
        }

        return when {
            pushOk == null -> Result.retry() // timeout
            pushOk -> Result.success()
            else -> Result.retry()
        }
    }

    // ── Pull ─────────────────────────────────────────────────────────

    private suspend fun pullFromDrive(db: AppDatabase, drive: DriveRepository) {
        mergeTodosFromDrive(db, drive)
        mergeNotesFromDrive(db, drive)
    }

    private suspend fun mergeTodosFromDrive(db: AppDatabase, drive: DriveRepository) {
        val jsonStr = drive.downloadTodosJson() ?: return
        val driveTodos = runCatching { json.decodeFromString<List<TodoSyncDto>>(jsonStr) }
            .getOrElse { return }

        // Include soft-deleted rows so we never resurrect a todo the user deleted
        val roomTodosById = db.todoDao().getAllTodosIncludingDeleted().associateBy { it.id }
        val allBuckets = db.bucketDao().getAllBuckets().first().toMutableList()

        driveTodos.forEach { dto ->
            // Drive item is marked deleted — never insert or update locally
            if (dto.deletedAt != null) return@forEach
            val bucketId = resolveBucketId(db, allBuckets, dto.bucket)
            val existing = roomTodosById[dto.id]
            when {
                // Locally deleted — never resurrect regardless of Drive timestamp
                existing != null && existing.deletedAt != null -> { /* skip */ }
                // Hard-purged but tombstone exists — never resurrect
                existing == null && db.tombstoneDao().isTombstoned(dto.id, TombstoneEntity.TYPE_TODO) -> { /* skip */ }
                existing == null -> db.todoDao().insertTodo(
                    TodoEntity(
                        id = dto.id,
                        title = dto.title,
                        bucketId = bucketId,
                        priority = Priority.entries.find { it.name == dto.priority.uppercase() }?.rank ?: Priority.NORMAL.rank,
                        isDone = dto.isDone,
                        dueDate = dto.dueDate,
                        createdAt = dto.createdAt,
                        updatedAt = dto.updatedAt,
                        recurrence = dto.recurrence.ifBlank { Recurrence.None.toStorageString() },
                        leadDays = dto.leadDays,
                        reminderAt = dto.reminderAt,
                        isPinned = dto.isPinned,
                        estimateMinutes = dto.estimateMinutes,
                        isSynced = true
                    )
                )
                dto.updatedAt > existing.updatedAt -> db.todoDao().updateTodo(
                    existing.copy(
                        title = dto.title,
                        bucketId = bucketId,
                        priority = Priority.entries.find { it.name == dto.priority.uppercase() }?.rank ?: Priority.NORMAL.rank,
                        isDone = dto.isDone,
                        dueDate = dto.dueDate,
                        updatedAt = dto.updatedAt,
                        // Blank means the remote copy predates these fields — keep what is
                        // already here rather than wiping a recurrence the phone knows about.
                        recurrence = dto.recurrence.ifBlank { existing.recurrence },
                        leadDays = dto.leadDays,
                        reminderAt = dto.reminderAt ?: existing.reminderAt,
                        isPinned = dto.isPinned,
                        estimateMinutes = dto.estimateMinutes ?: existing.estimateMinutes,
                        isSynced = true
                    )
                )
                // else: local is current or newer — keep it
            }
        }
    }

    private suspend fun mergeNotesFromDrive(db: AppDatabase, drive: DriveRepository) {
        val driveNoteIds = drive.listNoteIds()

        // Re-queue notes the app thinks are on Drive but are not.
        //
        // isSynced is set once, on a successful upload, and never revisited — so if the Drive
        // copy disappears the phone goes on believing it is safe and never re-uploads. That is
        // exactly what happened when Carl's duplicate SecondBrain folders were consolidated:
        // every note file went with the trashed folders, the notes stayed on the phone marked
        // synced, and the web app showed an empty Notes list with nothing explaining why.
        //
        // Guarded on driveNoteIds being non-empty: listNoteIds returns an empty list both when
        // Drive genuinely has no notes AND when the lookup failed, and treating a failed lookup
        // as "Drive has nothing" would re-upload the entire library on every network blip.
        // The first-ever sync is covered anyway — those notes are unsynced already.
        if (driveNoteIds.isNotEmpty()) {
            val missing = db.noteDao().getSyncedNoteIds().filterNot { it in driveNoteIds }
            if (missing.isNotEmpty()) db.noteDao().markNotesUnsynced(missing)
        }
        // Include soft-deleted note IDs so we never resurrect a note the user deleted
        val roomNoteIds = db.noteDao().getAllNoteIds().toSet()
        val allBuckets = db.bucketDao().getAllBuckets().first()
        val defaultBucketId = allBuckets.defaultBucket()?.id
            ?: allBuckets.firstOrNull()?.id ?: return

        driveNoteIds.filter { it !in roomNoteIds }.forEach { noteId ->
            // Hard-purged but tombstone exists — never resurrect
            if (db.tombstoneDao().isTombstoned(noteId, TombstoneEntity.TYPE_NOTE)) return@forEach
            val (title, content) = drive.downloadNoteFile(noteId) ?: return@forEach
            db.noteDao().insertNote(
                NoteEntity(
                    id = noteId,
                    title = title,
                    content = content,
                    bucketId = defaultBucketId,
                    isSynced = true
                )
            )
        }
    }

    private suspend fun resolveBucketId(
        db: AppDatabase,
        allBuckets: MutableList<BucketEntity>,
        bucketName: String
    ): Long {
        val existing = allBuckets.find { it.name.equals(bucketName, ignoreCase = true) }
        if (existing != null) return existing.id
        val newBucket = BucketEntity(name = bucketName, sortOrder = 99)
        val newId = db.bucketDao().insertBucket(newBucket)
        allBuckets.add(newBucket.copy(id = newId))
        return newId
    }

    // ── Push ─────────────────────────────────────────────────────────

    private suspend fun pushToDrive(db: AppDatabase, drive: DriveRepository): Boolean {
        // Include soft-deleted todos (deletedAt IS NOT NULL) so Drive's JSON reflects deletions.
        // This closes the resurrection window: if a todo is hard-purged before the next sync,
        // Drive has already seen the deletedAt marker and the pull will skip re-insertion.
        val todos = db.todoDao().getAllTodosIncludingDeleted()
        // Collected once and used for both the todo bucket names and buckets.json below.
        val allBuckets = db.bucketDao().getAllBuckets().first()
        val buckets = allBuckets.associateBy { it.id }
        val dtos = todos.map { todo ->
            TodoSyncDto(
                id = todo.id,
                title = todo.title,
                bucket = buckets[todo.bucketId]?.name ?: "Other",
                priority = Priority.fromRank(todo.priority).name,
                isDone = todo.isDone,
                dueDate = todo.dueDate,
                createdAt = todo.createdAt,
                updatedAt = todo.updatedAt,
                deletedAt = todo.deletedAt,
                recurrence = todo.recurrence,
                leadDays = todo.leadDays,
                reminderAt = todo.reminderAt,
                isPinned = todo.isPinned,
                estimateMinutes = todo.estimateMinutes
            )
        }
        val todosOk = drive.uploadTodosJson(json.encodeToString(dtos))

        // Publish the bucket list so the web app knows which buckets are vault. Without this
        // it fell back to a hardcoded list and rendered a bucket Carl had marked private as
        // an ordinary one. Best-effort: a failure here must not fail the whole sync, and the
        // web app treats a missing buckets.json as "trust nothing", not "nothing is vault".
        val bucketDtos = allBuckets.map { b -> BucketSyncDto(name = b.name, isVault = b.isVault) }
        runCatching { drive.uploadBucketsJson(json.encodeToString(bucketDtos)) }

        // Keep the web app's API keys in step with the phone's. The OpenAI key in particular
        // lived only in DataStore, so web transcription had no key at all and failed every
        // time. Merging, so a key set from the web is never blanked by a phone that lacks one.
        runCatching {
            val prefs = CarlsBrainApp.userPreferences
            drive.publishSettingsKeys(
                anthropicKey = prefs.anthropicApiKey.first(),
                openaiKey = prefs.openaiApiKey.first()
            )
        }

        // Journal entries. Same self-healing check as notes: an entry the app believes is on
        // Drive but is not gets re-queued, so a file lost outside the app cannot leave the two
        // silently disagreeing.
        val driveJournalIds = drive.listJournalIds()
        if (driveJournalIds.isNotEmpty()) {
            val missing = db.journalDao().getSyncedIds().filterNot { it in driveJournalIds }
            if (missing.isNotEmpty()) db.journalDao().markUnsynced(missing)
        }
        db.journalDao().getUnsyncedEntries().forEach { entry ->
            val ok = drive.uploadJournalEntry(
                entryId = entry.id,
                content = entry.content,
                prompt = entry.prompt,
                isPrivate = entry.isPrivate,
                createdAt = entry.createdAt
            )
            if (ok) db.journalDao().markSynced(entry.id)
        }
        db.journalDao().getDeletedEntries().first().forEach { entry ->
            drive.deleteJournalEntry(entry.id)
        }

        db.noteDao().getUnsyncedNotes().forEach { note ->
            val bucketName = db.bucketDao().getBucketById(note.bucketId)?.name ?: "Personal"
            if (drive.uploadNoteFile(note.id, note.title, note.content, bucketName)) {
                db.noteDao().markSynced(note.id)
            }
        }

        // Remove Drive files for notes that have been soft-deleted locally
        db.noteDao().getDeletedNotes().first().forEach { note ->
            drive.deleteNoteFile(note.id)
        }

        return todosOk
    }

    /** Bucket config published for the web app. Names must match those used in TodoSyncDto. */
    @Serializable
    data class BucketSyncDto(
        val name: String,
        val isVault: Boolean
    )

    /**
     * The todo wire format shared with the web app.
     *
     * recurrence, leadDays, reminderAt and estimateMinutes were previously absent here while
     * the web app's editor already wrote recurrence and leadDays. The result was one-way loss:
     * a recurring todo created on the laptop arrived on the phone as a plain one, and a
     * recurring todo created on the phone showed as one-off on the laptop. Nothing was
     * corrupted — both sides preserve fields they do not understand when merging an existing
     * row — but the round trip quietly dropped them.
     *
     * All new fields are optional with defaults, so an older todos.json still parses.
     */
    @Serializable
    data class TodoSyncDto(
        val id: Long,
        val title: String,
        val bucket: String,
        val priority: String,
        val isDone: Boolean,
        val dueDate: Long? = null,
        val createdAt: Long,
        val updatedAt: Long,
        val deletedAt: Long? = null,
        val recurrence: String = "",
        val leadDays: Int = 0,
        val reminderAt: Long? = null,
        val isPinned: Boolean = false,
        val estimateMinutes: Int? = null
    )
}
