package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "meetings")
data class MeetingEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String = "",
    val recordedAt: Long = System.currentTimeMillis(),
    val durationMs: Long = 0,
    val localAudioPath: String = "",
    val driveFolderId: String = "",
    val driveAudioFileId: String = "",
    val transcript: String = "",
    val summary: String = "",
    val pendingActionItems: String = "",
    val status: String = "IDLE",
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)
