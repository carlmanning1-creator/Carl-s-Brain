package com.carlmanning.carlsbrain.domain.capture

import android.content.Context
import android.net.Uri
import com.carlmanning.carlsbrain.CarlsBrainApp
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.worker.ReminderScheduler
import com.carlmanning.carlsbrain.data.remote.ApiMessage
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.MemoryLearner
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.domain.defaultBucket
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * The one save path for a typed capture — the Quick Capture screen and the widget's
 * [com.carlmanning.carlsbrain.ui.capture.QuickAddActivity] both come through here, so
 * auto-tagging, reminders and sync behave identically whichever was used.
 *
 * [save] inserts the row in the caller's coroutine (so the caller knows it exists), then hands
 * everything slower — Claude classification, photo uploads, memory — to
 * [CarlsBrainApp.appScope], because both callers finish or pop their screen straight after.
 */
object SaveCapture {

    data class Request(
        val isNote: Boolean,
        val text: String,
        val title: String = "",
        /** Carl's explicit choice; null means "Auto". */
        val selectedBucketId: Long? = null,
        val priority: Priority = Priority.NORMAL,
        val dueDate: Long? = null,
        val reminderAt: Long? = null,
        val recurrence: Recurrence = Recurrence.None,
        val photoUris: List<Uri> = emptyList(),
        /** A bucket already suggested for exactly [text]; reused rather than paid for twice. */
        val reusableSuggestion: BucketEntity? = null
    )

    sealed interface Result {
        data class Saved(val bucketName: String) : Result
        data object Blank : Result
        data object NoBucket : Result
    }

    /**
     * @param buckets the candidates for both the fallback bucket and Claude's classification.
     *   A caller that must never file into the vault passes non-vault buckets only — nothing
     *   here can choose a bucket outside this list.
     */
    suspend fun save(context: Context, request: Request, buckets: List<BucketEntity>): Result {
        val appContext = context.applicationContext
        val text = request.text.trim()
        if (text.isBlank()) return Result.Blank

        val bucketId = request.selectedBucketId
            ?: buckets.defaultBucket()?.id
            ?: buckets.lastOrNull()?.id
            ?: return Result.NoBucket
        val bucketName = buckets.find { it.id == bucketId }?.name ?: "Other"

        val db = AppDatabase.getInstance(appContext)
        // An explicit choice wins: never re-classify over a bucket Carl tapped.
        val bucketWasChosen = request.selectedBucketId != null
        val reusable = request.reusableSuggestion?.takeIf { s -> buckets.any { it.id == s.id } }

        if (request.isNote) {
            val title = request.title.trim().ifBlank {
                text.lines().first().take(60).ifBlank { "Note" }
            }
            val noteId = db.noteDao().insertNote(
                NoteEntity(title = title, content = text, bucketId = bucketId)
            )
            val pendingUris = request.photoUris
            CarlsBrainApp.appScope.launch {
                if (!bucketWasChosen) {
                    if (reusable != null) fileIntoBucket(db, noteId, reusable.id, isTodo = false)
                    else autoTagNote(db, noteId, text, buckets)
                }
                if (pendingUris.isNotEmpty()) uploadPendingPhotos(appContext, db, noteId, pendingUris)
                MemoryLearner.learnFrom(
                    appContext,
                    "Note created: \"${title}\" — bucket: $bucketName, content preview: ${text.take(120)}",
                    "note"
                )
            }
        } else {
            val todoId = db.todoDao().insertTodo(
                TodoEntity(
                    title = text,
                    bucketId = bucketId,
                    priority = request.priority.rank,
                    dueDate = request.dueDate,
                    reminderAt = request.reminderAt,
                    recurrence = request.recurrence.toStorageString()
                )
            )
            val reminderAt = request.reminderAt
            if (reminderAt != null && reminderAt > System.currentTimeMillis()) {
                ReminderScheduler.schedule(appContext, todoId, text, reminderAt)
            }
            // NORMAL is the picker's default, so anything else is a deliberate call — and the
            // one thing Carl most wants to survive is having said "urgent".
            val priorityWasChosen = request.priority != Priority.NORMAL
            val needsBucketCall = !bucketWasChosen && reusable == null
            CarlsBrainApp.appScope.launch {
                if (!bucketWasChosen && reusable != null) {
                    fileIntoBucket(db, todoId, reusable.id, isTodo = true)
                }
                if (needsBucketCall || !priorityWasChosen) {
                    autoTagTodo(
                        db, todoId, text, buckets,
                        applyBucket = needsBucketCall,
                        applyPriority = !priorityWasChosen
                    )
                }
                // Deliberately no MemoryLearner call for a to-do: memory.md is not a to-do list
                // (see CLAUDE.md, "memory.md is not a to-do list").
            }
        }
        return Result.Saved(bucketName)
    }

