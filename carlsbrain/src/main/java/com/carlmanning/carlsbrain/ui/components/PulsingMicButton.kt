package com.carlmanning.carlsbrain.ui.components

import android.provider.Settings
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

/** Minimum accessible touch target — gloved hands in the field need the full 48dp. */
private val MinTouchTarget = 48.dp

/**
 * Circular mic toggle that pulses while speech recognition is active.
 *
 * [size] controls the visual diameter only; the touch target is always at least 48dp.
 * When the system has animations turned off (reduced motion), the pulse is replaced by a
 * static emphasised ring so "listening" is still obvious without movement.
 */
@Composable
fun PulsingMicButton(
    isListening: Boolean,
    onClick: () -> Unit,
    size: Dp = 40.dp,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val animationsEnabled = remember(context) {
        Settings.Global.getFloat(
            context.contentResolver,
            Settings.Global.ANIMATOR_DURATION_SCALE,
            1f
        ) != 0f
    }
    val touchTarget = max(size, MinTouchTarget)

    Box(
        modifier = modifier
            .size(touchTarget)
            .clip(CircleShape)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center
    ) {
        if (isListening && animationsEnabled) {
            val transition = rememberInfiniteTransition(label = "micPulse")
            val progress by transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 1200),
                    repeatMode = RepeatMode.Restart
                ),
                label = "micPulseProgress"
            )
            val scale = 1f + (0.6f * progress)
            Box(
                modifier = Modifier
                    .size(size)
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        alpha = 0.5f * (1f - progress)
                    }
                    .background(MaterialTheme.colorScheme.primary, CircleShape)
            )
        }
        Box(
            modifier = Modifier
                .size(size)
                .clip(CircleShape)
                .background(
                    if (isListening) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.surfaceVariant
                )
                .then(
                    // Static stand-in for the pulse when animations are disabled.
                    if (isListening && !animationsEnabled) {
                        Modifier.border(2.dp, MaterialTheme.colorScheme.onPrimary, CircleShape)
                    } else {
                        Modifier
                    }
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = if (isListening) Icons.Filled.Stop else Icons.Filled.Mic,
                contentDescription = if (isListening) "Stop listening" else "Start voice input",
                modifier = Modifier.size(size * 0.5f),
                tint = if (isListening) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
