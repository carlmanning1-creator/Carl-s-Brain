package com.carlmanning.carlsbrain.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * A journal template Carl has built: a named set of typed questions.
 *
 * Templates are data, not code. The two seeded ones (Training, Kink) are ordinary rows he can
 * rename, reorder, re-question or delete — nothing about them is privileged.
 *
 * @param fieldsJson serialised `List<TemplateField>`. Held as JSON rather than a child table
 *   because fields are only ever read and written as a whole template, never queried
 *   individually — a join table would buy nothing and cost a migration every time the field
 *   model gains an attribute.
 * @param isPrivateByDefault whether entries from this template start with the private toggle
 *   on. The Kink template uses it so Carl does not have to remember.
 * @param builtInKey identifies a seeded template so the seeder can tell "not created yet" from
 *   "created and then deliberately deleted", and never resurrects one he has thrown away.
 */
@Entity(tableName = "journal_templates")
data class JournalTemplateEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val fieldsJson: String = "",
    val isPrivateByDefault: Boolean = false,
    val sortOrder: Int = 0,
    val builtInKey: String = "",
    /** Bucket new entries from this template are filed into, or null to leave them unfiled. */
    val bucketId: Long? = null,
    /**
     * When to nudge Carl to write one, as `DOW:HH:MM` (e.g. `SUN:10:00`), or blank for never.
     *
     * A string rather than columns because it is read and written whole, and because the next
     * shape this needs — "every weekday", "the day after a session" — should not each cost a
     * migration.
     */
    val reminderRule: String = "",
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    val deletedAt: Long? = null
)

/**
 * A named list of options that fields point at instead of carrying their own copy.
 *
 * Exists because Carl asked for two things at once: Secondary Activities offering the same list
 * as Main Activities, and being able to edit those options in settings. With a copy per field,
 * the first edit would silently desynchronise the two.
 *
 * @param optionsJson serialised `List<String>`.
 */
@Entity(tableName = "journal_option_lists")
data class JournalOptionListEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val name: String = "",
    val optionsJson: String = "",
    val builtInKey: String = "",
    val updatedAt: Long = System.currentTimeMillis()
)
