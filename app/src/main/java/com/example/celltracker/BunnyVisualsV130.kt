package com.example.celltracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * Lightweight, cached gradients inspired by the visual hierarchy of bunnylock.
 * No infinite gradient animation: cellular telemetry updates once per second,
 * and recreating animated shader brushes would generate unnecessary frame work.
 * Cross-fade transitions and animated selection tints provide the motion instead.
 */
@Composable
internal fun bunnyHeroBrush(): Brush {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        Brush.linearGradient(if (dark) listOf(
            Color(0xFF3B3154), Color(0xFF38293E), Color(0xFF284442)
        ) else listOf(
            Color(0xFFEAE3FA), Color(0xFFF8EAF3), Color(0xFFE3F4EE)
        ))
    }
}

@Composable
internal fun bunnyTileBrush(): Brush {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        Brush.linearGradient(if (dark) listOf(
            Color(0xFF292733), Color(0xFF2C2638)
        ) else listOf(
            Color(0xFFFFFDFE), Color(0xFFF6F1FA)
        ))
    }
}

@Composable
internal fun bunnyScreenBrush(): Brush {
    val dark = isSystemInDarkTheme()
    return remember(dark) {
        Brush.verticalGradient(if (dark) listOf(
            Color(0xFF24202F), Color(0xFF16151D), Color(0xFF16151D)
        ) else listOf(
            Color(0xFFF4EFF8), Color(0xFFFAF8FA), Color(0xFFFAF8FA)
        ))
    }
}
