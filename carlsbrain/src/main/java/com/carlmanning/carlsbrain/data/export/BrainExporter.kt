package com.carlmanning.carlsbrain.data.export

import android.content.Context
import android.net.Uri
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
import com.carlmanning.carlsbrain.data.local.entity.ChatMessageEntity
import com.carlmanning.carlsbrain.data.local.entity.ChatThreadEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.data.local.entity.JournalTemplateEntity
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.remote.ActionItem
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import com.carlmanning.carlsbrain.data.remote.appJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.OutputStreamWriter
import java.io.Writer
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes Carl's whole brain out as a plain `.zip` of durable formats — Markdown and CSV,
 * nothing that needs this app to read it back.
 *
 * Deliberately a one-way snapshot, not a backup format: there is no importer, and the
 * README inside the zip says so plainly. Drive sync remains the thing that survives a
 * reinstall; this is the thing that survives the app itself.
 *
 * Vault content is included ONLY when the caller passes [includeVault] = true, and the
 * caller is responsible for having established that the vault is actually unlocked
 * (see SettingsViewModel.exportEverything). Every query below has a vault-safe variant
 * mirroring the DAO's own `isVault = 0` predicates, so the default path cannot leak.
 */
object BrainExporter {

    /** What ended up in the zip — used to report completion honestly. */
    data class Summary(
        val noteCount: Int,
        val todoCount: Int,
        val meetingCount: Int,
        val eventCount: Int,
        val bucketCount: Int,
        val journalCount: Int,
        val subtaskCount: Int,
        val chatCount: Int,
        val includedVault: Boolean,
        val memoryIncluded: Boolean
    )

    /** Coarse progress for the Settings UI. Not a percentage — the counts aren't known up front. */
    sealed class Progress {
        data class Step(val label: String) : Progress()
    }

    private val isoFormatter: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME
    private val dateFormatter: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

