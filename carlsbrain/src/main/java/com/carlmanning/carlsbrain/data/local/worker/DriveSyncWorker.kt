package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.domain.model.Priority
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
            runCatching { pullFromDrive(db, drive) }
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
            val bucketId = resolveBucketId(db, allBuckets, dto.bucket)
            val existing = roomTodosById[dto.id]
            when {
                // Locally deleted — never resurrect regardless of Drive timestamp
                existing != null && existing.deletedAt != null -> { /* skip */ }
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
                        isSynced = true
                    )
                )
                // else: local is current or newer — keep it
            }
        }
    }

    private suspend fun mergeNotesFromDrive(db: AppDatabase, drive: DriveRepository) {
        val driveNoteIds = drive.listNoteIds()
        // Include soft-deleted note IDs so we never resurrect a note the user deleted
        val roomNoteIds = db.noteDao().getAllNoteIds().toSet()
        val allBuckets = db.bucketDao().getAllBuckets().first()
        val defaultBucketId = allBuckets.find { it.name == "Other" }?.id
            ?: allBuckets.firstOrNull()?.id ?: return

        driveNoteIds.filter { it !in roomNoteIds }.forEach { noteId ->
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
        val todos = db.todoDao().getVisibleTodos().first()
        val buckets = db.bucketDao().getAllBuckets().first().associateBy { it.id }
        val dtos = todos.map { todo ->
            TodoSyncDto(
                id = todo.id,
                title = todo.title,
                bucket = buckets[todo.bucketId]?.name ?: "Other",
                priority = Priority.fromRank(todo.priority).name,
                isDone = todo.isDone,
                dueDate = todo.dueDate,
                createdAt = todo.createdAt,
                updatedAt = todo.updatedAt
            )
        }
        val todosOk = drive.uploadTodosJson(json.encodeToString(dtos))

        db.noteDao().getUnsyncedNotes().forEach { note ->
            if (drive.uploadNoteFile(note.id, note.title, note.content)) {
                db.noteDao().markSynced(note.id)
            }
        }

        // Remove Drive files for notes that have been soft-deleted locally
        db.noteDao().getDeletedNotes().first().forEach { note ->
            drive.deleteNoteFile(note.id)
        }

        return todosOk
    }

    @Serializable
    data class TodoSyncDto(
        val id: Long,
        val title: String,
        val bucket: String,
        val priority: String,
        val isDone: Boolean,
        val dueDate: Long? = null,
        val createdAt: Long,
        val updatedAt: Long
    )
}
