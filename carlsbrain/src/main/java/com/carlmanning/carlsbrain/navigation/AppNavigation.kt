package com.carlmanning.carlsbrain.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.carlmanning.carlsbrain.ui.screens.capture.CaptureType
import com.carlmanning.carlsbrain.ui.screens.chat.ChatScreen
import com.carlmanning.carlsbrain.ui.screens.dashboard.DashboardScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NoteEditorScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NotesScreen
import com.carlmanning.carlsbrain.ui.screens.settings.MemoryEditorScreen
import com.carlmanning.carlsbrain.ui.screens.settings.SettingsScreen
import com.carlmanning.carlsbrain.ui.screens.todos.HistoryScreen
import com.carlmanning.carlsbrain.ui.screens.todos.TodoEditorScreen
import com.carlmanning.carlsbrain.ui.screens.todos.TodosScreen
import com.carlmanning.carlsbrain.ui.screens.search.SearchScreen

private data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val navItems = listOf(
    NavItem(Screen.Dashboard, "Dashboard", Icons.Filled.Home),
    NavItem(Screen.Notes, "Notes", Icons.AutoMirrored.Filled.Notes),
    NavItem(Screen.Todos, "To Do", Icons.Filled.CheckBox),
    NavItem(Screen.Chat, "Chat", Icons.AutoMirrored.Filled.Chat),
    NavItem(Screen.Calendar, "Calendar", Icons.Filled.CalendarMonth),
)

@Composable
fun AppNavigation(appViewModel: AppViewModel) {
    val navController = rememberNavController()
    val isVaultVisible by appViewModel.isVaultVisible.collectAsStateWithLifecycle()
    val isSyncing by appViewModel.isSyncing.collectAsStateWithLifecycle()
    val pendingCapture by appViewModel.pendingCapture.collectAsStateWithLifecycle()

    LaunchedEffect(pendingCapture) {
        pendingCapture?.let { req ->
            navController.navigate(Screen.Capture.route(req.type, req.startVoice))
            appViewModel.consumePendingCapture()
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationSuiteScaffold(
        navigationSuiteItems = {
            navItems.forEach { item ->
                val selected = currentDestination?.hierarchy
                    ?.any { it.route == item.screen.route } == true
                item(
                    icon = { Icon(item.icon, contentDescription = item.label) },
                    label = { Text(item.label) },
                    selected = selected,
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
    ) {
        NavHost(
            navController = navController,
            startDestination = Screen.Dashboard.route
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route()) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.Notes.route) {
                NotesScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route()) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onOpenNote = { noteId -> navController.navigate(Screen.NoteEditor.route(noteId)) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.Todos.route) {
                TodosScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route()) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onNavigateToHistory = { navController.navigate(Screen.History.route) },
                    onOpenTodo = { todoId -> navController.navigate(Screen.TodoEditor.route(todoId)) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(Screen.Chat.route) {
                ChatScreen(
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMemory = { navController.navigate(Screen.MemoryEditor.route) }
                )
            }
            composable(Screen.MemoryEditor.route) {
                MemoryEditorScreen(onNavigateBack = { navController.popBackStack() })
            }
            composable(
                route = Screen.Capture.route,
                arguments = listOf(
                    navArgument("type") { defaultValue = "TODO" },
                    navArgument("voice") { type = NavType.BoolType; defaultValue = false }
                )
            ) { backStackEntry ->
                val typeStr = backStackEntry.arguments?.getString("type") ?: "TODO"
                val startVoice = backStackEntry.arguments?.getBoolean("voice") ?: false
                val initialType = runCatching { CaptureType.valueOf(typeStr) }
                    .getOrDefault(CaptureType.TODO)
                CaptureScreen(
                    onDismiss = { navController.popBackStack() },
                    initialType = initialType,
                    startVoice = startVoice
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
            composable(
                route = Screen.TodoEditor.route,
                arguments = listOf(navArgument("todoId") { type = NavType.LongType })
            ) { backStackEntry ->
                val todoId = backStackEntry.arguments?.getLong("todoId") ?: return@composable
                TodoEditorScreen(
                    todoId = todoId,
                    onNavigateBack = { navController.popBackStack() }
                )
            }
            composable(Screen.Search.route) {
                SearchScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenNote = { noteId -> navController.navigate(Screen.NoteEditor.route(noteId)) },
                    onOpenTodo = { todoId -> navController.navigate(Screen.TodoEditor.route(todoId)) },
                    onOpenCalendar = {
                        navController.navigate(Screen.Calendar.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenChat = {
                        navController.navigate(Screen.Chat.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
        }
    }
}
