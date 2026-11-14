package com.carlmanning.carlsbrain.data.today

import android.content.Context
import org.json.JSONObject

/**
 * The small amount of state the Today surfaces need that is not a to-do.
 *
 * Plain SharedPreferences, read synchronously, because every reader is a broadcast receiver or
 * a widget render with a few seconds to live — not a place to wait on a DataStore flow.
 *
 * **Never synced**, and deliberately not in Room:
 *  - A snooze is "not now, on this phone". Restoring last month's snoozes onto a new device
 *    would hide work, the same reason loose-thread dismissals do not travel.
 *  - Keeping it out of the database means none of this needed a migration.
 *
 * Every read tolerates garbage — a corrupt entry is treated as absent, never thrown.
 */
object TodayState {
    private const val FILE = "today_surfaces"
    private const val KEY_SNOOZES = "snoozes"
    private const val KEY_UNDO = "undo"
    private const val KEY_PINNED_DISMISSED = "pinned_dismissed"
    private const val KEY_CHECKIN_PREFIX = "checkin_line_"

    /** How long a completion can be taken back, on every surface. */
    const val UNDO_WINDOW_MS = 5_000L

    /**
     * A completion that can still be undone.
     *
     * [spawnedId] is the next occurrence a recurring to-do produced. It has to be kept:
     * CompleteTodoUseCase.undoDone removes it, and an undo that forgot it would leave a
     * duplicate behind.
     */
    data class Undo(
        val todoId: Long,
        val spawnedId: Long?,
        val title: String,
        val expiresAt: Long,
        /** Where it sat on the widget, so "Marked done · Undo" can take its place. */
        val row: Int = 0
    )

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    // ── Snooze ────────────────────────────────────────────────────────────────

    /** To-do id → when it may reappear. Expired entries are dropped on the way out. */
    fun snoozes(context: Context, now: Long = System.currentTimeMillis()): Map<Long, Long> =
        runCatching {
            val json = JSONObject(prefs(context).getString(KEY_SNOOZES, "{}") ?: "{}")
            val out = HashMap<Long, Long>()
            json.keys().forEach { key ->
                val id = key.toLongOrNull() ?: return@forEach
                val until = json.optLong(key, 0L)
                if (until > now) out[id] = until
            }
            out
        }.getOrDefault(emptyMap())

    fun snooze(context: Context, todoId: Long, until: Long) {
        val current = snoozes(context).toMutableMap()
        current[todoId] = until
        writeSnoozes(context, current)
    }

    fun clearSnooze(context: Context, todoId: Long) {
        val current = snoozes(context).toMutableMap()
        if (current.remove(todoId) != null) writeSnoozes(context, current)
    }

    private fun writeSnoozes(context: Context, map: Map<Long, Long>) {
        val json = JSONObject()
        map.forEach { (id, until) -> json.put(id.toString(), until) }
        prefs(context).edit().putString(KEY_SNOOZES, json.toString()).apply()
    }

    // ── Undo ──────────────────────────────────────────────────────────────────

    /** The pending undo, or null once its window has closed. */
    fun undo(context: Context, now: Long = System.currentTimeMillis()): Undo? =
        runCatching {
            val raw = prefs(context).getString(KEY_UNDO, null) ?: return null
            val json = JSONObject(raw)
            val undo = Undo(
                todoId = json.getLong("todoId"),
                spawnedId = if (json.has("spawnedId")) json.getLong("spawnedId") else null,
                title = json.optString("title"),
                expiresAt = json.getLong("expiresAt"),
                row = json.optInt("row", 0)
            )
            if (undo.expiresAt > now) undo else null
        }.getOrNull()

    /** commit(), not apply(): the surfaces are redrawn straight after and must read this. */
    fun setUndo(context: Context, undo: Undo?) {
        val editor = prefs(context).edit()
        if (undo == null) {
            editor.remove(KEY_UNDO)
        } else {
            val json = JSONObject()
                .put("todoId", undo.todoId)
                .put("title", undo.title)
                .put("expiresAt", undo.expiresAt)
                .put("row", undo.row)
            if (undo.spawnedId != null) json.put("spawnedId", undo.spawnedId)
            editor.putString(KEY_UNDO, json.toString())
        }
        editor.commit()
    }

    // ── Pinned notification ───────────────────────────────────────────────────

    /**
     * Carl swiped the pinned notification away (Android 14+ allows it). Honoured until the
     * next morning digest clears it — re-posting on the next to-do change would make the swipe
     * meaningless.
     */
    fun pinnedDismissed(context: Context): Boolean =
        prefs(context).getBoolean(KEY_PINNED_DISMISSED, false)

    fun setPinnedDismissed(context: Context, dismissed: Boolean) {
        prefs(context).edit().putBoolean(KEY_PINNED_DISMISSED, dismissed).commit()
    }

    // ── Check-ins ─────────────────────────────────────────────────────────────

    /**
     * The Claude line a check-in was posted with, so the notification can be redrawn after a
     * tick without paying for the sentence again. Generated from vault-safe inputs.
     */
    fun checkInLine(context: Context, slot: String): String =
        prefs(context).getString(KEY_CHECKIN_PREFIX + slot, "") ?: ""

    fun setCheckInLine(context: Context, slot: String, line: String) {
        prefs(context).edit().putString(KEY_CHECKIN_PREFIX + slot, line).apply()
    }
}
