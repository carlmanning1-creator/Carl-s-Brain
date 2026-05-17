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
}
