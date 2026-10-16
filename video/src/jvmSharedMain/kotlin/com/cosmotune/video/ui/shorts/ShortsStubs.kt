package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsSeedCache
import com.cosmotune.video.ui.shell.VideoNavigator

/*
 * The Shorts composables the screens call (Home, Subscriptions, a channel, the search's Shorts filter). Plan task Y-31 replaces the
 * bodies of [ShortsShelf] and [ShortsGrid] with the Shorts cards of spec 5.0 and keeps their signatures; [openShort] is final.
 */

/**
 * A shelf of Shorts (spec 5.0, 5.15): the heading [title] with "See all" when [onSeeAll] is given, then a row of Shorts cards;
 * [onOpen] opens one (the screens call [openShort]). Stub: the heading and an empty row (Y-31).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShortsShelf(title: String, items: List<VideoItem>, onOpen: (VideoItem) -> Unit, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        CosmoSectionHeader(title = title, onShowAll = onSeeAll)
        LazyRow(contentPadding = PaddingValues(horizontal = CosmoSpacing.margin)) {}
    }
}

/**
 * A grid of Shorts (spec 5.0: a channel's Shorts tab, the search's Shorts filter): 3 columns on the phone, more when [wide];
 * [onOpen] opens one, [onLoadMore] asks for the next page near the end; [contentPadding] is the page's. Stub: an empty grid (Y-31).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun ShortsGrid(
    items: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onLoadMore: () -> Unit,
    contentPadding: PaddingValues,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val columns = if (wide) GridCells.Adaptive(VideoMetrics.SHORTS_GRID_MIN_CARD_DP.dp) else GridCells.Fixed(VideoMetrics.SHORTS_GRID_COLUMNS_PHONE)
    LazyVerticalGrid(columns = columns, modifier = modifier, contentPadding = contentPadding) {}
}

/**
 * Opens the Shorts pager at [item] (spec 5.15: any Short card anywhere opens the pager at that Short): the Shorts of [list] (the list
 * the card was in, [item] among them, in their order) wait in [ShortsSeedCache] under a fresh key, and
 * `VideoRoute.Shorts(origin, item.id, key)` is pushed. Final (not a stub).
 */
fun VideoNavigator.openShort(item: VideoItem, origin: ShortsOrigin, list: List<VideoItem>) {
    val shorts = list.filter { it.id == item.id || ShortsFilter.isShort(it) }.distinctBy { it.id }
    val seed = if (shorts.any { it.id == item.id }) shorts else listOf(item) + shorts
    val key = ShortsSeedCache.put(seed)
    push(VideoRoute.Shorts(origin, item.id, key))
}
