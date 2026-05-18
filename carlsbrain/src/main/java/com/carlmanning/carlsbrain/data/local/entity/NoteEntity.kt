package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.carlmanning.carlsbrain.domain.model.Note

@Entity(
    tableName = "notes",
    foreignKeys = [
        ForeignKey(
            entity = BucketEntity::class,
            parentColumns = ["id"],
            childColumns = ["bucketId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("bucketId")]
)
data class NoteEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val content: String,
    val bucketId: Long,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val attachments: String = "",  // comma-separated Drive file IDs
    val reminderAt: Long? = null,
    val sortOrder: Int = 0,
    val tags: String = "",
    val isPinned: Boolean = false
) {
    fun toDomain(): Note = Note(
        id = id,
        title = title,
        content = content,
        bucketId = bucketId,
        createdAt = createdAt,
        updatedAt = updatedAt,
        isSynced = isSynced,
        attachments = if (attachments.isBlank()) emptyList()
                      else attachments.split(",").filter { it.isNotBlank() }
    )

    companion object {
        fun fromDomain(note: Note): NoteEntity = NoteEntity(
            id = note.id,
            title = note.title,
            content = note.content,
            bucketId = note.bucketId,
            createdAt = note.createdAt,
            updatedAt = note.updatedAt,
            isSynced = note.isSynced,
            attachments = note.attachments.joinToString(",")
        )
    }
}
