package com.cosmotune.video.ui.library

import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.ui.blocks.VideoPageHeading
import com.cosmotune.video.ui.blocks.VideoSectionHeading
import com.cosmotune.video.resources.video_library_label
import com.cosmotune.video.resources.Res
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

/**
 * A library page's heading (Orbit): the mono "YOURS" over [title] in the wide face with its peach full stop, [actions] at its end
 * (round buttons). It scrolls away under the top bar, which then names the page.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LibraryHeader(title: String, wide: Boolean, modifier: Modifier = Modifier, actions: @Composable RowScope.() -> Unit = {}) {
    VideoPageHeading(title, stringResource(Res.string.video_library_label), modifier, actions)
}

/**
 * A row that leads to a page (Orbit rule 8): the song row's shape (64 dp, radius 22, the page's ink at 5 %), [icon] in a 44 dp disc
 * where a cover would be, [title] over [subtitle], the [count] in the mono label face at its end (when there is one) and a chevron.
 * [divided] is kept for the callers: Orbit rows stand apart, 8 dp between them, rather than on hairlines.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun LinkRow(icon: ImageVector, title: String, count: Int?, onClick: () -> Unit, modifier: Modifier = Modifier, subtitle: String? = null, divided: Boolean = true) {
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitSongRow(
        title = title, subtitle = subtitle.orEmpty(), cover = null, fonts = fonts, look = look, onClick = onClick, icon = icon, height = 64.dp,
        modifier = modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin, vertical = 4.dp),
        trailingText = count?.toString(),
        trailing = { Icon(CosmoIcons.ChevronRight, contentDescription = null, tint = look.dim, modifier = Modifier.padding(start = 8.dp).size(CHEVRON)) },
    )
}

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
        VideoSectionHeading(title, onSeeAll = onShowAll, seeAllLabel = stringResource(SharedRes.string.show_all))
        LocalShelfRowChrome.current.Wrap(rowState) {
            LazyRow(
                state = rowState,
                contentPadding = PaddingValues(horizontal = CosmoSpacing.margin),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
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
