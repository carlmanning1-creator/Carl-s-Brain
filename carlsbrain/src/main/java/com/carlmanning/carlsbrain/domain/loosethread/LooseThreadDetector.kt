package com.carlmanning.carlsbrain.domain.loosethread

import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.remote.ActionItem
import com.carlmanning.carlsbrain.data.remote.appJson
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.coroutines.flow.first
import kotlinx.serialization.decodeFromString

/** Where a loose thread came from. The kind decides how "Open" navigates. */
enum class ThreadKind { TODO, MEETING, JOURNAL_DRAFT, NOTE }

/**
 * Something Carl started and did not finish.
 *
 * @param key stable `KIND:refId` — what dismissals and snoozes are recorded against.
 * @param signal short phrase naming why this surfaced, e.g. "3 of 5 subtasks done, untouched 9 days".
 */
data class LooseThread(
    val kind: ThreadKind,
    val refId: Long,
    val title: String,
    val signal: String,
    val lastTouched: Long
) {
    val key: String get() = "${kind.name}:$refId"
}

/**
 * Finds started-but-unfinished work deterministically, in SQL and plain Kotlin.
 *
 * Claude is not asked *what* is loose — only, later and separately, how to phrase the one being
 * shown. That split is deliberate: the thresholds below are the part that has to be reliable and
 * offline, and a model asked to trawl the whole database for "loose ends" invents them.
 *
 * Thresholds differ per signal because the signals mean different things. A meeting action item
 * three days old is already rotting; a note untouched for three days is just a note.
 */
object LooseThreadDetector {

    private const val DAY = 24 * 60 * 60 * 1000L

    private const val STARTED_TODO_DAYS = 7L
    private const val MEETING_ACTION_DAYS = 3L
    private const val JOURNAL_DRAFT_DAYS = 2L
    private const val NOTE_DAYS = 14L
    private const val PINNED_URGENT_DAYS = 5L

    /** How long "Snooze" hides a thread for. One week — long enough to actually be a break. */
    const val SNOOZE_MS = 7 * DAY

