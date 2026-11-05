package com.cosmotune.video.ui.blocks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyGridState
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem

/**
 * The grid of the wide pages, 840 dp and up (spec 5.0): [columns] = clamp(floor((W - 2 x 28 + 16) / (280 + 16)), 2, 5) for a page
 * W dp wide, each card [cardWidth] wide (the content width less the gaps, shared out); 16 dp between columns, 28 between rows. The
 * numbers are [VideoMetrics]'s.
 */
@Immutable
data class VideoGridSpec(val columns: Int, val cardWidth: Dp) {
    /** The columns as the grid takes them. */
    val cells: GridCells get() = GridCells.Fixed(columns)

    companion object {
        /** 16 dp between the columns. */
        val ColumnGap: Dp = VideoMetrics.GRID_COL_GAP_DP.dp

        /** 28 dp between the rows. */
        val RowGap: Dp = VideoMetrics.GRID_ROW_GAP_DP.dp

        /** The grid for a page [widthDp] wide (the window's width, margins included). */
        fun of(widthDp: Float): VideoGridSpec =
            VideoGridSpec(VideoMetrics.gridColumns(widthDp), VideoMetrics.gridCardWidthDp(widthDp).dp)
    }
}

/**
 * The lazy grid of a wide page, set up as the spec has it: [spec]'s columns, 16 dp between them and 28 between rows, the page margin
 * of the desktop (28 dp) at the sides unless [contentPadding] says otherwise. A page puts its header in an item that spans the line
 * (`item(span = { GridItemSpan(maxLineSpan) })`) and its cards in [videoGridItems]. The cards sit on the 28 dp line; the blocks that
 * are not cards (the chips, a shelf, a row) bring the phone's own 16 dp margin, so a page that wants them on that line as well gives
 * them 12 dp more at each side.
 */
@Composable
fun VideoGrid(
    spec: VideoGridSpec,
    modifier: Modifier = Modifier,
    state: LazyGridState = rememberLazyGridState(),
    contentPadding: PaddingValues = PaddingValues(horizontal = VideoMetrics.DESKTOP_MARGIN_DP.dp),
    content: LazyGridScope.() -> Unit,
) {
    LazyVerticalGrid(
        columns = spec.cells,
        modifier = modifier,
        state = state,
        contentPadding = contentPadding,
        horizontalArrangement = Arrangement.spacedBy(VideoGridSpec.ColumnGap),
        verticalArrangement = Arrangement.spacedBy(VideoGridSpec.RowGap),
        content = content,
    )
}

/**
 * The cells of [items] in a lazy grid: the feed card of the phone without its side margin and the room after it (the grid's gaps
 * and padding do that), keyed by video id (a repeat in the list gets a suffix). [onItemClick] and [onItemMore] are the card's tap and
 * its menu; [watched] gives a video's watched fraction by id, [downloaded] says whether a video is downloaded, and [title] is the
 * title to show for an item (a DeArrow title; the item's own by default).
 */
fun LazyGridScope.videoGridItems(
    items: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
    onItemMore: (VideoItem) -> Unit,
    watched: (String) -> Float? = { null },
    downloaded: (String) -> Boolean = { false },
    title: (VideoItem) -> String = { it.title },
) {
    val keys = uniqueKeys(items)
    items(count = items.size, key = { keys[it] }) { index ->
        val item = items[index]
        VideoCardBody(
            item = item,
            onClick = { onItemClick(item) },
            onMore = { onItemMore(item) },
            modifier = Modifier,
            watchedFraction = watched(item.id),
            title = title(item),
            downloaded = downloaded(item.id),
        )
    }
}
