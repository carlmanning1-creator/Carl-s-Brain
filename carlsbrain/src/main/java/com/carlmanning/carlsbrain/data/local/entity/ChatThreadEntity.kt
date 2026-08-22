package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A chat conversation.
 *
 * @param isSynced whether Drive holds the current version of this thread *and its messages*.
 *   Cleared whenever a message is added, because the file on Drive is the whole conversation:
 *   a thread whose row has not changed but whose messages have is still stale. Same
 *   self-healing rule as notes and journal entries — a thread marked synced whose file has
 *   gone missing from Drive is marked unsynced again and re-uploaded.
 */
@Entity(tableName = "chat_threads")
data class ChatThreadEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val title: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false
)