    /**
     * @param vaultOpen when false, vault buckets contribute nothing — the same rule the rest of
     *   the app follows, applied by using the non-vault DAO queries rather than filtering after.
     */
    suspend fun find(
        db: AppDatabase,
        vaultOpen: Boolean,
        now: Long = System.currentTimeMillis()
    ): List<LooseThread> {
        val found = mutableListOf<LooseThread>()

        val todos = if (vaultOpen) db.todoDao().getActiveTodos().first()
                    else db.todoDao().getActiveNonVaultTodos().first()

        // ── Started to-dos ────────────────────────────────────────────
        // The strongest signal in the app: partly-ticked subtasks are proof he began.
        val subtasks = db.subtaskDao().getSubtasksForTodos(todos.map { it.id }).first()
            .groupBy { it.todoId }
        for (todo in todos) {
            val subs = subtasks[todo.id].orEmpty()
            if (subs.isEmpty()) continue
            val done = subs.count { it.isDone }
            if (done == 0 || done == subs.size) continue
            val idleDays = (now - todo.updatedAt) / DAY
            if (idleDays < STARTED_TODO_DAYS) continue
            found += LooseThread(
                ThreadKind.TODO, todo.id, todo.title,
                "$done of ${subs.size} steps done, untouched $idleDays days",
                todo.updatedAt
            )
        }

        // ── Pinned or urgent, and going nowhere ───────────────────────
        // Carl marking something urgent and then not touching it for five days is itself the
        // signal — the priority says it mattered, the silence says something stalled.
        for (todo in todos) {
            if (found.any { it.kind == ThreadKind.TODO && it.refId == todo.id }) continue
            val flagged = todo.isPinned || todo.priority <= Priority.HIGH.rank
            if (!flagged) continue
            val idleDays = (now - todo.updatedAt) / DAY
            if (idleDays < PINNED_URGENT_DAYS) continue
            val label = if (todo.isPinned) "Pinned" else Priority.fromRank(todo.priority).displayName
            found += LooseThread(
                ThreadKind.TODO, todo.id, todo.title,
                "$label, untouched $idleDays days", todo.updatedAt
            )
        }

        // ── Meeting action items never approved ───────────────────────
        // These are the ones that vanish: Claude extracted them, Carl never turned them into
        // to-dos, and nothing else in the app ever mentions them again.
        // getAllMeetings has no vault variant, so the filter is applied here against the bucket
        // list — a meeting filed in a vault bucket must not leak its title into the Dashboard.
        val vaultBucketIds = db.bucketDao().getAllBuckets().first()
            .filter { it.isVault }.map { it.id }.toSet()
        db.meetingDao().getAllMeetings().first()
            .filter { meeting ->
                val bucketId = meeting.bucketId
                vaultOpen || bucketId == null || bucketId !in vaultBucketIds
            }
            .filter { it.deletedAt == null }
            .filter { (now - it.recordedAt) / DAY >= MEETING_ACTION_DAYS }
            .forEach { meeting ->
                // pendingActionItems is a JSON array, not newline-delimited text — every other
                // reader decodes it. Testing isNotBlank() here meant "[]", which is what a
                // fully-approved meeting stores, counted as an outstanding thread forever.
                val pending = runCatching {
                    appJson.decodeFromString<List<ActionItem>>(
                        meeting.pendingActionItems.ifBlank { "[]" }
                    )
                }.getOrDefault(emptyList())
                if (pending.isEmpty()) return@forEach
                val count = pending.size
                found += LooseThread(
                    ThreadKind.MEETING, meeting.id,
                    meeting.title.ifBlank { "Untitled meeting" },
                    "$count action item${if (count == 1) "" else "s"} still unapproved",
                    meeting.recordedAt
                )
            }

        // ── Journal drafts ────────────────────────────────────────────
        // Drafts are excluded from every other query on purpose, which is exactly why they need
        // surfacing here: nothing else would ever remind him one exists.
        db.journalDao().getAllEntriesIncludingDeleted()
            .filter { it.deletedAt == null && it.isDraft }
            .filter { (now - it.updatedAt) / DAY >= JOURNAL_DRAFT_DAYS }
            // Both halves of the vault rule: the entry's own privacy flag and its bucket. The
            // journal DAO does this in SQL, but drafts are read through the unfiltered query.
            .filter { entry ->
                val bucketId = entry.bucketId
                vaultOpen || (!entry.isPrivate && (bucketId == null || bucketId !in vaultBucketIds))
            }
            .forEach { entry ->
                found += LooseThread(
                    ThreadKind.JOURNAL_DRAFT, entry.id,
                    entry.content.lines().firstOrNull { it.isNotBlank() }?.take(60)
                        ?: "Journal draft",
                    "Unfinished journal entry", entry.updatedAt
                )
            }

        // ── Notes with unticked checkboxes ────────────────────────────
        // Only notes that contain a checklist he part-filled. Every note being "loose" after a
        // fortnight would drown the real signals — most notes are reference, not work.
        val notes = if (vaultOpen) db.noteDao().getAllNotes().first()
                    else db.noteDao().getNonVaultNotes().first()
        notes.filter { it.deletedAt == null }
            .filter { (now - it.updatedAt) / DAY >= NOTE_DAYS }
            .forEach { note ->
                val open = note.content.lines().count { it.trimStart().startsWith("- [ ]") }
                val ticked = note.content.lines().count {
                    val t = it.trimStart()
                    t.startsWith("- [x]", ignoreCase = true)
                }
                if (open == 0 || ticked == 0) return@forEach
                found += LooseThread(
                    ThreadKind.NOTE, note.id,
                    note.title.ifBlank { note.content.lines().first().take(60) },
                    "$ticked done, $open still open on this list", note.updatedAt
                )
            }

        // ── Carl's own decisions win ──────────────────────────────────
        val states = db.looseThreadStateDao().getAll().associateBy { it.key }
        return found
            .filter { thread ->
                val state = states[thread.key] ?: return@filter true
                state.dismissedAt == null && state.snoozedUntil <= now
            }
            .sortedBy { it.lastTouched }
    }
}
