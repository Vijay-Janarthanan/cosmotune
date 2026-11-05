package com.cosmotune.video.ui.library

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.ui.blocks.VideoRow
import com.cosmotune.video.ui.blocks.VideoShelfCard
import com.cosmotune.video.ui.blocks.uniqueKeys
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.ui.text.style.TextOverflow
import com.cosmotune.ui.kit.KitDivider
import com.cosmotune.ui.kit.KitPageTitle
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.video.ui.blocks.videoClickable
import com.cosmotune.music.sharedui.resources.show_all
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue

/*
 * The pieces the library pages share (plan task Y-22; spec 5.0, 5.8): the page's list and its width on a wide window, the page
 * heading, the rows that lead to a page, a video row and a shelf that show DeArrow's title and picture, and the swipe that removes
 * a row.
 */

/** A list page on a wide window (the desktop) keeps its rows this wide at most, centred, as the search results do (spec 5.3). */
internal val LIST_MAX_WIDTH = 840.dp

/**
 * What a wide page adds to each side: the blocks carry the phone's 16 dp margin themselves (Y-13), and the desktop page's margin
 * is 28 (spec 5.0), so 12 more.
 */
internal val WIDE_EXTRA_MARGIN = (VideoMetrics.DESKTOP_MARGIN_DP - VideoMetrics.MARGIN_DP).dp

/**
 * A library page's one scrolling list: [contentPadding] (the shell's bars) on its content, and on a [wide] window 12 dp more on each
 * side and the items centred; [content] gets the modifier each item puts on its outermost block, which caps it at [LIST_MAX_WIDTH]
 * when [wide]. The list scrolls under the bars, so the top bar's title follows it.
 */
@Composable
internal fun LibraryList(
    contentPadding: PaddingValues,
    wide: Boolean,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.(cell: Modifier) -> Unit,
) {
    val direction = LocalLayoutDirection.current
    val extra = if (wide) WIDE_EXTRA_MARGIN else 0.dp
    val padding = remember(contentPadding, extra, direction) {
        PaddingValues(
            start = contentPadding.calculateStartPadding(direction) + extra,
            top = contentPadding.calculateTopPadding(),
            end = contentPadding.calculateEndPadding(direction) + extra,
            bottom = contentPadding.calculateBottomPadding(),
        )
    }
    val cell = if (wide) Modifier.widthIn(max = LIST_MAX_WIDTH) else Modifier
    LazyColumn(
        state = state,
        modifier = Modifier.fillMaxSize(),
        contentPadding = padding,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) { content(cell) }
}

/** A library page's heading: the kit's large light [title], [actions] at its end (40 dp tonal buttons). It scrolls away under the top bar, which then names the page. */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LibraryHeader(title: String, wide: Boolean, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    KitPageTitle(title = title, modifier = modifier, actions = actions)
}

/**
 * A row of a hairline list group (DESIGN_SYSTEM.md 4.9): 56 dp, [icon] in `textSecondary` at 24 dp with no tile, [title] in `rowTitle`, the [count]
 * in `meta` at its end (when there is one) and a chevron; [divided] draws the hairline under it, inset 56 dp to the text. It leads to a page.
 */
@Composable
internal fun LinkRow(icon: ImageVector, title: String, count: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, divided: Boolean = true) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = LINK_ROW)
                .videoClickable(CosmoRadii.r0, onClick, null)
                .padding(horizontal = CosmoSpacing.margin),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, contentDescription = null, tint = Color(palette.textSecondary), modifier = Modifier.size(LINK_ICON))
            Spacer(Modifier.width(LINK_ICON_GAP))
            Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
                Text(title, style = type.forText(type.rowTitle, title), color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) Text(subtitle, style = type.forText(type.meta, subtitle), color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            if (count != null) {
                Text(count.toString(), style = type.meta.tabular(), color = Color(palette.textSecondary), maxLines = 1)
                Spacer(Modifier.width(8.dp))
            }
            Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = Color(palette.textTertiary), modifier = Modifier.size(CHEVRON))
        }
        if (divided) KitDivider()
    }
}

/** A list group's row: 56 dp, the 24 dp icon, and 16 dp to the text, so the hairline's 56 dp lines up with it. */
private val LINK_ROW = 56.dp
private val LINK_ICON = 24.dp
private val LINK_ICON_GAP = 16.dp

/**
 * A [VideoRow] that shows what a card shows everywhere (Y-16): DeArrow's title and picture when the settings want them
 * ([rememberVideoTitle], [rememberVideoThumbnail]), and the desktop's row on a [wide] window.
 */
