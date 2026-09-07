package com.carlmanning.carlsbrain.domain.usecase

import com.carlmanning.carlsbrain.data.local.dao.TodoDao
import com.carlmanning.carlsbrain.data.local.entity.TodoEntity

/**
 * Decides which to-do — if any — a `[DONE: …]` marker means.
 *
 * `[DONE:]` is a fuzzy substring search against real titles, so getting this wrong completes
 * the wrong to-do and says nothing. The voice service was given three guards for that; Chat had
 * none of them, and Chat runs `[TODO:]` before `[DONE:]` on the same reply — so a to-do Chat had
 * just created was immediately a candidate for completion by the very reply that created it,
 * after which the nightly cleanup archived it and it vanished as though it had never existed.
 *
 * Shared rather than copied, because the two surfaces answering differently is worse than either
 * answer: the same sentence typed and spoken should complete the same to-do.
 *
 * Vault-closed by construction — [TodoDao.searchTodos] excludes vault buckets — so no title in
 * any outcome here can be vault content. Keep that true if the query is ever changed.
 */
object ResolveDoneMarker {

    sealed class Outcome {
        /** Exactly one sensible match. */
        data class Matched(val todo: TodoEntity) : Outcome()
        /** Nothing matched. Previously a silent no-op on both surfaces. */
        object NotFound : Outcome()
        /** Several matched. Nothing is completed — the caller asks which. */
        data class Ambiguous(val candidates: List<TodoEntity>) : Outcome()
    }

    /**
     * @param excludeIds to-dos created by the same reply. Without this the reply that creates a
     *   to-do can complete it in the same breath.
     */
    suspend fun resolve(
        todoDao: TodoDao,
        titleQuery: String,
        excludeIds: Set<Long> = emptySet()
    ): Outcome {
        val candidates = todoDao.searchTodos(titleQuery)
            .filter { !it.isDone && it.id !in excludeIds }
        if (candidates.isEmpty()) return Outcome.NotFound

        // An exact title match wins outright. Claude usually echoes the full title, and without
        // this a to-do whose title is a substring of another's ("Roster" vs "Roster handover")
        // would read as ambiguous every single time.
        val exact = candidates.filter { it.title.equals(titleQuery, ignoreCase = true) }
        val chosen = exact.singleOrNull() ?: candidates.singleOrNull()
        return if (chosen != null) Outcome.Matched(chosen) else Outcome.Ambiguous(candidates)
    }

    /** The sentence to show or speak when several to-dos matched. */
    fun ambiguousMessage(titleQuery: String, candidates: List<TodoEntity>): String {
        val listed = candidates.take(3).joinToString("; ") { it.title }
        val more = if (candidates.size > 3) ", and others" else ""
        return "There's more than one to-do matching \"$titleQuery\": $listed$more. " +
            "Which one do you mean? I haven't marked anything done."
    }

    /** The sentence to show or speak when nothing matched. */
    fun notFoundMessage(titleQuery: String): String =
        "I couldn't find a to-do matching \"$titleQuery\", so I haven't marked anything done."
}