    /**
     * Streams the export into [uri] (a document handed back by the system file picker).
     *
     * Nothing is buffered whole: each zip entry is written and flushed before the next one
     * starts, and the two potentially large tables (notes, meetings) are walked id-by-id so
     * only a single record's content is resident at any moment.
     */
    suspend fun export(
        context: Context,
        uri: Uri,
        includeVault: Boolean,
        onProgress: (Progress) -> Unit = {}
    ): Result<Summary> = withContext(Dispatchers.IO) {
        runCatching {
            val db = AppDatabase.getInstance(context)
            val drive = DriveRepository(context)
            val exportedAt = System.currentTimeMillis()

            // memory.md comes off the network, so fetch it before the output stream is open —
            // a slow or failed Drive call must not hold a half-written document.
            onProgress(Progress.Step("Fetching memory.md…"))
            val memory = runCatching { drive.getMemoryMd() }.getOrNull()

            val allBuckets = db.bucketDao().getAllBuckets().first()
            val bucketsById = allBuckets.associateBy { it.id }
            val exportBuckets = if (includeVault) allBuckets else allBuckets.filter { !it.isVault }

            val stream = context.contentResolver.openOutputStream(uri)
                ?: error("Couldn't open the file you chose")

            var noteCount = 0
            var todoCount = 0
            var meetingCount = 0
            var eventCount = 0
            var journalCount = 0
            var subtaskCount = 0
            var chatCount = 0

            ZipOutputStream(BufferedOutputStream(stream)).use { zip ->
                // ── notes/<bucket>/<title>.md ──────────────────────────────────
                onProgress(Progress.Step("Writing notes…"))
                val usedNotePaths = mutableSetOf<String>()
                for (noteId in noteIds(db, includeVault)) {
                    currentCoroutineContext().ensureActive()
                    val note = db.noteDao().getNoteById(noteId) ?: continue
                    val bucket = bucketsById[note.bucketId]
                    // Belt and braces: the id query already excludes vault buckets, but a
                    // note whose bucket vanished mid-export must not slip through either.
                    if (!includeVault && bucket?.isVault != false) continue
                    val path = uniquePath(
                        usedNotePaths,
                        "notes/${safeName(bucket?.name ?: "Unfiled")}/${safeName(note.title)}",
                        ".md"
                    )
                    zip.writeTextEntry(path) { it.write(noteMarkdown(note, bucket)) }
                    noteCount++
                }

                // ── todos.csv ─────────────────────────────────────────────────
                onProgress(Progress.Step("Writing todos…"))
                val todos = if (includeVault) db.todoDao().getAllTodos().first()
                            else db.todoDao().getNonVaultTodos().first()
                zip.writeTextEntry("todos.csv") { w ->
                    w.writeCsvRow(
                        "id", "title", "bucket", "priority", "due", "reminder", "recurrence",
                        "done", "archived", "created", "updated", "estimateMinutes", "sourceMeetingId"
                    )
                    todos.forEach { todo ->
                        w.writeCsvRow(*todoRow(todo, bucketsById[todo.bucketId]))
                    }
                }
                todoCount = todos.size

                // ── meetings/<date>-<title>.md ────────────────────────────────
                onProgress(Progress.Step("Writing meetings…"))
                val usedMeetingPaths = mutableSetOf<String>()
                for (meetingId in meetingIds(db, includeVault)) {
                    currentCoroutineContext().ensureActive()
                    val meeting = db.meetingDao().getMeetingById(meetingId) ?: continue
                    val bucket = meeting.bucketId?.let { bucketsById[it] }
                    if (!includeVault && bucket?.isVault == true) continue
                    val date = dateFormatter.format(
                        Instant.ofEpochMilli(meeting.recordedAt).atZone(ZoneId.systemDefault())
                    )
                    val path = uniquePath(
                        usedMeetingPaths,
                        "meetings/$date-${safeName(meeting.title.ifBlank { "Untitled meeting" })}",
                        ".md"
                    )
                    zip.writeTextEntry(path) { it.write(meetingMarkdown(meeting, bucket)) }
                    meetingCount++
                }

                // ── subtasks.csv ──────────────────────────────────────────────
                // Written beside todos.csv rather than inside it, because a to-do has zero or
                // many. Filtered to the to-dos actually exported, so the non-vault export does
                // not carry the checklist of a vault to-do it deliberately left out.
                onProgress(Progress.Step("Writing subtasks…"))
                val exportedTodoIds = todos.map { it.id }.toSet()
                val subtasks = db.subtaskDao().getAllSubtasksOnce()
                    .filter { it.todoId in exportedTodoIds }
                zip.writeTextEntry("subtasks.csv") { w ->
                    w.writeCsvRow("todoId", "todoTitle", "title", "done", "sortOrder")
                    val titlesById = todos.associate { it.id to it.title }
                    subtasks.sortedWith(compareBy({ it.todoId }, { it.sortOrder })).forEach { s ->
                        w.writeCsvRow(
                            s.todoId.toString(), titlesById[s.todoId].orEmpty(), s.title,
                            s.isDone.toString(), s.sortOrder.toString()
                        )
                    }
                }
                subtaskCount = subtasks.size

                // ── journal/<date>-<id>.md ────────────────────────────────────
                //
                // The journal was absent from this export entirely — the most personal record
                // in the app, with all its template answers and Trends history, silently
                // missing from "the thing that survives the app itself".
                //
                // Both halves of the journal vault rule apply, as they do in JournalDao: an
                // entry is withheld when it is marked private *or* filed into a vault bucket.
                // Either one alone was a real leak on the web app, and an export is the one
                // artefact that leaves the device.
                onProgress(Progress.Step("Writing journal…"))
                val usedJournalPaths = mutableSetOf<String>()
                val templatesById = db.journalTemplateDao().getAllTemplatesIncludingDeleted()
                    .associateBy { it.id }
                for (entryId in journalIds(db, includeVault)) {
                    currentCoroutineContext().ensureActive()
                    val entry = db.journalDao().getEntryById(entryId) ?: continue
                    val bucket = entry.bucketId?.let { bucketsById[it] }
                    // Belt and braces, as with notes: a bucket that vanished mid-export, or an
                    // entry marked private since the id walk, must not slip through.
                    if (!includeVault && (entry.isPrivate || bucket?.isVault == true)) continue
                    val date = dateFormatter.format(
                        Instant.ofEpochMilli(entry.createdAt).atZone(ZoneId.systemDefault())
                    )
                    val path = uniquePath(usedJournalPaths, "journal/$date-${entry.id}", ".md")
                    zip.writeTextEntry(path) {
                        it.write(journalMarkdown(entry, bucket, templatesById[entry.templateId]?.name))
                    }
                    journalCount++
                }

                // ── journal-templates.md ──────────────────────────────────────
                // The questions the answers were given to. Without them a scale answer of "7"
                // is uninterpretable, which is the whole reason entries snapshot their fields.
                val templates = templatesById.values
                    .filter { it.deletedAt == null }
                    .filter { t ->
                        if (includeVault) true
                        else !t.isPrivateByDefault &&
                            t.bucketId?.let { bucketsById[it]?.isVault } != true
                    }
                    .sortedBy { it.name }
                if (templates.isNotEmpty()) {
                    zip.writeTextEntry("journal-templates.md") { w ->
                        w.write(templatesMarkdown(templates, bucketsById))
                    }
                }

                // ── chat/<date>-<title>.md ────────────────────────────────────
                // Chat is vault-closed by construction — it can only ever file into non-vault
                // buckets — so there is no vault dimension to filter here.
                onProgress(Progress.Step("Writing chat…"))
                val usedChatPaths = mutableSetOf<String>()
                for (thread in db.chatDao().getAllThreads().first()) {
                    currentCoroutineContext().ensureActive()
                    val messages = db.chatDao().getMessagesForThread(thread.id)
                    if (messages.isEmpty()) continue
                    val date = dateFormatter.format(
                        Instant.ofEpochMilli(thread.createdAt).atZone(ZoneId.systemDefault())
                    )
                    val path = uniquePath(
                        usedChatPaths,
                        "chat/$date-${safeName(thread.title.ifBlank { "Conversation" })}",
                        ".md"
                    )
                    zip.writeTextEntry(path) { it.write(chatMarkdown(thread, messages)) }
                    chatCount++
                }

                // ── calendar.csv ──────────────────────────────────────────────
                // Cached Google Calendar events. Not bucketed, so no vault dimension.
                onProgress(Progress.Step("Writing calendar…"))
                val events = db.calendarEventDao().getAllEventsOnce()
                zip.writeTextEntry("calendar.csv") { w ->
                    w.writeCsvRow(
                        "id", "title", "start", "end", "isAllDay",
                        "location", "calendarName", "colorHex", "cachedAt"
                    )
                    events.forEach { e ->
                        w.writeCsvRow(
                            e.id, e.title, iso(e.startMs), iso(e.endMs), e.isAllDay.toString(),
                            e.location.orEmpty(), e.calendarName.orEmpty(), e.colorHex.orEmpty(),
                            iso(e.cachedAt)
                        )
                    }
                }
                eventCount = events.size

                // ── buckets.csv ───────────────────────────────────────────────
                zip.writeTextEntry("buckets.csv") { w ->
                    w.writeCsvRow("name", "colorHex", "isVault")
                    exportBuckets.forEach { b ->
                        w.writeCsvRow(b.name, b.colorHex, b.isVault.toString())
                    }
                }

                // ── memory.md ─────────────────────────────────────────────────
                if (memory != null) {
                    zip.writeTextEntry("memory.md") { it.write(memory) }
                }

                // ── README.txt ────────────────────────────────────────────────
                zip.writeTextEntry("README.txt") { w ->
                    w.write(
                        readme(
                            exportedAt = exportedAt,
                            includeVault = includeVault,
                            memoryIncluded = memory != null,
                            noteCount = noteCount,
                            todoCount = todoCount,
                            meetingCount = meetingCount,
                            eventCount = eventCount,
                            bucketCount = exportBuckets.size,
                            journalCount = journalCount,
                            subtaskCount = subtaskCount,
                            chatCount = chatCount
                        )
                    )
                }
            }

            Summary(
                noteCount = noteCount,
                todoCount = todoCount,
                meetingCount = meetingCount,
                eventCount = eventCount,
                bucketCount = exportBuckets.size,
                journalCount = journalCount,
                subtaskCount = subtaskCount,
                chatCount = chatCount,
                includedVault = includeVault,
                memoryIncluded = memory != null
            )
        }
    }

