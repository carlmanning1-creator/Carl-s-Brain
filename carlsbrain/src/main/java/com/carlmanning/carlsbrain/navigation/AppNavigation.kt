package com.carlmanning.carlsbrain.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.carlmanning.carlsbrain.AppViewModel
import com.carlmanning.carlsbrain.ui.screens.calendar.CalendarScreen
import com.carlmanning.carlsbrain.ui.screens.capture.CaptureScreen
import com.carlmanning.carlsbrain.ui.screens.chat.ChatScreen
import com.carlmanning.carlsbrain.ui.screens.dashboard.DashboardScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NoteEditorScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NotesScreen
import com.carlmanning.carlsbrain.ui.screens.settings.SettingsScreen
import com.carlmanning.carlsbrain.ui.screens.todos.HistoryScreen
import com.carlmanning.carlsbrain.ui.screens.todos.TodosScreen

private data class BottomNavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val bottomNavItems = listOf(
    BottomNavItem(Screen.Dashboard, "Dashboard", Icons.Filled.Home),
    BottomNavItem(Screen.Notes, "Notes", Icons.AutoMirrored.Filled.Notes),
    BottomNavItem(Screen.Todos, "To Do", Icons.Filled.CheckBox),
    BottomNavItem(Screen.Chat, "Chat", Icons.AutoMirrored.Filled.Chat),
    BottomNavItem(Screen.Calendar, "Calendar", Icons.Filled.CalendarMonth),
)

@Composable
fun AppNavigation(appViewModel: AppViewModel) {
    val navController = rememberNavController()
    val isVaultVisible by appViewModel.isVaultVisible.collectAsStateWithLifecycle()

    Scaffold(
        bottomBar = {
            val navBackStackEntry by navController.currentBackStackEntryAsState()
            val currentDestination = navBackStackEntry?.destination
            val showBottomBar = bottomNavItems.any { it.screen.route == currentDestination?.route }

            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any { it.route == item.screen.route } == true,
                            onClick = {
                                navController.navigate(item.screen.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route) }
                )
            }
            composable(Screen.Notes.route) {
                NotesScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route) },
                    onOpenNote = { noteId -> navController.navigate(Screen.NoteEditor.route(noteId)) }
                )
            }
            composable(Screen.Todos.route) {
                TodosScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route) },
                    onNavigateToHistory = { navController.navigate(Screen.History.route) }
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Capture.route) {
                CaptureScreen(
                    onDismiss = { navController.popBackStack() }
                )
            }
            composable(
                route = Screen.NoteEditor.route,
                arguments = listOf(navArgument("noteId") { type = NavType.LongType })
            ) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getLong("noteId") ?: return@composable
                NoteEditorScreen(
                    noteId = noteId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
        }
    }
}
