package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.select.ShortsFilter
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsSeedCache
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.actions.rememberVideoActions
import com.cosmotune.video.ui.blocks.VideoActionsSheet
import com.cosmotune.video.ui.blocks.VideoSheetContext
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/*
 * The Shorts composables the screens call (Home, Subscriptions, a channel, the search's Shorts filter): a shelf and a grid of the
 * Shorts cards of spec 5.0 (plan task Y-31 filled the bodies; the signatures are Y-16's), and [openShort], which is final.
 */

/** Room above a shelf, as `VideoShelf` and part two's shelves have it (`.shelf2`). */
private val SHELF_TOP = 22.dp

/** The Shorts mark before the heading: 20 dp, with 8 dp to the title. */
private val HEADING_ICON = 20.dp
private val HEADING_ICON_GAP = 8.dp

/** The heading block's own bottom padding (`CosmoSectionHeader`: 10 dp), so the mark centres on the title line. */
private val HEADING_BOTTOM = 10.dp

/** More Shorts are asked for when the last visible card is this many rows from the end. */
private const val LOAD_MORE_ROWS = 3

/**
 * A shelf of Shorts (spec 5.0, 5.15): 22 dp of room above, the heading ([CosmoSectionHeader]) with the Shorts mark before [title]
 * and its "Show all" button when [onSeeAll] is given, then a row of 132 dp [ShortsCard]s, 10 dp apart and 16 dp in from each side,
 * keyed by video id. [onOpen] opens one (the screens call [openShort]); a card's hold or right click opens the video sheet here,
 * whose actions go through the shared action handler (a Short's "Play" opens the pager with this shelf's Shorts). On the desktop
 * the row takes the platform's shelf chrome, as `VideoShelf` does.
 */
@Composable
fun ShortsShelf(title: String, items: List<VideoItem>, onOpen: (VideoItem) -> Unit, onSeeAll: (() -> Unit)?, modifier: Modifier = Modifier) {
    val actions = rememberVideoActions()
    val downloaded by rememberDownloadedIds()
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    val keys = remember(items) { uniqueKeys(items) }
    val rowState = rememberLazyListState()
    Column(modifier.fillMaxWidth().padding(top = SHELF_TOP)) {
        ShortsHeading {
            CosmoSectionHeader(title = title, onShowAll = onSeeAll)
        }
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(VideoMetrics.SHORTS_SHELF_GAP_DP.dp),
            ) {
                items(count = items.size, key = { keys[it] }) { index ->
                    val item = items[index]
                    ShortsCard(
                        item = item,
                        width = VideoMetrics.SHORTS_SHELF_W_DP.dp,
                        onClick = { onOpen(item) },
                        onMore = { sheetFor = item },
                        downloaded = item.id in downloaded,
                    )
                }
            }
        }
    }
    sheetFor?.let { item ->
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { actions.handle(item, it, VideoSheetContext.DEFAULT, list = items) },
            onDismiss = { sheetFor = null },
            downloaded = item.id in downloaded,
        )
    }
}

/**
 * The heading of a Shorts shelf: the Shorts mark in `accent`, 16 dp in, then [header] (a [CosmoSectionHeader], whose own 16 dp start
 * margin is pulled back under the mark so the title stands 8 dp after it; its end, and the "Show all" button, stay where they are).
 */
@Composable
private fun ShortsHeading(header: @Composable () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(
            VideoIcons.Shorts,
            contentDescription = null,
            tint = Color(palette.accent),
            modifier = Modifier.padding(start = CosmoSpacing.margin, bottom = HEADING_BOTTOM).size(HEADING_ICON),
        )
        Box(
            Modifier.weight(1f).layout { measurable, constraints ->
                val pull = (CosmoSpacing.margin - HEADING_ICON_GAP).roundToPx()
                val wider = constraints.copy(minWidth = constraints.minWidth + pull, maxWidth = constraints.maxWidth + pull)
                val placeable = measurable.measure(wider)
                layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-pull, 0) }
            },
        ) { header() }
    }
}

