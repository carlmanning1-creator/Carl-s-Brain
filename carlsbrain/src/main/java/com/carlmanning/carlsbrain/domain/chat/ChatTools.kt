package com.carlmanning.carlsbrain.domain.chat

import com.carlmanning.carlsbrain.data.local.AppDatabase
import com.carlmanning.carlsbrain.data.remote.CalendarRepository
import com.carlmanning.carlsbrain.data.remote.ToolUse
import com.carlmanning.carlsbrain.domain.model.Priority
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * The tools unleashed Chat can call against Carl's own material.
 *
 * ## Read-only, by design
 *
 * Every tool here reads. Chat already writes through the `[TODO:]` / `[NOTE:]` / `[DONE:]` /
 * `[CALENDAR:]` markers it has always used, and those go through the same use cases the rest
 * of the app does — `CompleteTodoUseCase` in particular, without which ticking a recurring
 * to-do silently ends the recurrence. Adding a second, parallel write path would have meant
 * two places that can create a to-do and only one of them correct.
 *
 * ## The vault rule
 *
 * Chat is a vault-closed surface. Every query below is the non-vault variant, unconditionally
 * — not "unless the vault happens to be open". That matches how Chat already files captures
 * (non-vault buckets only, because its completion path is vault-filtered) and it means there
 * is no state anywhere that can make one of these tools return a vault item. A tool that is
 * vault-safe only while a flag says so is one refactor away from not being.
 *
 * The filtering is in SQL, in the DAO, not here — a screen or a helper that forgets to filter
 * is exactly how this project has leaked before.
 */
object ChatTools {

    /** How many tool round trips one question may take before the loop gives up. */
    const val MAX_ITERATIONS = 6

    /**
     * The tool schemas, as sent to the API.
     *
     * Descriptions are written for the model, not for us: each says when *not* to reach for the
     * tool as well as when to, because the failure mode that costs Carl money is Claude
     * searching his notes for something it was told two lines earlier.
     */
    val DEFINITIONS: JsonArray = buildJsonArray {
        add(
            tool(
                name = "search_notes",
                description = """
                    Search Carl's notes by keyword, matching title, body and tags. Returns the 20
                    most recently updated matches, each truncated. Use when he refers
                    to something he wrote down and you do not already have it in context. Do not
                    use it to answer a question you can already answer.
                """.trimIndent(),
                properties = buildJsonObject {
                    put(
                        "query",
                        stringProp("Keyword or phrase. A single distinctive word works best.")
                    )
                }
            )
        )
        add(
            tool(
                name = "search_todos",
                description = """
                    Search Carl's to-dos by title. Returns each match with its priority, due date
                    and whether it is done, unfinished ones first. Pass an empty query to list
                    his current to-dos. Use before claiming something is or is not on his list.
                """.trimIndent(),
                properties = buildJsonObject {
                    put("query", stringProp("Keyword, or empty for everything outstanding."))
                }
            )
        )
        add(
            tool(
                name = "search_journal",
                description = """
                    Search Carl's journal entries by keyword. Private entries, drafts and entries
                    in vault buckets are never returned — the query cannot reach them. Use for
                    spotting patterns over time. Do not quote entries back at him verbatim.
                """.trimIndent(),
                properties = buildJsonObject {
                    put("query", stringProp("Keyword or phrase."))
                }
            )
        )
        add(
            tool(
                name = "get_calendar",
                description = """
                    Carl's upcoming calendar events. Use before suggesting a time for anything,
                    and before saying what his day looks like.
                """.trimIndent(),
                properties = buildJsonObject {
                    put(
                        "days_ahead",
                        buildJsonObject {
                            put("type", JsonPrimitive("integer"))
                            put(
                                "description",
                                JsonPrimitive("How many days forward to look. Default 7, max 60.")
                            )
                        }
                    )
                }
            )
        )
    }

    /**
     * Everything unleashed Chat is offered: Carl's own material plus the open web.
     *
     * The web tools run on Anthropic's servers and never come back here as a tool_use block —
     * they are declared in the same array only because that is where the API expects them. The
     * loop in ChatViewModel therefore only ever has to execute the four above.
     */
    val UNLEASHED_TOOLS: JsonArray = buildJsonArray {
        DEFINITIONS.forEach { add(it) }
        add(
            buildJsonObject {
                put("type", JsonPrimitive("web_search_20260209"))
                put("name", JsonPrimitive("web_search"))
            }
        )
        add(
            buildJsonObject {
                put("type", JsonPrimitive("web_fetch_20260209"))
                put("name", JsonPrimitive("web_fetch"))
            }
        )
    }

