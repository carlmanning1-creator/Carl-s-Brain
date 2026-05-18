package com.carlmanning.carlsbrain.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Notes : Screen("notes")
    object Todos : Screen("todos")
    object Chat : Screen("chat")
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
    object Meetings : Screen("meetings")
    object MeetingDetail : Screen("meeting_detail/{meetingId}") {
        fun route(meetingId: Long) = "meeting_detail/$meetingId"
    }
}
