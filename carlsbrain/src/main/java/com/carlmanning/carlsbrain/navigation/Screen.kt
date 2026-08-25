package com.carlmanning.carlsbrain.navigation

sealed class Screen(val route: String) {
    object Onboarding : Screen("onboarding")
    object Dashboard : Screen("dashboard")
    object Notes : Screen("notes")
    object Journal : Screen("journal")

    /**
     * One templated journal entry. Both arguments are optional and mean different things:
     * a templateId alone starts (or resumes the draft of) a new entry from that template, and
     * an entryId opens an existing entry — draft or saved — for editing.
     */
    object JournalEntry : Screen("journal_entry?templateId={templateId}&entryId={entryId}") {
        fun route(templateId: Long? = null, entryId: Long? = null) =
            "journal_entry?templateId=${templateId ?: -1L}&entryId=${entryId ?: -1L}"
    }

    object JournalTemplates : Screen("journal_templates")
    object JournalTrends : Screen("journal_trends")
    object Todos : Screen("todos")
    object ChatThreadList : Screen("chat_threads")
    object Chat : Screen("chat/{threadId}") {
        fun route(threadId: Long) = "chat/$threadId"
    }
    object Calendar : Screen("calendar")
    object Settings : Screen("settings")
    object History : Screen("history")
    object Search : Screen("search")
    object Capture : Screen("capture?type={type}&voice={voice}") {
        fun route(type: String = "TODO", voice: Boolean = false) =
            "capture?type=$type&voice=$voice"
    }
    object NoteEditor : Screen("note_editor/{noteId}") {
        fun route(noteId: Long) = "note_editor/$noteId"
    }
    object TodoEditor : Screen("todo_editor/{todoId}") {
        fun route(todoId: Long) = "todo_editor/$todoId"
    }
    object MemoryEditor : Screen("memory_editor")
    object RecentlyDeleted : Screen("recently_deleted")
    object Meetings : Screen("meetings")
    object Health : Screen("health")
    object MeetingDetail : Screen("meeting_detail/{meetingId}") {
        fun route(meetingId: Long) = "meeting_detail/$meetingId"
    }
}
