package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * What Carl has already said about a loose thread — snoozed, or dead.
 *
 * Keyed by `KIND:refId` rather than by a foreign key, because a thread can point at a to-do, a
 * meeting, a note or a journal draft and there is no one table to reference. Rows are cheap and
 * few; nothing prunes them until the thing they refer to is gone, which the detector handles by
 * simply never producing that key again.
 *
 * Deliberately never synced. "I dealt with this" is a moment on one device, and restoring a
 * six-month-old dismissal onto a new phone would silently hide work Carl might well want back.
 */
@Entity(tableName = "loose_thread_state")
data class LooseThreadStateEntity(
    @PrimaryKey val key: String,
    /** Hidden until this instant. 0 means not snoozed. */
    val snoozedUntil: Long = 0L,
    /** Non-null once Carl has declared it dead — never surfaced again. */
    val dismissedAt: Long? = null
)
