package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.JournalEntryEntity
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class DeletedItem {
    data class DeletedNote(val entity: NoteEntity) : DeletedItem()
    data class DeletedTodo(val entity: TodoEntity) : DeletedItem()
    data class DeletedMeeting(val entity: MeetingEntity) : DeletedItem()
    data class DeletedJournal(val entity: JournalEntryEntity) : DeletedItem()
    val deletedAt: Long get() = when (this) {
        is DeletedNote -> entity.deletedAt ?: 0L
        is DeletedTodo -> entity.deletedAt ?: 0L
        is DeletedMeeting -> entity.deletedAt ?: 0L
        is DeletedJournal -> entity.deletedAt ?: 0L
    }
    val title: String get() = when (this) {
        is DeletedNote -> entity.title
        is DeletedTodo -> entity.title
        is DeletedMeeting -> entity.title
        // Journal entries have no title, so the first line stands in for one — the same thing
        // the loose-thread sheet shows for a draft.
        is DeletedJournal ->
            entity.content.lines().firstOrNull { it.isNotBlank() }?.take(60).orEmpty()
    }
}

/**
 * The 90-day recycle bin.
 *
 * ## The vault rule applies here too
 *
 * The bin was built from the unfiltered `getDeleted*` queries regardless of vault state, so
 * every deleted vault item listed its title for the three months it sat here — the vault rule
 * was applied to the live lists and then simply stopped at the recycle bin. The filtering is in
 * SQL, in vault-open/closed DAO pairs, like everywhere else in the app.
 *
 * ## Journal entries belong in it
 *
 * They were missing entirely, even though they are soft-deleted like everything else and
 * `MidnightCleanupWorker` purges them at ninety days. A journal entry deleted by mistake had no
 * route back at all and was destroyed silently three months later.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RecentlyDeletedViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)

    private val vaultOpen = MutableStateFlow(false)

    /** Set by the screen from the app-wide vault state; drives which DAO variant is read. */
    fun setVaultVisible(open: Boolean) { vaultOpen.value = open }

    val deletedItems = vaultOpen.flatMapLatest { open ->
        combine(
            if (open) db.noteDao().getDeletedNotes() else db.noteDao().getDeletedNonVaultNotes(),
            if (open) db.todoDao().getDeletedTodos() else db.todoDao().getDeletedNonVaultTodos(),
            if (open) db.meetingDao().getDeletedMeetings()
            else db.meetingDao().getDeletedNonVaultMeetings(),
            if (open) db.journalDao().getDeletedEntries()
            else db.journalDao().getDeletedVisibleEntries()
        ) { notes, todos, meetings, journal ->
            (notes.map { DeletedItem.DeletedNote(it) } +
             todos.map { DeletedItem.DeletedTodo(it) } +
             meetings.map { DeletedItem.DeletedMeeting(it) } +
             journal.map { DeletedItem.DeletedJournal(it) })
                .sortedByDescending { it.deletedAt }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(item: DeletedItem) {
        viewModelScope.launch {
            when (item) {
                is DeletedItem.DeletedNote -> db.noteDao().restoreNoteFromBin(item.entity.id)
                is DeletedItem.DeletedTodo -> db.todoDao().restoreTodoFromBin(item.entity.id)
                is DeletedItem.DeletedMeeting -> db.meetingDao().restoreMeetingFromBin(item.entity.id)
                is DeletedItem.DeletedJournal -> db.journalDao().restoreEntry(item.entity.id)
            }
        }
    }

    /**
     * Removes one item for good.
     *
     * Every type writes a tombstone, and every type with a Drive file deletes it. Meetings wrote
     * no tombstone and journal entries wrote neither, so a purged item whose Drive artefact
     * outlived it was re-inserted by the next pull as though it were new — "delete permanently"
     * appearing not to have worked at all.
     */
    fun deletePermanently(item: DeletedItem) {
        viewModelScope.launch {
            when (item) {
                is DeletedItem.DeletedTodo -> {
                    db.tombstoneDao().insert(
                        TombstoneEntity(item.entity.id, TombstoneEntity.TYPE_TODO)
                    )
                    db.todoDao().deleteTodo(item.entity)
                }
                is DeletedItem.DeletedNote -> {
                    db.tombstoneDao().insert(
                        TombstoneEntity(item.entity.id, TombstoneEntity.TYPE_NOTE)
                    )
                    runCatching { drive.deleteNoteFile(item.entity.id) }
                    db.noteDao().deleteNoteById(item.entity.id)
                }
                is DeletedItem.DeletedJournal -> {
                    db.tombstoneDao().insert(
                        TombstoneEntity(item.entity.id, TombstoneEntity.TYPE_JOURNAL)
                    )
                    runCatching { drive.deleteJournalEntry(item.entity.id) }
                    db.journalDao().deleteEntry(item.entity)
                }
                is DeletedItem.DeletedMeeting -> {
                    db.tombstoneDao().insert(
                        TombstoneEntity(item.entity.id, TombstoneEntity.TYPE_MEETING)
                    )
                    // The folder holds the audio, transcript and summary — all of it goes.
                    runCatching { drive.deleteMeetingFolder(item.entity.driveFolderId) }
                    runCatching {
                        if (item.entity.localAudioPath.isNotBlank()) {
                            java.io.File(item.entity.localAudioPath).delete()
                        }
                    }
                    db.meetingDao().deleteMeeting(item.entity)
                }
            }
        }
    }

    /**
     * Empties the whole bin.
     *
     * Deliberately reads the *unfiltered* queries: emptying the bin empties it, and silently
     * leaving vault items behind because the vault happened to be closed would be worse than
     * either alternative — Carl would believe they were gone. The screen's confirmation says so.
     */
    fun emptyBin() {
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() + 1000

            // Captured before purging, so tombstones and Drive cleanup are correct
            val deletedTodos = db.todoDao().getDeletedTodos().first()
            val deletedNotes = db.noteDao().getDeletedNotes().first()
            val deletedJournal = db.journalDao().getDeletedEntries().first()
            val deletedMeetings = db.meetingDao().getDeletedMeetings().first()

            // Write tombstones so a pull never re-inserts a hard-purged item
            db.tombstoneDao().insertAll(
                deletedTodos.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_TODO) } +
                deletedNotes.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_NOTE) } +
                deletedJournal.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_JOURNAL) } +
                deletedMeetings.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_MEETING) }
            )

            // Best-effort Drive cleanup — the tombstones cover any failure here.
            deletedNotes.forEach { runCatching { drive.deleteNoteFile(it.id) } }
            deletedJournal.forEach { runCatching { drive.deleteJournalEntry(it.id) } }
            deletedMeetings.forEach { meeting ->
                runCatching { drive.deleteMeetingFolder(meeting.driveFolderId) }
                runCatching {
                    if (meeting.localAudioPath.isNotBlank()) {
                        java.io.File(meeting.localAudioPath).delete()
                    }
                }
            }

            db.noteDao().purgeOldDeletedNotes(cutoff)
            db.todoDao().purgeOldDeletedTodos(cutoff)
            db.meetingDao().purgeOldDeletedMeetings(cutoff)
            db.journalDao().purgeOldDeletedEntries(cutoff)
        }
    }

    fun daysRemaining(deletedAt: Long): Int {
        val daysGone = ((System.currentTimeMillis() - deletedAt) / (24 * 60 * 60 * 1000)).toInt()
        return maxOf(0, 90 - daysGone)
    }
}
