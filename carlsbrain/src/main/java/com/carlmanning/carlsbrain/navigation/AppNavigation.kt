package com.carlmanning.carlsbrain.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.currentWindowAdaptiveInfo
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffoldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
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
import com.carlmanning.carlsbrain.data.preferences.UserPreferences
import com.carlmanning.carlsbrain.data.local.worker.WeeklyReviewWorker
import com.carlmanning.carlsbrain.ui.screens.onboarding.OnboardingScreen
import com.carlmanning.carlsbrain.ui.screens.calendar.CalendarScreen
import com.carlmanning.carlsbrain.ui.screens.capture.CaptureScreen
import com.carlmanning.carlsbrain.ui.screens.capture.CaptureType
import com.carlmanning.carlsbrain.ui.screens.chat.ChatScreen
import com.carlmanning.carlsbrain.ui.screens.chat.ChatThreadListScreen
import com.carlmanning.carlsbrain.ui.screens.dashboard.DashboardScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NoteEditorScreen
import com.carlmanning.carlsbrain.ui.screens.notes.NotesScreen
import com.carlmanning.carlsbrain.ui.screens.settings.MemoryEditorScreen
import com.carlmanning.carlsbrain.ui.screens.settings.RecentlyDeletedScreen
import com.carlmanning.carlsbrain.ui.screens.settings.SettingsScreen
import com.carlmanning.carlsbrain.ui.screens.todos.HistoryScreen
import com.carlmanning.carlsbrain.ui.screens.todos.TodoEditorScreen
import com.carlmanning.carlsbrain.ui.screens.todos.TodosScreen
import com.carlmanning.carlsbrain.ui.screens.meetings.MeetingDetailScreen
import com.carlmanning.carlsbrain.ui.screens.meetings.MeetingsScreen
import com.carlmanning.carlsbrain.ui.screens.search.SearchScreen
import com.carlmanning.carlsbrain.ui.screens.health.HealthScreen
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private data class NavItem(
    val screen: Screen,
    val label: String,
    val icon: ImageVector
)

private val navItems = listOf(
    NavItem(Screen.Dashboard, "Home", Icons.Filled.Home),
    NavItem(Screen.Notes, "Notes", Icons.AutoMirrored.Filled.Notes),
    NavItem(Screen.Todos, "To Do", Icons.Filled.CheckBox),
    NavItem(Screen.ChatThreadList, "Chat", Icons.AutoMirrored.Filled.Chat),
    NavItem(Screen.Meetings, "Meetings", Icons.Filled.Mic),
)
// Capped at five per Material guidance — one-handed, gloved use makes mis-taps costly.
// Calendar and Health are reached from the Dashboard top-bar overflow menu instead;
// their NavHost destinations below are unchanged.

