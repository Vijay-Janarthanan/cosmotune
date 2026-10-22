package com.cosmotune.video.ui.channel

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.states.CosmoShimmer
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_list_error
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.blocks.ThumbnailShape
import com.cosmotune.music.sharedui.resources.cosmo_state_empty
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes

/*
 * What the phone's list and the wide page's grid have in common (plan task Y-20): a page writes its items once, to a [PageItems], and
 * the phone's LazyColumn and the desktop's LazyVerticalGrid each carry them out. Plus where the content line stands in either, and the
 * skeleton and the status items of a list that loads in pages.
 */

/**
 * The items of a page, written once. A [full] item spans the page's width. [cells] are one video card each: a full-width row of the
 * phone's list, one cell of the grid on a wide page. [rows] are always full width (a playlist's row, a search-like list).
 */
internal interface PageItems {
    /** Whether this is the wide page's grid (cards without a margin of their own, in cells). */
    val wide: Boolean

    fun full(key: Any, contentType: Any? = null, content: @Composable () -> Unit)

    fun <T> cells(items: List<T>, key: (Int, T) -> Any, contentType: Any? = null, content: @Composable (T) -> Unit)

    fun <T> rows(items: List<T>, key: (Int, T) -> Any, contentType: Any? = null, content: @Composable (T) -> Unit)
}

/** [PageItems] written into a phone's `LazyColumn`. */
internal class ListPageItems(private val scope: LazyListScope) : PageItems {
    override val wide: Boolean get() = false

    override fun full(key: Any, contentType: Any?, content: @Composable () -> Unit) {
        scope.item(key = key, contentType = contentType) { content() }
    }

    override fun <T> cells(items: List<T>, key: (Int, T) -> Any, contentType: Any?, content: @Composable (T) -> Unit) = rows(items, key, contentType, content)

    override fun <T> rows(items: List<T>, key: (Int, T) -> Any, contentType: Any?, content: @Composable (T) -> Unit) {
        scope.items(count = items.size, key = { key(it, items[it]) }, contentType = { contentType }) { content(items[it]) }
    }
}

/** [PageItems] written into a wide page's `LazyVerticalGrid`. */
internal class GridPageItems(private val scope: LazyGridScope) : PageItems {
    override val wide: Boolean get() = true

    override fun full(key: Any, contentType: Any?, content: @Composable () -> Unit) {
        scope.item(key = key, span = { GridItemSpan(maxLineSpan) }, contentType = contentType) { content() }
    }

    override fun <T> cells(items: List<T>, key: (Int, T) -> Any, contentType: Any?, content: @Composable (T) -> Unit) {
        scope.items(count = items.size, key = { key(it, items[it]) }, contentType = { contentType }) { content(items[it]) }
    }

    override fun <T> rows(items: List<T>, key: (Int, T) -> Any, contentType: Any?, content: @Composable (T) -> Unit) {
        scope.items(count = items.size, key = { key(it, items[it]) }, span = { GridItemSpan(maxLineSpan) }, contentType = { contentType }) { content(items[it]) }
    }
}

/**
 * Where the page's content line stands: [contentLine] is the distance from the page's edge at which its content starts (16 dp on
 * the phone, 28 dp on a wide page), [containerPad] how much of it the list or grid already pads. The page's own blocks use [own]
 * as their side margin; a block that brings the phone's 16 dp margin of its own (the chips, a shelf, a row) goes through [fit], so
 * that margin lands on the line: pushed in where the container pads less, pulled out where it pads more.
 */
@Immutable
internal class PageLine(val contentLine: Dp, val containerPad: Dp) {
    /** The side margin of a block that has none of its own. */
    val own: Dp get() = contentLine - containerPad

    /** [modifier] with what a block that has the phone's 16 dp margin needs to stand on the line. */
    fun fit(modifier: Modifier): Modifier {
        val delta = contentLine - containerPad - CosmoSpacing.margin
        return if (delta >= 0.dp) modifier.padding(horizontal = delta) else modifier.bleed(-delta)
    }

    companion object {
        /** The phone's list: no padding in the container, blocks keep their 16 dp. */
        val Phone = PageLine(contentLine = CosmoSpacing.margin, containerPad = 0.dp)

        /** The wide page's grid, which pads the 28 dp of the page itself. */
        val WideGrid = PageLine(contentLine = VideoMetrics.DESKTOP_MARGIN_DP.dp, containerPad = VideoMetrics.DESKTOP_MARGIN_DP.dp)

        /** A wide page that lays out its own blocks in a plain column (no container padding). */
        val WideColumn = PageLine(contentLine = VideoMetrics.DESKTOP_MARGIN_DP.dp, containerPad = 0.dp)
    }
}

/**
 * Makes the block [by] wider on each side and keeps its place: it measures [by] x 2 wider and is placed [by] to the left, so the
 * block's own margin of [by] lands on the container's edge. Draws outside its bounds (nothing clips it but the list).
 */
internal fun Modifier.bleed(by: Dp): Modifier = layout { measurable, constraints ->
    val extra = by.roundToPx() * 2
    val wider = if (constraints.hasBoundedWidth) {
        constraints.copy(minWidth = if (constraints.minWidth > 0) constraints.minWidth + extra else 0, maxWidth = constraints.maxWidth + extra)
    } else {
        constraints
    }
    val placeable = measurable.measure(wider)
    layout((placeable.width - extra).coerceAtLeast(0), placeable.height) { placeable.place(-extra / 2, 0) }
}

// ---- skeletons ---------------------------------------------------------------------------------------------------------------------

