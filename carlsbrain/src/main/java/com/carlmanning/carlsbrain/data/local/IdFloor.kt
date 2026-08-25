package com.carlmanning.carlsbrain.data.local

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Stops a second device minting ids that already belong to something on Drive.
 *
 * ## The collision
 *
 * Room's `autoGenerate` ids start at 1 on a fresh install. Carl's existing phone has notes 1..N
 * published to Drive. Install the app on a new phone and the first thing anyone does is capture
 * something — but the Drive sync runs every fifteen minutes, so that capture can easily happen
 * before the first pull has landed. It gets id 1.
 *
 * The pull then skips note 1, because a row with that id already exists locally. The push
 * uploads the local note 1 over `note_1.md`. A note Carl wrote months ago is overwritten by an
 * unrelated one, with nothing anywhere reporting it.
 *
 * ## The fix
 *
 * On a genuinely fresh install, seed each id sequence from the clock instead of from zero. The
 * web app already mints ids this way, and the two have coexisted without collision since:
 * Room's own ids are small integers, epoch milliseconds are not, and two devices seeded a
 * second apart cannot overlap unless one of them creates a million rows in that second.
 *
 * This runs *before* the first sync rather than after it, which is the point — waiting until
 * the pull completes would leave exactly the window the bug lives in.
 */
object IdFloor {

    /**
     * The tables whose ids are published to Drive under that id.
     *
     * Only these matter. Subtasks travel by value, buckets are matched by name, and meetings
     * are keyed by their Drive folder — none of them can collide across devices.
     */
    private val SYNCED_TABLES = listOf("notes", "todos", "journal_entries", "chat_threads")

    /**
     * Seeds the id sequences on a fresh database.
     *
     * Deliberately conservative: it does nothing unless every synced table is empty, so it can
     * never renumber anything on Carl's existing phone even if it were called by mistake.
     *
     * @return true if the floor was applied, false if the database already had content.
     */
    fun applyIfFresh(db: SupportSQLiteDatabase, nowMs: Long = System.currentTimeMillis()): Boolean {
        val empty = SYNCED_TABLES.all { table ->
            db.query("SELECT COUNT(*) FROM $table").use { cursor ->
                cursor.moveToFirst() && cursor.getLong(0) == 0L
            }
        }
        if (!empty) return false

        SYNCED_TABLES.forEach { table ->
            // sqlite_sequence holds the last id used, so the next row gets seq + 1. The table
            // exists as soon as any AUTOINCREMENT table does, which Room guarantees here.
            //
            // DELETE then INSERT rather than INSERT OR REPLACE: sqlite_sequence has no unique
            // index on `name`, so REPLACE has nothing to match on and would append a second row
            // for the same table — which SQLite then reads unpredictably.
            db.execSQL("DELETE FROM sqlite_sequence WHERE name = ?", arrayOf(table))
            db.execSQL(
                "INSERT INTO sqlite_sequence (name, seq) VALUES (?, ?)",
                arrayOf(table, nowMs)
            )
        }
        return true
    }
}
