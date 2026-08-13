package com.carlmanning.carlsbrain.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp

/** Surface tones for nested containers, from flat background up to nested cards. */
object BrainElevation {
    /** Flattest surface — screen background. */
    val level0: Color
        @Composable get() = MaterialTheme.colorScheme.surface

    /** First nesting level — cards on the background. */
    val level1: Color
        @Composable get() = MaterialTheme.colorScheme.surfaceVariant

    /** Second nesting level — content inside a card. */
    val level2: Color
        @Composable get() = nestedSurface(1)

    /** Third nesting level — content inside nested content. */
    val level3: Color
        @Composable get() = nestedSurface(2)
}

/** Returns a progressively lighter surface tone for the given nesting depth (clamped 0..3). */
@Composable
fun nestedSurface(depth: Int): Color {
    val clamped = depth.coerceIn(0, 3)
    return lerp(
        MaterialTheme.colorScheme.surfaceVariant,
        MaterialTheme.colorScheme.onSurface,
        0.04f * clamped
    )
}