@Composable
fun AppNavigation(appViewModel: AppViewModel) {
    val context = LocalContext.current
    val userPrefs = remember(context) { UserPreferences(context) }
    val coroutineScope = rememberCoroutineScope()

    // Tri-state: null while the DataStore read is still in flight. We render nothing
    // until it resolves so returning users never see onboarding flash on cold start.
    val onboardingCompleted: Boolean? by remember(userPrefs) {
        userPrefs.onboardingCompleted.map<Boolean, Boolean?> { it }
    }.collectAsStateWithLifecycle(initial = null)

    if (onboardingCompleted == null) return

    // First run shows onboarding ahead of the nav graph entirely, so the graph's start
    // destination can stay pinned to Dashboard (see `startDestination` below) and
    // `findStartDestination()` always resolves to a route that is actually on the back stack.
    // Rendering it here rather than as a NavHost destination also means Dashboard never gets
    // a frame on screen before onboarding. The biometric gate wraps this whole composable,
    // so onboarding stays behind it.
    var showOnboarding by remember { mutableStateOf(onboardingCompleted == false) }
    if (showOnboarding) {
        OnboardingScreen(
            onFinish = {
                coroutineScope.launch { userPrefs.setOnboardingCompleted(true) }
                showOnboarding = false
            }
        )
        return
    }

    val navController = rememberNavController()
    val isVaultVisible by appViewModel.isVaultVisible.collectAsStateWithLifecycle()
    val isSyncing by appViewModel.isSyncing.collectAsStateWithLifecycle()
    val pendingCapture by appViewModel.pendingCapture.collectAsStateWithLifecycle()
    val pendingOpenNoteId by appViewModel.pendingOpenNoteId.collectAsStateWithLifecycle()
    val pendingOpenTodoId by appViewModel.pendingOpenTodoId.collectAsStateWithLifecycle()
    val pendingStartMeeting by appViewModel.pendingStartMeeting.collectAsStateWithLifecycle()
    val pendingOpenMeetingId by appViewModel.pendingOpenMeetingId.collectAsStateWithLifecycle()
    val pendingChatPrompt by appViewModel.pendingChatPrompt.collectAsStateWithLifecycle()
    val urgentTodoCount by appViewModel.urgentTodoCount.collectAsStateWithLifecycle()

    LaunchedEffect(pendingCapture) {
        pendingCapture?.let { req ->
            navController.navigate(Screen.Capture.route(req.type, req.startVoice))
            appViewModel.consumePendingCapture()
        }
    }

    LaunchedEffect(pendingOpenNoteId) {
        pendingOpenNoteId?.let { noteId ->
            navController.navigate(Screen.NoteEditor.route(noteId))
            appViewModel.consumePendingOpenNoteId()
        }
    }

    LaunchedEffect(pendingOpenTodoId) {
        pendingOpenTodoId?.let { todoId ->
            navController.navigate(Screen.TodoEditor.route(todoId))
            appViewModel.consumePendingOpenTodoId()
        }
    }

    LaunchedEffect(pendingStartMeeting) {
        if (pendingStartMeeting) {
            navController.navigate(Screen.Meetings.route) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
                restoreState = true
            }
            appViewModel.consumePendingStartMeeting()
        }
    }

    LaunchedEffect(pendingOpenMeetingId) {
        pendingOpenMeetingId?.let { meetingId ->
            navController.navigate(Screen.MeetingDetail.route(meetingId)) {
                popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                launchSingleTop = true
            }
            appViewModel.consumePendingOpenMeetingId()
        }
    }

    LaunchedEffect(pendingChatPrompt) {
        pendingChatPrompt?.let {
            // Must land on ChatScreen, which is keyed on a threadId and is what actually
            // consumes autoSendPrompt. Routing to the thread list left the prompt unsent.
            appViewModel.startChatThreadForPrompt { threadId ->
                navController.navigate(Screen.Chat.route(threadId)) {
                    popUpTo(navController.graph.findStartDestination().id) { saveState = false }
                    launchSingleTop = true
                }
            }
        }
    }

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    NavigationSuiteScaffold(
        layoutType = NavigationSuiteScaffoldDefaults
            .calculateFromAdaptiveInfo(currentWindowAdaptiveInfo()),
        navigationSuiteItems = {
            navItems.forEach { item ->
                val selected = currentDestination?.hierarchy
                    ?.any { it.route == item.screen.route } == true
                val showBadge = item.screen == Screen.Todos && urgentTodoCount > 0
                item(
                    icon = {
                        if (showBadge) {
                            BadgedBox(badge = {
                                Badge { Text(if (urgentTodoCount > 99) "99+" else urgentTodoCount.toString()) }
                            }) {
                                Icon(item.icon, contentDescription = item.label)
                            }
                        } else {
                            Icon(item.icon, contentDescription = item.label)
                        }
                    },
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
            // Always Dashboard, first run included, so every bottom-nav tap's
            // popUpTo(findStartDestination()) actually matches and the back stack stops growing.
            startDestination = Screen.Dashboard.route
        ) {
            composable(Screen.Dashboard.route) {
                DashboardScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route()) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onNavigateToChat = {
                        navController.navigate(Screen.ChatThreadList.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onOpenTodo = { todoId -> navController.navigate(Screen.TodoEditor.route(todoId)) },
                    onOpenNote = { noteId -> navController.navigate(Screen.NoteEditor.route(noteId)) },
                    onOpenMeeting = { meetingId -> navController.navigate(Screen.MeetingDetail.route(meetingId)) },
                    onOpenCalendar = {
                        navController.navigate(Screen.Calendar.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    // Goes straight through the pending-prompt path rather than the
                    // screen's fallback, which restarts the Activity to deliver an intent.
                    onWeeklyReview = {
                        appViewModel.requestChatPrompt(WeeklyReviewWorker.WEEKLY_REVIEW_PROMPT)
                    },
                    onNavigateToHealth = {
                        navController.navigate(Screen.Health.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                )
            }
            composable(Screen.Notes.route) {
                NotesScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToCapture = { navController.navigate(Screen.Capture.route()) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onNavigateToChat = {
                        navController.navigate(Screen.ChatThreadList.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
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
                    onNavigateToChat = {
                        navController.navigate(Screen.ChatThreadList.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onNavigateToHistory = { navController.navigate(Screen.History.route) },
                    onOpenTodo = { todoId -> navController.navigate(Screen.TodoEditor.route(todoId)) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.History.route) {
                HistoryScreen(
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) }
                )
            }
            composable(Screen.ChatThreadList.route) {
                ChatThreadListScreen(
                    onOpenThread = { threadId -> navController.navigate(Screen.Chat.route(threadId)) },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(
                route = Screen.Chat.route,
                arguments = listOf(androidx.navigation.navArgument("threadId") { type = NavType.LongType })
            ) { backStackEntry ->
                val threadId = backStackEntry.arguments?.getLong("threadId") ?: -1L
                ChatScreen(
                    threadId = threadId,
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    autoSendPrompt = pendingChatPrompt,
                    onAutoSendConsumed = { appViewModel.consumePendingChatPrompt() }
                )
            }
            composable(Screen.Calendar.route) {
                CalendarScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onNavigateToChat = {
                        navController.navigate(Screen.ChatThreadList.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(Screen.Meetings.route) {
                MeetingsScreen(
                    onOpenMeeting = { meetingId -> navController.navigate(Screen.MeetingDetail.route(meetingId)) },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    autoStartRecording = pendingStartMeeting,
                    onAutoStartConsumed = { appViewModel.consumePendingStartMeeting() }
                )
            }
            composable(Screen.Health.route) {
                HealthScreen(
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow
                )
            }
            composable(
                route = Screen.MeetingDetail.route,
                arguments = listOf(navArgument("meetingId") { type = NavType.LongType })
            ) { backStackEntry ->
                val meetingId = backStackEntry.arguments?.getLong("meetingId") ?: return@composable
                MeetingDetailScreen(
                    meetingId = meetingId,
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onOpenTodo = { todoId -> navController.navigate(Screen.TodoEditor.route(todoId)) }
                )
            }
            composable(Screen.Settings.route) {
                SettingsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToMemory = { navController.navigate(Screen.MemoryEditor.route) },
                    onNavigateToRecentlyDeleted = { navController.navigate(Screen.RecentlyDeleted.route) },
                    isVaultVisible = isVaultVisible
                )
            }
            composable(Screen.MemoryEditor.route) {
                MemoryEditorScreen(
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) }
                )
            }
            composable(Screen.RecentlyDeleted.route) {
                RecentlyDeletedScreen(
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) }
                )
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
                    startVoice = startVoice,
                    isVaultVisible = isVaultVisible
                )
            }
            composable(
                route = Screen.NoteEditor.route,
                arguments = listOf(navArgument("noteId") { type = NavType.LongType })
            ) { backStackEntry ->
                val noteId = backStackEntry.arguments?.getLong("noteId") ?: return@composable
                NoteEditorScreen(
                    noteId = noteId,
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onOpenMeeting = { meetingId -> navController.navigate(Screen.MeetingDetail.route(meetingId)) }
                )
            }
            composable(
                route = Screen.TodoEditor.route,
                arguments = listOf(navArgument("todoId") { type = NavType.LongType })
            ) { backStackEntry ->
                val todoId = backStackEntry.arguments?.getLong("todoId") ?: return@composable
                TodoEditorScreen(
                    todoId = todoId,
                    onNavigateBack = { navController.popBackStack() },
                    isVaultVisible = isVaultVisible,
                    onVaultToggle = { appViewModel.toggleVaultVisibility() },
                    isSyncing = isSyncing,
                    onSyncNow = appViewModel::syncNow,
                    onNavigateToSettings = { navController.navigate(Screen.Settings.route) },
                    onNavigateToSearch = { navController.navigate(Screen.Search.route) },
                    onOpenMeeting = { meetingId -> navController.navigate(Screen.MeetingDetail.route(meetingId)) }
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
                        navController.navigate(Screen.ChatThreadList.route) {
                            popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    onOpenMeeting = { meetingId -> navController.navigate(Screen.MeetingDetail.route(meetingId)) },
                    isVaultVisible = isVaultVisible
                )
            }
        }
    }
}
