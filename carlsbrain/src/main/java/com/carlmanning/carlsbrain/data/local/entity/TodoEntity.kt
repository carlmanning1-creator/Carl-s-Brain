package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.carlmanning.carlsbrain.domain.model.Priority
import com.carlmanning.carlsbrain.domain.model.Recurrence
import com.carlmanning.carlsbrain.domain.model.Todo

@Entity(
    tableName = "todos",
    foreignKeys = [
        ForeignKey(
            entity = BucketEntity::class,
            parentColumns = ["id"],
            childColumns = ["bucketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bucketId"), Index("isDone"), Index("priority"), Index("deletedAt")]
)
data class TodoEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val bucketId: Long,
    val priority: Int = Priority.NORMAL.rank,
    val dueDate: Long? = null,
    val reminderAt: Long? = null,
    val recurrence: String = Recurrence.None.toStorageString(),
    val calendarEventId: String? = null,
    val isDone: Boolean = false,
    val isArchived: Boolean = false,
    val archivedAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val sortOrder: Int = 0,
    val isPinned: Boolean = false,
    val attachments: String = "",
    val deletedAt: Long? = null,
    val leadDays: Int = 0,
    val sourceMeetingId: Long? = null,
    val sourceNoteId: Long? = null,
    val estimateMinutes: Int? = null
) {
    fun toDomain(): Todo = Todo(
        id = id,
        title = title,
        bucketId = bucketId,
        priority = Priority.fromRank(priority),
        dueDate = dueDate,
        reminderAt = reminderAt,
        recurrence = Recurrence.fromStorageString(recurrence),
        calendarEventId = calendarEventId,
        isDone = isDone,
        isPinned = isPinned,
        estimateMinutes = estimateMinutes,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isSynced = isSynced
    )

    companion object {
        fun fromDomain(todo: Todo): TodoEntity = TodoEntity(
            id = todo.id,
            title = todo.title,
            bucketId = todo.bucketId,
            priority = todo.priority.rank,
            dueDate = todo.dueDate,
            reminderAt = todo.reminderAt,
            recurrence = todo.recurrence.toStorageString(),
            calendarEventId = todo.calendarEventId,
            isDone = todo.isDone,
            isPinned = todo.isPinned,
            estimateMinutes = todo.estimateMinutes,
            createdAt = todo.createdAt,
            updatedAt = todo.updatedAt,
            isSynced = todo.isSynced
        )
    }
}
