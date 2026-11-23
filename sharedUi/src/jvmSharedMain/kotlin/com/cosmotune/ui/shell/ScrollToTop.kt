package com.cosmotune.ui.shell

import androidx.compose.foundation.lazy.LazyListState

/**
 * A tap on the tab already chosen scrolls its page back to the top (upstream 1.10's navigation fix). A long way down it first jumps
 * to a few rows from the top, so the glide is short and quick however far the page was scrolled; [jumpFrom] rows is the line.
 */
suspend fun scrollToTopGently(state: LazyListState, jumpFrom: Int = SCROLL_TOP_JUMP_FROM) {
    if (state.firstVisibleItemIndex == 0 && state.firstVisibleItemScrollOffset == 0) return
    if (state.firstVisibleItemIndex > jumpFrom) state.scrollToItem(jumpFrom)
    state.animateScrollToItem(0)
}

/** Past this many rows, [scrollToTopGently] jumps before it glides. */
const val SCROLL_TOP_JUMP_FROM = 8

/** Whether a tab tap is a reselect that should scroll to the top: the same tab, already at its own root page (nothing over it). */
fun isTabReselect(tapped: Int, selected: Int, atRoot: Boolean): Boolean = tapped == selected && atRoot