/** How many skeleton cards stand where the first page will be. */
private const val SKELETON_CARDS = 3

/** A feed card's skeleton (spec 5.0): the 16:9 thumbnail, then the avatar and two lines, in the shapes they replace. */
@Composable
internal fun CardSkeleton(modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth()) {
        CosmoShimmer(Modifier.fillMaxWidth().aspectRatio(16f / 9f), ThumbnailShape)
        Row(Modifier.fillMaxWidth().padding(top = VideoMetrics.FEED_META_TOP_DP.dp), verticalAlignment = Alignment.Top) {
            CosmoShimmer(Modifier.size(VideoMetrics.FEED_AVATAR_DP.dp), CircleShape)
            Spacer(Modifier.width(VideoMetrics.FEED_GAP_DP.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CosmoShimmer(Modifier.fillMaxWidth(0.85f).height(14.dp), LINE_SHAPE)
                CosmoShimmer(Modifier.fillMaxWidth(0.5f).height(12.dp), LINE_SHAPE)
            }
        }
    }
}

/** A video row's skeleton (spec 5.0): the 160 x 90 thumbnail and two lines, padding 8 16. */
@Composable
internal fun RowSkeleton(modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = VideoMetrics.ROW_PAD_V_DP.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CosmoShimmer(Modifier.size(VideoMetrics.ROW_THUMB_W_DP.dp, VideoMetrics.ROW_THUMB_H_DP.dp), ThumbnailShape)
        Spacer(Modifier.width(CosmoSpacing.gutter))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            CosmoShimmer(Modifier.fillMaxWidth(0.9f).height(14.dp), LINE_SHAPE)
            CosmoShimmer(Modifier.fillMaxWidth(0.6f).height(14.dp), LINE_SHAPE)
            CosmoShimmer(Modifier.fillMaxWidth(0.4f).height(12.dp), LINE_SHAPE)
        }
    }
}

private val LINE_SHAPE = RoundedCornerShape(CosmoRadii.xs)

/** The skeleton cards of a list's first page: a card each (with the phone's margin and the room after it on the phone). */
internal fun PageItems.cardSkeletons(key: String) {
    cells(List(SKELETON_CARDS) { it }, key = { _, n -> "$key:skeleton:$n" }, contentType = "skeleton") {
        val margin = if (wide) Modifier else Modifier.padding(start = CosmoSpacing.margin, end = CosmoSpacing.margin, bottom = VideoMetrics.FEED_AFTER_DP.dp)
        CardSkeleton(margin)
    }
}

/** How many skeleton rows stand where a playlist's first page will be. */
private const val SKELETON_ROWS = 6

/** The skeleton rows of a list's first page. */
internal fun PageItems.rowSkeletons(key: String) {
    rows(List(SKELETON_ROWS) { it }, key = { _, n -> "$key:skeleton:$n" }, contentType = "skeleton") { RowSkeleton() }
}

// ---- the status of a list that loads in pages --------------------------------------------------------------------------------------------------

/**
 * What stands after (or instead of) the items of [paged]: the skeleton cards while the first page loads; the failure with "Try again"
 * when it failed; [empty] when it came back empty; while another page waits, a loader that asks for it as soon as it is composed
 * (so the list reads on while the end is on screen); and a "Try again" row when a later page failed. [key] makes the items' keys; [rowStyle]
 * draws the skeleton as rows instead of cards.
 */
internal fun PageItems.pagedStatus(key: String, paged: PagedItems<*>, rowStyle: Boolean = false, empty: @Composable () -> Unit = { DefaultEmpty() }) {
    when (paged.state) {
        PagedState.IDLE, PagedState.LOADING_FIRST -> if (rowStyle) rowSkeletons(key) else cardSkeletons(key)
        PagedState.FAILED_FIRST -> full("$key:failed", "status") { ListFailed(paged::retry) }
        PagedState.READY, PagedState.LOADING_MORE -> when {
            paged.items.isEmpty() && !paged.hasMore -> full("$key:empty", "status") { empty() }
            paged.hasMore -> full("$key:more", "more") { MoreLoader(paged) }
        }
        PagedState.FAILED_MORE -> full("$key:failed-more", "status") { MoreFailed(paged::retry) }
    }
}

/** "Nothing here yet", as the empty state. */
@Composable
internal fun DefaultEmpty() {
    CosmoEmptyState(message = stringResource(SharedRes.string.cosmo_state_empty))
}

/** The first page failed: the empty state with "Try again". */
@Composable
internal fun ListFailed(onRetry: () -> Unit) {
    CosmoEmptyState(message = stringResource(Res.string.video_list_error), actionLabel = stringResource(Res.string.video_retry), onAction = onRetry)
}

/** The loader at the end of a list: a blob that, once composed, asks for the next page (again for each page that arrives and leaves it on screen). */
@Composable
private fun MoreLoader(paged: PagedItems<*>) {
    LaunchedEffect(paged, paged.items.size, paged.state) { paged.loadMore() }
    Box(Modifier.fillMaxWidth().padding(vertical = 16.dp), contentAlignment = Alignment.Center) {
        CosmoLoadingBlob(Modifier.size(32.dp))
    }
}

/** A later page failed: "Try again" at the end of what is loaded. */
@Composable
private fun MoreFailed(onRetry: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(vertical = 8.dp), contentAlignment = Alignment.Center) {
        CosmoButton(stringResource(Res.string.video_retry), onRetry, kind = CosmoButtonKind.TONAL, size = CosmoButtonSize.SMALL)
    }
}
