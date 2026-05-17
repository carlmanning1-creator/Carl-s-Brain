package com.carlmanning.carlsbrain.ui.components

import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrainTopBar(
    title: String = "Carl's Brain",
    onVaultToggle: () -> Unit,
    onNavigateToSettings: () -> Unit,
    onNavigateToSearch: (() -> Unit)? = null,
    isSyncing: Boolean = false,
    onSyncNow: () -> Unit = {},
    extraActions: @Composable RowScope.() -> Unit = {}
) {
    val haptic = LocalHapticFeedback.current

    TopAppBar(
        title = {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge
            )
        },
        navigationIcon = {
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
                    )
            ) {
                Icon(
                    imageVector = Icons.Filled.Psychology,
                    contentDescription = "Brain icon — long press to toggle vault",
                    modifier = Modifier.size(28.dp)
                )
            }
        },
        actions = {
            extraActions()
            if (onNavigateToSearch != null) {
                IconButton(onClick = onNavigateToSearch) {
                    Icon(Icons.Filled.Search, contentDescription = "Search")
                }
            }
            if (isSyncing) {
                Box(modifier = Modifier.size(48.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                }
            } else {
                IconButton(onClick = onSyncNow) {
                    Icon(Icons.Filled.Sync, contentDescription = "Sync now")
                }
            }
            IconButton(onClick = onNavigateToSettings) {
                Icon(imageVector = Icons.Filled.Settings, contentDescription = "Settings")
            }
        }
    )
}