@Composable
internal fun LibraryVideoRow(
    item: VideoItem,
    onClick: () -> Unit,
    onMore: () -> Unit,
    wide: Boolean,
    modifier: Modifier = Modifier,
    watchedFraction: Float? = null,
    downloaded: Boolean = false,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val title = rememberVideoTitle(item)
    val picture = rememberVideoThumbnail(item)
    val shown = remember(item, picture) { if (picture == item.thumbnailUrl) item else item.copy(thumbnailUrl = picture) }
    VideoRow(
        item = shown,
        onClick = onClick,
        onMore = onMore,
        modifier = modifier,
        watchedFraction = watchedFraction,
        desktop = wide,
        trailing = trailing,
        title = title,
        downloaded = downloaded,
    )
}

/**
 * A shelf of shelf cards (DESIGN_SYSTEM.md 4.7) as `VideoShelf` draws it (the heading, a row 16 dp in from each side and 12 dp apart, the
 * desktop's arrows), with each card showing DeArrow's title and picture, which `VideoShelf` cannot ask for.
 */
@Composable
internal fun LibraryShelf(
    title: String,
    items: List<VideoItem>,
    onItemClick: (VideoItem) -> Unit,
    onItemMore: (VideoItem) -> Unit,
    watched: (String) -> Float?,
    downloaded: (String) -> Boolean,
    cardWidth: Dp,
    modifier: Modifier = Modifier,
    onShowAll: (() -> Unit)? = null,
) {
    val keys = remember(items) { uniqueKeys(items) }
    val rowState = rememberLazyListState()
    Column(modifier) {
        KitSectionHeader(title = title, onSeeAll = onShowAll, seeAllLabel = stringResource(SharedRes.string.show_all))
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(VideoMetrics.SHELF_GAP_DP.dp),
            ) {
                items(count = items.size, key = { keys[it] }) { index ->
                    val item = items[index]
                    val shownTitle = rememberVideoTitle(item)
                    val picture = rememberVideoThumbnail(item)
                    val shown = remember(item, shownTitle, picture) {
                        if (shownTitle == item.title && picture == item.thumbnailUrl) item else item.copy(title = shownTitle, thumbnailUrl = picture)
                    }
                    VideoShelfCard(
                        item = shown,
                        onClick = { onItemClick(item) },
                        onMore = { onItemMore(item) },
                        watchedFraction = watched(item.id),
                        width = cardWidth,
                        downloaded = downloaded(item.id),
                    )
                }
            }
        }
    }
}

/**
 * A row that a swipe either way removes (spec 5.8: "swipe to remove"): past half its width it slides out and [onRemove] runs. What it
 * uncovers is the part two swipe's container (ink at 5 %, radius 20) with the close mark at the side it left. The reveal is a gesture's
 * by-product, hidden from assistive technology: the page offers the same removal as an action of the row. With [enabled] false the
 * row does not move (watch later while it is being reordered).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SwipeToRemove(enabled: Boolean, onRemove: () -> Unit, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val remove by rememberUpdatedState(onRemove)
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) remove()
            true
        },
        positionalThreshold = { distance -> distance * BlockMetrics.SWIPE_POSITIONAL_THRESHOLD },
    )
    SwipeToDismissBox(
        state = state,
        modifier = modifier,
        enableDismissFromStartToEnd = enabled,
        enableDismissFromEndToStart = enabled,
        backgroundContent = { RemoveReveal(state) },
    ) {
        content()
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RemoveReveal(state: SwipeToDismissBoxState) {
    val colors = LocalCosmoColors.current
    val ink = Color(colors.palette.textSecondary)
    Box(
        Modifier
            .fillMaxSize()
            .clearAndSetSemantics { }
            .drawWithContent {
                val offset = try { state.requireOffset() } catch (e: IllegalStateException) { 0f }
                if (offset == 0f) return@drawWithContent
                val left = if (offset > 0f) 0f else size.width + offset
                val right = if (offset > 0f) offset else size.width
                clipRect(left = left, top = 0f, right = right, bottom = size.height) {
                    drawRoundRect(
                        colors.live(CoverField.TEXT_PRIMARY).copy(alpha = BlockMetrics.SWIPE_CONTAINER_INK),
                        cornerRadius = CornerRadius(BlockMetrics.SWIPE_CONTAINER_RADIUS_DP.dp.toPx()),
                    )
                    this@drawWithContent.drawContent()
                }
            },
    ) {
        Icon(CosmoIcons.Close, contentDescription = null, tint = ink, modifier = Modifier.align(Alignment.CenterStart).padding(start = REVEAL_INSET).size(REVEAL_ICON))
        Icon(CosmoIcons.Close, contentDescription = null, tint = ink, modifier = Modifier.align(Alignment.CenterEnd).padding(end = REVEAL_INSET).size(REVEAL_ICON))
    }
}

/** The chevron at a row's end: 20 dp, `textTertiary`. */
private val CHEVRON = 20.dp

/** The close mark of the swipe: 24 dp, 24 dp in from the edge the row left. */
private val REVEAL_ICON = 24.dp
private val REVEAL_INSET = 24.dp
