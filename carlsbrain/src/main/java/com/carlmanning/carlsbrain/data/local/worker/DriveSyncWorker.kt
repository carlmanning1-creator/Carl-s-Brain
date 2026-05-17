package com.carlmanning.carlsbrain.data.local.worker

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DriveSyncWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val json = Json { prettyPrint = true }

    override suspend fun doWork(): Result {
        val db = AppDatabase.getInstance(applicationContext)
        val drive = DriveRepository(applicationContext)

        // Snapshot all visible todos → todos.json
        val todos = db.todoDao().getVisibleTodos().first()
        val buckets = db.bucketDao().getAllBuckets().first().associateBy { it.id }
        val dtos = todos.map { todo ->
            TodoSyncDto(
                id = todo.id,
                title = todo.title,
                bucket = buckets[todo.bucketId]?.name ?: "Other",
                priority = todo.priority,
                isDone = todo.isDone,
                dueDate = todo.dueDate,
                createdAt = todo.createdAt,
                updatedAt = todo.updatedAt
            )
        }
        drive.uploadTodosJson(json.encodeToString(dtos))

        // Sync each unsynced note as an individual Drive file
        val unsyncedNotes = db.noteDao().getUnsyncedNotes()
        unsyncedNotes.forEach { note ->
            val ok = drive.uploadNoteFile(note.id, note.title, note.content)
            if (ok) db.noteDao().markSynced(note.id)
        }

        return Result.success()
    }

    @Serializable
    private data class TodoSyncDto(
        val id: Long,
        val title: String,
        val bucket: String,
        val priority: String,
        val isDone: Boolean,
        val dueDate: Long?,
        val createdAt: Long,
        val updatedAt: Long
    )
}
