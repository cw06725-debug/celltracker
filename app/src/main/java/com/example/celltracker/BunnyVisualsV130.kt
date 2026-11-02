package com.example.celltracker

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
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

/** Light entrance/re-entry animation, mirroring Home's one-shot reveal.
 * Not an AnimatedContent crossfade: the old tab is removed immediately,
 * preserving the Preview12 bottom navigation flicker fix.
 */
@Composable
internal fun SoftPageRevealV130(
    revealKey: Any,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit
) {
    val progress = remember(revealKey) { Animatable(0f) }
    LaunchedEffect(revealKey) {
        progress.animateTo(1f, animationSpec = tween(320, easing = FastOutSlowInEasing))
    }
    Box(modifier.graphicsLayer {
        alpha = progress.value
        translationY = (1f - progress.value) * 9.dp.toPx()
    }) { content() }
}
