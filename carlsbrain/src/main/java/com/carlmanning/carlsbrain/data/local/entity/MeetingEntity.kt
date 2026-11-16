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
    val deletedAt: Long? = null,
    /** Set only on meetings Fireflies transcribed before it was removed in v2.28. Kept, not migrated. */
    val firefliesId: String? = null,
    /**
     * Which rung of the ladder produced the transcript: WHISPER, LIVE or MANUAL — or FIREFLIES
     * on meetings from before v2.28, when Fireflies was removed.
     *
     * Recorded because the ladder is silent about itself otherwise. A Fireflies transcript had
     * speaker labels and a Whisper one does not, and when a meeting reads oddly the first useful
     * question is which of them wrote it. Blank on meetings recorded before this was added.
     */
    val transcriptSource: String = "",
    val bucketId: Long? = null
)
