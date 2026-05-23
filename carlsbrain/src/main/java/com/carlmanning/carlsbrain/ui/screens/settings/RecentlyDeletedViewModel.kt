package com.carlmanning.carlsbrain.ui.screens.settings

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.local.entity.MeetingEntity
import com.carlmanning.carlsbrain.data.local.entity.NoteEntity
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity
import com.carlmanning.carlsbrain.data.local.entity.TombstoneEntity
import com.carlmanning.carlsbrain.data.remote.DriveRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

sealed class DeletedItem {
    data class DeletedNote(val entity: NoteEntity) : DeletedItem()
    data class DeletedTodo(val entity: TodoEntity) : DeletedItem()
    data class DeletedMeeting(val entity: MeetingEntity) : DeletedItem()
    val deletedAt: Long get() = when (this) {
        is DeletedNote -> entity.deletedAt ?: 0L
        is DeletedTodo -> entity.deletedAt ?: 0L
        is DeletedMeeting -> entity.deletedAt ?: 0L
    }
    val title: String get() = when (this) {
        is DeletedNote -> entity.title
        is DeletedTodo -> entity.title
        is DeletedMeeting -> entity.title
    }
}

class RecentlyDeletedViewModel(app: Application) : AndroidViewModel(app) {
    private val db = AppDatabase.getInstance(app)
    private val drive = DriveRepository(app)

    val deletedItems = combine(
        db.noteDao().getDeletedNotes(),
        db.todoDao().getDeletedTodos(),
        db.meetingDao().getDeletedMeetings()
    ) { notes, todos, meetings ->
        (notes.map { DeletedItem.DeletedNote(it) } +
         todos.map { DeletedItem.DeletedTodo(it) } +
         meetings.map { DeletedItem.DeletedMeeting(it) })
            .sortedByDescending { it.deletedAt }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun restore(item: DeletedItem) {
        viewModelScope.launch {
            when (item) {
                is DeletedItem.DeletedNote -> db.noteDao().restoreNoteFromBin(item.entity.id)
                is DeletedItem.DeletedTodo -> db.todoDao().restoreTodoFromBin(item.entity.id)
                is DeletedItem.DeletedMeeting -> db.meetingDao().restoreMeetingFromBin(item.entity.id)
            }
        }
    }

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
                is DeletedItem.DeletedMeeting -> {
                    db.meetingDao().deleteMeeting(item.entity)
                }
            }
        }
    }

    fun emptyBin() {
        viewModelScope.launch {
            val cutoff = System.currentTimeMillis() + 1000

            // Capture IDs before purging so tombstones and Drive cleanup are correct
            val deletedTodos = db.todoDao().getDeletedTodos().first()
            val deletedNotes = db.noteDao().getDeletedNotes().first()

            // Write tombstones so pull never re-inserts hard-purged items
            db.tombstoneDao().insertAll(
                deletedTodos.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_TODO) } +
                deletedNotes.map { TombstoneEntity(it.id, TombstoneEntity.TYPE_NOTE) }
            )

            // Best-effort Drive file deletion for notes — tombstone covers failures
            deletedNotes.forEach { note -> runCatching { drive.deleteNoteFile(note.id) } }

            db.noteDao().purgeOldDeletedNotes(cutoff)
            db.todoDao().purgeOldDeletedTodos(cutoff)
            db.meetingDao().purgeOldDeletedMeetings(cutoff)
        }
    }

    fun daysRemaining(deletedAt: Long): Int {
        val daysGone = ((System.currentTimeMillis() - deletedAt) / (24 * 60 * 60 * 1000)).toInt()
        return maxOf(0, 90 - daysGone)
    }
}
