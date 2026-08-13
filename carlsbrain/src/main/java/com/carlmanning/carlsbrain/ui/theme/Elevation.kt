package com.carlmanning.carlsbrain.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/**
 * Surface tone for a nested container at the given nesting depth (clamped 0..3).
 *
 * Each step blends [MaterialTheme.colorScheme.surfaceVariant] a little further toward
 * `onSurface`, so deeper containers sit at *higher contrast* against the surface — that
 * means lighter in dark theme and darker in light theme. It is deliberately not Material's
 * tonal-elevation ramp (which always lightens); the goal here is depth that stays readable
 * outdoors in bright sun in either theme.
 */
@Composable
fun nestedSurface(depth: Int): Color {
    val clamped = depth.coerceIn(0, 3)
    return lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.04f * clamped
    )
}
