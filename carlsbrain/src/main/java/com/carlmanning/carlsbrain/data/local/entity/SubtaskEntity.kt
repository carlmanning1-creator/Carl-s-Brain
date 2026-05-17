package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subtasks",
    foreignKeys = [ForeignKey(
        entity = TodoEntity::class,
        parentColumns = ["id"],
        childColumns = ["todoId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("todoId")]
)
data class SubtaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val todoId: Long,
    val title: String,
    val isDone: Boolean = false,
    val sortOrder: Int = 0
)
