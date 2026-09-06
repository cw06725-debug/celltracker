package com.example.celltracker

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged

/**
 * Small in-process navigation memory so returning from a child/detail page restores the
 * exact parent scroll position. This intentionally lives outside the destination's
 * composition because AnimatedContent disposes the outgoing screen.
 */
object UiScrollMemory {
    private val scrollOffsets = mutableMapOf<String, Int>()
    private val lazyOffsets = mutableMapOf<String, Pair<Int, Int>>()

    fun scrollOffset(key: String): Int = scrollOffsets[key] ?: 0
    fun setScrollOffset(key: String, value: Int) { scrollOffsets[key] = value }

    fun lazyOffset(key: String): Pair<Int, Int> = lazyOffsets[key] ?: (0 to 0)
    fun setLazyOffset(key: String, index: Int, offset: Int) { lazyOffsets[key] = index to offset }
}

@Composable
fun rememberRetainedScrollState(key: String): ScrollState {
    val state = remember(key) { ScrollState(UiScrollMemory.scrollOffset(key)) }
    LaunchedEffect(state, key) {
        snapshotFlow { state.value }
            .distinctUntilChanged()
            .collect { UiScrollMemory.setScrollOffset(key, it) }
    }
    return state
}

@Composable
fun rememberRetainedLazyListState(key: String): LazyListState {
    val saved = UiScrollMemory.lazyOffset(key)
    val state = remember(key) { LazyListState(saved.first, saved.second) }
    LaunchedEffect(state, key) {
        snapshotFlow { state.firstVisibleItemIndex to state.firstVisibleItemScrollOffset }
            .distinctUntilChanged()
            .collect { (index, offset) -> UiScrollMemory.setLazyOffset(key, index, offset) }
    }
    return state
}