    /**
     * Runs one tool call and returns what Claude should see.
     *
     * Never throws: a failure comes back as text saying so, because the alternative is the
     * whole conversation failing over a database hiccup in a tool Claude reached for
     * speculatively. Claude can then say it could not look, which is honest and recoverable.
     *
     * The results are deliberately terse. Whole notes would fill the context window in three
     * calls, and the point of a search result is to let Claude decide what matters, not to
     * reproduce the note.
     */
    suspend fun execute(
        use: ToolUse,
        db: AppDatabase,
        calendar: CalendarRepository
    ): String = runCatching {
        when (use.name) {
            "search_notes" -> {
                val query = use.input["query"]?.jsonPrimitive?.content.orEmpty().trim()
                if (query.isBlank()) return@runCatching "No query given."
                val notes = db.noteDao().searchNotes(query)
                if (notes.isEmpty()) return@runCatching "No notes match \"$query\"."
                notes.take(20).joinToString("\n\n") { note ->
                    val title = note.title.ifBlank { "(untitled)" }
                    "• $title — ${note.content.take(300).replace("\n", " ")}"
                }
            }

            "search_todos" -> {
                val query = use.input["query"]?.jsonPrimitive?.content.orEmpty().trim()
                val todos = db.todoDao().searchTodos(query)
                if (todos.isEmpty()) {
                    return@runCatching if (query.isBlank()) "No outstanding to-dos."
                                       else "No to-dos match \"$query\"."
                }
                todos.take(30).joinToString("\n") { todo ->
                    val due = todo.dueDate?.let { " · due ${formatDay(it)}" }.orEmpty()
                    val state = if (todo.isDone) " · DONE" else ""
                    "• ${todo.title} · ${Priority.fromRank(todo.priority).displayName}$due$state"
                }
            }

            "search_journal" -> {
                val query = use.input["query"]?.jsonPrimitive?.content.orEmpty().trim()
                if (query.isBlank()) return@runCatching "No query given."
                // searchVisible, not searchAll: private entries, drafts and vault-bucketed
                // entries are excluded in SQL, so there is no call site that can leak one.
                val entries = db.journalDao().searchVisible(query)
                if (entries.isEmpty()) return@runCatching "No journal entries match \"$query\"."
                entries.take(15).joinToString("\n\n") { entry ->
                    "• ${formatDay(entry.createdAt)} — ${entry.content.take(300).replace("\n", " ")}"
                }
            }

            "get_calendar" -> {
                val days = use.input["days_ahead"]?.jsonPrimitive?.content?.toIntOrNull() ?: 7
                calendar.getUpcomingEvents(days.coerceIn(1, 60)).fold(
                    onSuccess = { events ->
                        if (events.isEmpty()) "Nothing in the calendar for the next $days days."
                        else events.take(40).joinToString("\n") { event ->
                            "• ${formatDay(event.startMs)} — ${event.title}"
                        }
                    },
                    // A calendar that will not load is worth saying out loud rather than
                    // reporting as an empty diary, which reads as a free day.
                    onFailure = { "Could not read the calendar: ${it.message}" }
                )
            }

            else -> "Unknown tool: ${use.name}"
        }
    }.getOrElse { "That lookup failed: ${it.message}" }

    private val dayFormat: DateTimeFormatter =
        DateTimeFormatter.ofPattern("EEE d MMM, HH:mm").withZone(ZoneId.systemDefault())

    private fun formatDay(epochMs: Long): String =
        runCatching { dayFormat.format(Instant.ofEpochMilli(epochMs)) }.getOrDefault("unknown date")

    private fun stringProp(description: String) = buildJsonObject {
        put("type", JsonPrimitive("string"))
        put("description", JsonPrimitive(description))
    }

    private fun tool(
        name: String,
        description: String,
        properties: JsonObject
    ) = buildJsonObject {
        put("name", JsonPrimitive(name))
        put("description", JsonPrimitive(description))
        put(
            "input_schema",
            buildJsonObject {
                put("type", JsonPrimitive("object"))
                put("properties", properties)
            }
        )
    }
}
