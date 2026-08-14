package com.carlmanning.carlsbrain.data.export

import android.content.Context
import android.net.Uri
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.BucketEntity
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
                            bucketCount = exportBuckets.size
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
        bucketCount: Int
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
        appendLine("  Deleted items sitting in Recently Deleted, and chat history.")
        appendLine()
        appendLine("VAULT")
        if (includeVault) {
            appendLine("  This export INCLUDES your Vault buckets.")
            appendLine("  This zip is NOT ENCRYPTED. Anything that can open a zip file can read")
            appendLine("  your Vault notes and to-dos — no PIN, no fingerprint, no app required.")
            appendLine("  Store it somewhere you'd be comfortable storing the contents themselves.")
        } else {
            appendLine("  Vault buckets and everything filed in them were left out of this export.")
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