/**
 * A grid of Shorts (spec 5.0: a channel's Shorts tab, the search's Shorts filter). On the phone 3 columns 4 dp apart inside the 16 dp
 * page margin (106 x 188 at 360 dp, radius 16); when [wide], [VideoMetrics.shortsGridColumns] of the width (3 to 8) 12 dp apart inside
 * the desktop's 28 dp margin. [onOpen] opens a card; [onLoadMore] is called when the last visible card is within three rows of the
 * end. [contentPadding] is the page's: as the grids of Video mode do, the page gives the side margin in it (the search page does);
 * when it gives no sides at all, the grid keeps the margin itself. Cards are keyed by video id; a hold or right click opens the
 * video sheet here.
 */
@Composable
fun ShortsGrid(
    items: List<VideoItem>,
    onOpen: (VideoItem) -> Unit,
    onLoadMore: () -> Unit,
    contentPadding: PaddingValues,
    wide: Boolean,
    modifier: Modifier = Modifier,
) {
    val actions = rememberVideoActions()
    val downloaded by rememberDownloadedIds()
    var sheetFor by remember { mutableStateOf<VideoItem?>(null) }
    val keys = remember(items) { uniqueKeys(items) }
    val direction = LocalLayoutDirection.current
    val loadMore by rememberUpdatedState(onLoadMore)
    val state = rememberLazyGridState()
    BoxWithConstraints(modifier) {
        val layout = ShortsGridLayout.of(maxWidth.value, wide)
        val noSides = layout.keepsMargin(contentPadding.calculateStartPadding(direction).value, contentPadding.calculateEndPadding(direction).value)
        val start = if (noSides) layout.sideDp.dp else contentPadding.calculateStartPadding(direction)
        val end = if (noSides) layout.sideDp.dp else contentPadding.calculateEndPadding(direction)
        val cardWidth = layout.cardWidthDp((maxWidth - start - end).value).dp
        LaunchedEffect(state, items.size, layout.columns) {
            snapshotFlow { (state.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1) >= items.size - LOAD_MORE_ROWS * layout.columns }
                .distinctUntilChanged()
                .filter { it && items.isNotEmpty() }
                .collect { loadMore() }
        }
        LazyVerticalGrid(
            columns = GridCells.Fixed(layout.columns),
            state = state,
            contentPadding = PaddingValues(start = start, top = contentPadding.calculateTopPadding(), end = end, bottom = contentPadding.calculateBottomPadding()),
            horizontalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
            verticalArrangement = Arrangement.spacedBy(layout.gapDp.dp),
        ) {
            items(count = items.size, key = { keys[it] }) { index ->
                val item = items[index]
                ShortsCard(
                    item = item,
                    width = cardWidth,
                    onClick = { onOpen(item) },
                    onMore = { sheetFor = item },
                    downloaded = item.id in downloaded,
                )
            }
        }
    }
    sheetFor?.let { item ->
        VideoActionsSheet(
            item = item,
            context = VideoSheetContext.DEFAULT,
            onAction = { actions.handle(item, it, VideoSheetContext.DEFAULT, list = items) },
            onDismiss = { sheetFor = null },
            downloaded = item.id in downloaded,
        )
    }
}

/**
 * The Shorts grid's numbers (spec 5.0): the columns, the gap between cards (both ways) and the side margin, for a page [of] a width
 * (margins included); [cardWidthDp] shares the content width (margins left out) among the columns.
 */
internal data class ShortsGridLayout(val columns: Int, val gapDp: Float, val sideDp: Float) {
    fun cardWidthDp(contentWidthDp: Float): Float = (contentWidthDp - (columns - 1) * gapDp) / columns

    /** Whether the grid keeps the side margin ([sideDp]) itself: the page's padding gives no sides ([startDp], [endDp]) at all. */
    fun keepsMargin(startDp: Float, endDp: Float): Boolean = startDp <= 0f && endDp <= 0f

    companion object {
        /** The phone's grid, or the wide one ([VideoMetrics.shortsGridColumns]) for a page [widthDp] wide when [wide]. */
        fun of(widthDp: Float, wide: Boolean): ShortsGridLayout =
            if (wide) {
                ShortsGridLayout(VideoMetrics.shortsGridColumns(widthDp), VideoMetrics.SHORTS_GRID_WIDE_GAP_DP, VideoMetrics.DESKTOP_MARGIN_DP)
            } else {
                ShortsGridLayout(VideoMetrics.SHORTS_GRID_COLUMNS_PHONE, VideoMetrics.SHORTS_GRID_GAP_DP, VideoMetrics.MARGIN_DP)
            }
    }
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
