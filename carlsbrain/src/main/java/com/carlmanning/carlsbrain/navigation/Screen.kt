package com.carlmanning.carlsbrain.navigation

sealed class Screen(val route: String) {
    object Dashboard : Screen("dashboard")
    object Notes : Screen("notes")
    object Todos : Screen("todos")
    object Chat : Screen("chat")
    object Calendar : Screen("calendar")
    object Settings : Screen("settings")
    object Capture : Screen("capture")
    object History : Screen("history")
    object NoteEditor : Screen("note_editor/{noteId}") {
        fun route(noteId: Long) = "note_editor/$noteId"
    }
    object TodoEditor : Screen("todo_editor/{todoId}") {
        fun route(todoId: Long) = "todo_editor/$todoId"
    }
}