    // ── Id walks ─────────────────────────────────────────────────────────────
    // The note and meeting DAOs only expose whole-row Flows, and a transcript history can
    // be large. These read the id column alone through RoomDatabase.query(), so the full
    // rows are then pulled — and released — one at a time as each zip entry is written.
    // The predicates mirror NoteDao.getNonVaultNotes / MeetingDao.getNonVaultMeetings exactly.

    private fun noteIds(db: AppDatabase, includeVault: Boolean): List<Long> {
        val sql = if (includeVault) {
            "SELECT id FROM notes WHERE deletedAt IS NULL ORDER BY updatedAt DESC"
        } else {
            """
            SELECT n.id FROM notes n
            INNER JOIN buckets b ON n.bucketId = b.id
            WHERE b.isVault = 0 AND n.deletedAt IS NULL
            ORDER BY n.updatedAt DESC
            """.trimIndent()
        }
        return db.query(sql, emptyArray<Any?>()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    /**
     * Journal entry ids, honouring **both** halves of the journal vault rule.
     *
     * An entry is hidden when it is marked private *or* filed into a vault bucket — the two are
     * independent, and filtering on `isPrivate` alone was a real leak on the web app, because on
     * the phone the bucket is usually what hides an entry and ticking Private as well is the
     * exception. `bucketId` is nullable and unfiled is the ordinary case, so a NULL bucket is
     * kept rather than joined away.
     *
     * Drafts are included, unlike every other journal query. Those exclusions exist to keep an
     * unfinished entry out of Claude, search and Drive; this file is a local snapshot Carl asked
     * for, and silently dropping his unfinished writing from "the thing that survives the app"
     * is the worse failure. They are marked as drafts in the file.
     */
    private fun journalIds(db: AppDatabase, includeVault: Boolean): List<Long> {
        val sql = if (includeVault) {
            "SELECT id FROM journal_entries WHERE deletedAt IS NULL ORDER BY createdAt DESC"
        } else {
            """
            SELECT id FROM journal_entries
            WHERE deletedAt IS NULL AND isPrivate = 0
              AND (bucketId IS NULL OR bucketId IN (SELECT id FROM buckets WHERE isVault = 0))
            ORDER BY createdAt DESC
            """.trimIndent()
        }
        return db.query(sql, emptyArray<Any?>()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    private fun meetingIds(db: AppDatabase, includeVault: Boolean): List<Long> {
        // bucketId is nullable on meetings, so un-bucketed meetings are kept and only
        // meetings filed into a vault bucket are excluded — an INNER JOIN would drop them.
        val sql = if (includeVault) {
            "SELECT id FROM meetings WHERE deletedAt IS NULL ORDER BY recordedAt DESC"
        } else {
            """
            SELECT id FROM meetings
            WHERE deletedAt IS NULL
              AND (bucketId IS NULL OR bucketId IN (SELECT id FROM buckets WHERE isVault = 0))
            ORDER BY recordedAt DESC
            """.trimIndent()
        }
        return db.query(sql, emptyArray<Any?>()).use { c ->
            buildList { while (c.moveToNext()) add(c.getLong(0)) }
        }
    }

    // ── Document builders ────────────────────────────────────────────────────

    private fun noteMarkdown(note: NoteEntity, bucket: BucketEntity?): String = buildString {
        appendLine("---")
        appendLine("title: ${yaml(note.title)}")
        appendLine("bucket: ${yaml(bucket?.name ?: "Unfiled")}")
        appendLine("created: ${iso(note.createdAt)}")
        appendLine("updated: ${iso(note.updatedAt)}")
        appendLine("tags: ${yaml(note.tags)}")
        appendLine("reminder: ${yaml(note.reminderAt?.let { iso(it) } ?: "")}")
        appendLine("---")
        appendLine()
        appendLine(note.content)
    }

    private fun journalMarkdown(
        entry: JournalEntryEntity,
        bucket: BucketEntity?,
        templateName: String?
    ): String = buildString {
        appendLine("---")
        appendLine("created: ${iso(entry.createdAt)}")
        appendLine("updated: ${iso(entry.updatedAt)}")
        appendLine("bucket: ${yaml(bucket?.name ?: "Unfiled")}")
        appendLine("template: ${yaml(templateName ?: "")}")
        appendLine("mood: ${yaml(entry.mood)}")
        appendLine("private: ${entry.isPrivate}")
        appendLine("draft: ${entry.isDraft}")
        appendLine("---")
        appendLine()
        if (entry.isDraft) {
            appendLine("> **Unfinished draft.**")
            appendLine()
        }
        if (entry.prompt.isNotBlank()) {
            // The prompt travels with the entry for the same reason it is stored with it: the
            // writing answers a question, and a year later the answer alone does not say which.
            appendLine("**Prompt:** ${entry.prompt}")
            appendLine()
        }
        appendLine(entry.content)
        // The rendered text above is what a reader wants; the raw answers are what a
        // spreadsheet wants. Both, because this file has to outlive the app that made it.
        if (entry.answersJson.isNotBlank() && entry.answersJson != "{}") {
            appendLine()
            appendLine("## Template answers (raw)")
            appendLine()
            appendLine("```json")
            appendLine(entry.answersJson)
            appendLine("```")
        }
    }

    private fun templatesMarkdown(
        templates: List<JournalTemplateEntity>,
        bucketsById: Map<Long, BucketEntity>
    ): String = buildString {
        appendLine("# Journal templates")
        appendLine()
        appendLine(
            "The questions the answers in `journal/` were given to. A scale answer of \"7\" " +
                "means nothing without the question and its anchors, which is why each entry " +
                "records the field definitions it was answered against."
        )
        templates.forEach { t ->
            appendLine()
            appendLine("## ${t.name}")
            appendLine()
            appendLine("- Default bucket: ${t.bucketId?.let { bucketsById[it]?.name } ?: "Unfiled"}")
            appendLine("- Private by default: ${t.isPrivateByDefault}")
            if (t.reminderRule.isNotBlank()) appendLine("- Reminder: ${t.reminderRule}")
            appendLine()
            appendLine("```json")
            appendLine(t.fieldsJson)
            appendLine("```")
        }
    }

    private fun chatMarkdown(
        thread: ChatThreadEntity,
        messages: List<ChatMessageEntity>
    ): String = buildString {
        appendLine("# ${thread.title.ifBlank { "Conversation" }}")
        appendLine()
        appendLine("- Started: ${iso(thread.createdAt)}")
        appendLine("- Last message: ${iso(thread.updatedAt)}")
        messages.forEach { m ->
            appendLine()
            appendLine("### ${if (m.isFromUser) "Carl" else "Claude"} · ${iso(m.createdAt)}")
            appendLine()
            appendLine(m.content)
        }
    }

    private fun meetingMarkdown(meeting: MeetingEntity, bucket: BucketEntity?): String = buildString {
        appendLine("# ${meeting.title.ifBlank { "Untitled meeting" }}")
        appendLine()
        appendLine("- Date: ${iso(meeting.recordedAt)}")
        appendLine("- Duration: ${formatDuration(meeting.durationMs)}")
        if (bucket != null) appendLine("- Bucket: ${bucket.name}")
        appendLine()
        appendLine("## Summary")
        appendLine()
        appendLine(meeting.summary.ifBlank { "_No summary._" })
        appendLine()
        appendLine("## Action items")
        appendLine()
        val items = runCatching {
            appJson.decodeFromString<List<ActionItem>>(meeting.pendingActionItems.ifBlank { "[]" })
        }.getOrDefault(emptyList())
        if (items.isEmpty()) {
            appendLine("_None outstanding._")
        } else {
            items.forEach { appendLine("- [ ] ${it.title} (${it.bucket})") }
        }
        appendLine()
        appendLine("## Transcript")
        appendLine()
        appendLine(meeting.transcript.ifBlank { "_No transcript._" })
    }

    private fun todoRow(todo: TodoEntity, bucket: BucketEntity?): Array<String> = arrayOf(
        todo.id.toString(),
        todo.title,
        bucket?.name ?: "Unfiled",
        com.carlmanning.carlsbrain.domain.model.Priority.fromRank(todo.priority).displayName,
        todo.dueDate?.let { iso(it) } ?: "",
        todo.reminderAt?.let { iso(it) } ?: "",
        todo.recurrence,
        todo.isDone.toString(),
        todo.isArchived.toString(),
        iso(todo.createdAt),
        iso(todo.updatedAt),
        todo.estimateMinutes?.toString() ?: "",
        todo.sourceMeetingId?.toString() ?: ""
    )

    private fun readme(
        exportedAt: Long,
        includeVault: Boolean,
        memoryIncluded: Boolean,
        noteCount: Int,
        todoCount: Int,
        meetingCount: Int,
        eventCount: Int,
        bucketCount: Int,
        journalCount: Int,
        subtaskCount: Int,
        chatCount: Int
    ): String = buildString {
        appendLine("Carl's Brain — data export")
        appendLine("Created ${iso(exportedAt)}")
        appendLine()
        appendLine("WHAT THIS IS")
        appendLine("A snapshot of everything in the app at the moment above, in plain formats")
        appendLine("that any computer can read. Nothing here needs Carl's Brain to open it.")
        appendLine()
        appendLine("THIS IS NOT A BACKUP YOU CAN RESTORE FROM")
        appendLine("There is no way to load this zip back into the app. It is a copy you can")
        appendLine("read, search, keep, or hand to something else. If you want the app itself")
        appendLine("restored, that is what Google Drive sync is for.")
        appendLine()
        appendLine("WHAT'S IN HERE")
        appendLine("  notes/<bucket>/<title>.md   $noteCount notes, one Markdown file each,")
        appendLine("                              with title/bucket/dates/tags at the top.")
        appendLine("  todos.csv                   $todoCount to-dos, one row each.")
        appendLine("  subtasks.csv                $subtaskCount subtasks, each naming its to-do.")
        appendLine("  journal/<date>-<id>.md      $journalCount journal entries, with the prompt they")
        appendLine("                              answered and any template scores, raw.")
        appendLine("  journal-templates.md        The questions those scores were answers to.")
        appendLine("  chat/<date>-<title>.md      $chatCount Claude conversations.")
        appendLine("  meetings/<date>-<title>.md  $meetingCount meetings: summary, action items,")
        appendLine("                              and the full transcript.")
        appendLine("  calendar.csv                $eventCount cached calendar events.")
        appendLine("  buckets.csv                 $bucketCount buckets, with colour and vault flag.")
        if (memoryIncluded) {
            appendLine("  memory.md                   Claude's long-term memory file from Drive.")
        } else {
            appendLine("  memory.md                   NOT INCLUDED — it lives in Google Drive and")
            appendLine("                              couldn't be fetched (likely offline).")
        }
        appendLine()
        appendLine("WHAT'S DELIBERATELY MISSING")
        appendLine("  Meeting audio recordings. Only the transcripts are here — the audio files")
        appendLine("  stay in Google Drive, where they were uploaded.")
        appendLine("  Deleted items sitting in Recently Deleted.")
        appendLine("  Attachments on notes, to-dos and journal entries — those stay in Drive too.")
        appendLine("  Unfinished journal drafts ARE included, marked as drafts, because losing")
        appendLine("  unfinished writing from a snapshot like this is the worse mistake.")
        appendLine()
        appendLine("VAULT")
        if (includeVault) {
            appendLine("  This export INCLUDES your Vault buckets.")
            appendLine("  This zip is NOT ENCRYPTED. Anything that can open a zip file can read")
            appendLine("  your Vault notes and to-dos — no PIN, no fingerprint, no app required.")
            appendLine("  Store it somewhere you'd be comfortable storing the contents themselves.")
        } else {
            appendLine("  Vault buckets and everything filed in them were left out of this export.")
            appendLine("  Journal entries marked Private were left out too, whatever bucket they")
            appendLine("  are in — private and vault are separate flags and either one hides an")
            appendLine("  entry, so this export honours both.")
        }
    }

    // ── Formatting helpers ───────────────────────────────────────────────────

    private fun iso(epochMillis: Long): String =
        if (epochMillis <= 0L) ""
        else isoFormatter.format(Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()))

    private fun formatDuration(ms: Long): String {
        if (ms <= 0L) return "unknown"
        val totalMinutes = ms / 60_000
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
    }

    /** Front-matter values are always quoted, so a colon or a `#` in a title can't break the block. */
    private fun yaml(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").trim() + "\""

    /**
     * Filesystem-safe file/folder name: anything outside a conservative set becomes `-`,
     * runs collapse, and the result is capped so long titles can't blow a path limit.
     */
    private fun safeName(raw: String): String {
        val cleaned = raw
            .replace(Regex("[^A-Za-z0-9 ._-]"), "-")
            .replace(Regex("[-\\s]+"), " ")
            .trim(' ', '.', '-')
            .take(80)
            .trim()
        return cleaned.ifBlank { "untitled" }
    }

    /**
     * Two notes can legitimately share a title. Collisions get `-2`, `-3`, … rather than
     * silently overwriting each other inside the zip.
     */
    private fun uniquePath(used: MutableSet<String>, base: String, extension: String): String {
        var candidate = base + extension
        var n = 2
        while (!used.add(candidate.lowercase())) {
            candidate = "$base-$n$extension"
            n++
        }
        return candidate
    }

    // ── Zip / CSV plumbing ───────────────────────────────────────────────────

    /**
     * Writes one entry and flushes it. The writer is deliberately never closed — closing it
     * would close the underlying ZipOutputStream and end the archive after the first file.
     */
    private inline fun ZipOutputStream.writeTextEntry(name: String, block: (Writer) -> Unit) {
        putNextEntry(ZipEntry(name))
        val writer = OutputStreamWriter(this, Charsets.UTF_8)
        block(writer)
        writer.flush()
        closeEntry()
    }

    private fun Writer.writeCsvRow(vararg fields: String) {
        write(fields.joinToString(",") { csv(it) })
        write("\r\n")
    }

    /** RFC 4180: quote anything containing a comma, quote, newline or edge whitespace. */
    private fun csv(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' } ||
            field != field.trim()
        return if (needsQuoting) "\"" + field.replace("\"", "\"\"") + "\"" else field
    }
}
