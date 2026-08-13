package com.carlmanning.carlsbrain.ui.components

import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainTopBar(
    title: String = "Carl's Brain",
    titleContent: (@Composable () -> Unit)? = null,
    isVaultVisible: Boolean = false,
    onVaultToggle: () -> Unit = {},
    onNavigateBack: (() -> Unit)? = null,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: (() -> Unit)? = null,
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {},
    overflowMenuContent: (@Composable (dismiss: () -> Unit) -> Unit)? = null
) {
    val haptic = LocalHapticFeedback.current
    val context = LocalContext.current
    val isOnline by produceState(initialValue = true) {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        fun hasInternet(): Boolean = cm.activeNetwork?.let {
            cm.getNetworkCapabilities(it)?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } == true
        value = hasInternet()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { value = true }
            // Another network may still be up (e.g. cellular after leaving Wi-Fi),
            // so re-query the active network rather than assuming we went offline.
            override fun onLost(network: Network) { value = hasInternet() }
            override fun onCapabilitiesChanged(
                network: Network,
                capabilities: NetworkCapabilities
            ) {
                value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            }
        }
        cm.registerDefaultNetworkCallback(callback)
        awaitDispose { cm.unregisterNetworkCallback(callback) }
    }

    TopAppBar(
        title = {
            if (titleContent != null) {
                titleContent()
            } else {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge
                )
            }
        },
        navigationIcon = {
            if (onNavigateBack != null) {
                IconButton(onClick = onNavigateBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .combinedClickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = {},
                            onLongClick = {
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                onVaultToggle()
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Filled.Psychology,
                        contentDescription = "Brain icon — long press to toggle vault",
                        modifier = Modifier.size(28.dp),
                        tint = if (isVaultVisible) Color(0xFFFFB300)
                               else MaterialTheme.colorScheme.onSurface
                    )
                    if (isVaultVisible) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .align(Alignment.BottomEnd)
                                .background(Color(0xFFFFB300), CircleShape)
                        )
                    }
                }
            }
        },
        actions = {
            extraActions()
            if (overflowMenuContent != null) {
                var menuExpanded by remember { mutableStateOf(false) }
                IconButton(onClick = { menuExpanded = true }) {
                    Icon(Icons.Filled.MoreVert, contentDescription = "More options")
                }
                DropdownMenu(
                    expanded = menuExpanded,
                    onDismissRequest = { menuExpanded = false }
                ) {
                    overflowMenuContent { menuExpanded = false }
                }
            }
            if (onNavigateToSearch != null) {
                IconButton(onClick = onNavigateToSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
            when {
                !isOnline -> {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        Icon(
                            Icons.Filled.WifiOff,
                            contentDescription = "Offline",
                            modifier = Modifier.size(20.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                isSyncing -> {
                    Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    }
                }
                else -> {
                    IconButton(onClick = onSyncNow) {
                        Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                    }
                }
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    )
}