    private suspend fun uploadPendingPhotos(context: Context, db: AppDatabase, noteId: Long, uris: List<Uri>) {
        val drive = DriveRepository(context)
        val driveIds = mutableListOf<String>()
        for (uri in uris) {
            val bytes = context.contentResolver.openInputStream(uri)?.readBytes() ?: continue
            val id = drive.uploadPhoto(noteId, bytes, "image/jpeg") ?: continue
            driveIds.add(id)
        }
        if (driveIds.isNotEmpty()) {
            db.noteDao().getNoteById(noteId)?.let { existing ->
                val current = if (existing.attachments.isBlank()) emptyList()
                              else existing.attachments.split(",")
                db.noteDao().updateNote(
                    existing.copy(
                        attachments = (current + driveIds).joinToString(","),
                        updatedAt = System.currentTimeMillis(),
                        isSynced = false
                    )
                )
            }
        }
    }

    private suspend fun autoTagTodo(
        db: AppDatabase,
        todoId: Long,
        text: String,
        bucketList: List<BucketEntity>,
        applyBucket: Boolean,
        applyPriority: Boolean
    ) {
        val bucketNames = bucketList.joinToString("|") { it.name }
        val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>","priority":"<one of: URGENT|HIGH|NORMAL|SOMEDAY>"}
Classify this capture: "$text""""

        CarlsBrainApp.claudeClient.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You classify tasks. Return only valid JSON, nothing else."
        ).onSuccess { response ->
            runCatching {
                val tag = appJson.decodeFromString<AutoTag>(response.trim())
                val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                val priority = Priority.entries.find { it.name == tag.priority.uppercase() }
                if (bucket != null && priority != null) {
                    db.todoDao().getTodoById(todoId)?.let { existing ->
                        db.todoDao().updateTodo(
                            existing.copy(
                                bucketId = if (applyBucket) bucket.id else existing.bucketId,
                                priority = if (applyPriority) priority.rank else existing.priority,
                                updatedAt = System.currentTimeMillis()
                            )
                        )
                    }
                }
            }
        }
    }

    /** Files an already-saved row into [bucketId] without asking Claude. */
    private suspend fun fileIntoBucket(db: AppDatabase, id: Long, bucketId: Long, isTodo: Boolean) {
        runCatching {
            if (isTodo) {
                db.todoDao().getTodoById(id)?.let {
                    db.todoDao().updateTodo(
                        it.copy(bucketId = bucketId, updatedAt = System.currentTimeMillis(), isSynced = false)
                    )
                }
            } else {
                db.noteDao().getNoteById(id)?.let {
                    db.noteDao().updateNote(
                        it.copy(bucketId = bucketId, updatedAt = System.currentTimeMillis(), isSynced = false)
                    )
                }
            }
        }
    }

    private suspend fun autoTagNote(db: AppDatabase, noteId: Long, text: String, bucketList: List<BucketEntity>) {
        val bucketNames = bucketList.joinToString("|") { it.name }
        val prompt = """Return JSON only: {"bucket":"<one of: $bucketNames>"}
Which bucket does this note belong to? "$text""""

        CarlsBrainApp.claudeClient.chat(
            messages = listOf(ApiMessage("user", prompt)),
            systemPrompt = "You classify notes. Return only valid JSON, nothing else."
        ).onSuccess { response ->
            runCatching {
                val tag = appJson.decodeFromString<NoteTag>(response.trim())
                val bucket = bucketList.find { it.name.equals(tag.bucket, ignoreCase = true) }
                if (bucket != null) {
                    db.noteDao().getNoteById(noteId)?.let { existing ->
                        db.noteDao().updateNote(
                            existing.copy(bucketId = bucket.id, updatedAt = System.currentTimeMillis())
                        )
                    }
                }
            }
        }
    }

    @Serializable
    private data class AutoTag(val bucket: String = "", val priority: String = "NORMAL")

    @Serializable
    private data class NoteTag(val bucket: String = "")
}
