package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A journal entry — deliberately its own entity, not a flagged note.
 *
 * Keeping it separate is what lets journal entries carry their own fields (the prompt they
 * were written against, an explicit privacy flag), stay out of every Notes query without each
 * of those queries needing a filter, and be excluded from Claude or search independently of
 * notes. A boolean on NoteEntity would have leaked entries into Notes views the moment any
 * query was missed, which is exactly the class of silent bug this project keeps hitting.
 *
 * @param prompt the question shown when the entry was written, stored with it so an entry
 *   still reads correctly after the prompt is edited in Settings. Blank for a free-form entry.
 * @param isPrivate hidden unless the vault is open, and never included in notifications or in
 *   anything sent to Claude. Independent of buckets: an entry is private on its own terms
 *   rather than by living in a vault bucket, because journalling is one place Carl may want a
 *   single entry hidden without moving it somewhere else.
 * @param attachments comma-separated Drive file ids, the same encoding notes and todos use.
 *   Photos are a bare id; other files are `file:<name>:<id>`. Attachments on a private entry are
 *   uploaded like any other — Carl's explicit decision — so the privacy flag governs display in
 *   the app, not visibility in his own Drive.
 * @param templateId the template this entry was written from, or null for a free-form entry.
 * @param answersJson serialised `EntryAnswers` — the structured answers plus a snapshot of the
 *   field definitions they were given against, so a later edit to the template cannot change
 *   what a past entry means. Blank for a free-form entry.
 * @param isDraft an entry abandoned part-way and kept so it can be finished. Drafts are shown
 *   in the list marked DRAFT, and are excluded from Claude, search, the private count and the
 *   Drive push **in SQL** rather than by each call site remembering — an unfinished entry
 *   reaching an API would be exactly the kind of silent leak this table is shaped to prevent.
 * @param deletedAt soft delete, matching notes and todos — Recently Deleted, 90-day purge.
 */
@Entity(
    tableName = "journal_entries",
    // Every index here must also be created by the migration, and vice versa. Room compares the
    // two exactly and throws "Migration didn't properly handle" on the first database access if
    // they differ — which, since that access happens during startup, is a crash on launch.
    indices = [
        Index("createdAt"),
        Index("isPrivate"),
        Index("deletedAt"),
        Index("isDraft")
    ]
)
data class JournalEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val content: String = "",
    val prompt: String = "",
    val isPrivate: Boolean = false,
    /** Optional one-line mood/summary Carl can set; never inferred by Claude. */
    val mood: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val isSynced: Boolean = false,
    val attachments: String = "",
    val templateId: Long? = null,
    val answersJson: String = "",
    val isDraft: Boolean = false,
    val deletedAt: Long? = null
)
