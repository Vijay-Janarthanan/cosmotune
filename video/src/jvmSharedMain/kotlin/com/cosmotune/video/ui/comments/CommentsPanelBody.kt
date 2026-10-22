package com.cosmotune.video.ui.comments

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.Springs
import com.cosmotune.design.TypeScale
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.bridge.CosmoSheetHandle
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.ui.components.cosmoGlass
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.comments.CommentsFeed
import com.cosmotune.video.comments.CommentsState
import com.cosmotune.video.design.VideoText
import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.resources.video_comments_count
import com.cosmotune.video.resources.video_comments_empty
import com.cosmotune.video.resources.video_comments_newest
import com.cosmotune.video.resources.video_comments_off
import com.cosmotune.video.resources.video_comments_top
import com.cosmotune.video.resources.video_retry
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.rememberMetaStyle
import com.cosmotune.video.ui.shell.VideoBackHandler
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import com.cosmotune.music.sharedui.resources.Res as SharedRes
import com.cosmotune.music.sharedui.resources.cosmo_close

/*
 * The panel around the list (spec 5.9): the sheet of the phone, a detached solid-glass card that the caller places under the player, or
 * the plain column of the desktop; the header (title, count, Top and Newest, close); and the body that swaps between the comments and
 * the replies of one comment.
 */

/** The sheet is at most this share of the window high (the same rule as part two's sheet surface). */
private const val SHEET_MAX_HEIGHT_SHARE = 0.85f

/** Pulling the grabber down this far, or flinging it this fast (dp per second), closes the sheet. */
private val DISMISS_DRAG = 96.dp
private val DISMISS_FLING = 1200.dp

private val SHEET_SHAPE = SuperellipseShape(CosmoRadii.sheet)

/** The header's padding: 4 20 8. */
private val HEADER_START = 20.dp
private val HEADER_TOP = 4.dp
private val HEADER_END = 20.dp
private val HEADER_BOTTOM = 8.dp

/** The 40 dp close button reserves 48 dp; shifted this far so its cross stands near the header's right margin. */
private val CLOSE_SHIFT = 12.dp

/**
 * The surface of the panel. On the phone ([sheet]) it is part two's sheet look, built here because that surface scrolls its content
 * itself and a long lazy list cannot sit inside it: [CosmoRadii.sheetInset] in from the sides and the bottom, a 36 dp superellipse of
 * the solid glass (no backdrop: the sheet lies over the page and the playing video), the grabber on top, at most 85 % of the window
 * high and as high as the caller's modifier allows (the caller gives the offset under the player). The grabber pulls the sheet down to
 * close it. Touches on the sheet do not reach the page behind it. On the desktop it is a plain column, never taller than the window,
 * so the list inside it always has a height to scroll in.
 */
@Composable
internal fun CommentsSurface(sheet: Boolean, onDismiss: () -> Unit, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val density = LocalDensity.current
    val colors = LocalCosmoColors.current
    val windowHeight = with(density) { LocalWindowInfo.current.containerSize.height.toDp() }
    if (!sheet) {
        Column(modifier.fillMaxWidth().heightIn(max = windowHeight), content = content)
        return
    }
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val pull = remember { Animatable(0f) }
    val dismissPx = with(density) { DISMISS_DRAG.toPx() }
    val flingPx = with(density) { DISMISS_FLING.toPx() }
    val dragState = rememberDraggableState { delta -> scope.launch { pull.snapTo((pull.value + delta).coerceAtLeast(0f)) } }
    Column(
        modifier
            .fillMaxWidth()
            .heightIn(max = windowHeight * SHEET_MAX_HEIGHT_SHARE)
            .fillMaxHeight()
            // read in the layer block: pulling the sheet moves a layer and recomposes nothing
            .graphicsLayer { translationY = pull.value }
            .padding(start = CosmoRadii.sheetInset, end = CosmoRadii.sheetInset, bottom = CosmoRadii.sheetInset)
            // under the glass, the page's own ground: the glass is 94 % solid, and this sheet has no scrim over the page behind it (a modal
            // sheet has one), so without it the page's words would show faintly through the header and the first comments
            .drawWithCache {
                // the superellipse's outline is a path; the cache makes it once per size
                val ground = (SHEET_SHAPE.createOutline(size, layoutDirection, this) as? Outline.Generic)?.path
                onDrawBehind { if (ground != null) drawPath(ground, colors.live(CoverField.BACKGROUND_BOTTOM)) }
            }
            .cosmoGlass(hazeState = null, shape = SHEET_SHAPE)
            // an empty pointer input still takes the touches that land on the sheet: the page behind it does not scroll or tap
            .pointerInput(Unit) {},
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .draggable(
                    state = dragState,
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        if (pull.value > dismissPx || velocity > flingPx) onDismiss() else pull.animateTo(0f, CosmoMotion.move(reduced))
                    },
                ),
            contentAlignment = Alignment.Center,
        ) { CosmoSheetHandle() }
        content()
    }
}

/**
 * The panel's body: the header and the comments, or the replies of the comment in [replyTo] (a cross-fade between them). The state of
 * the comments (their scroll position and which are open) is kept in the caller's [listState] and [expanded] while the replies show.
 */
@Composable
internal fun ColumnScope.CommentsPanelBody(
    feed: CommentsFeed,
    sheet: Boolean,
    onDismiss: () -> Unit,
    listState: LazyListState,
    expanded: ExpandedComments,
    replyTo: Comment?,
    onReplyTo: (Comment?) -> Unit,
) {
    val state by feed.state.collectAsState()
    val reduced = LocalReducedMotion.current
    // the system Back key closes the replies before it closes the panel (the page's own handler, if it has one, is registered before this)
    VideoBackHandler(enabled = replyTo != null) { onReplyTo(null) }
    Crossfade(targetState = replyTo, modifier = Modifier.weight(1f), animationSpec = fade(reduced), label = "comments") { open ->
        val token = open?.replies
        if (open != null && token != null) {
            RepliesList(open, token, sheet, onBack = { onReplyTo(null) }, onDismiss = onDismiss, modifier = Modifier.fillMaxSize())
        } else {
            Column(Modifier.fillMaxSize()) {
                CommentsHeader(state, sheet, onSort = feed::setSort, onDismiss = onDismiss)
                Box(Modifier.weight(1f).fillMaxWidth()) {
                    CommentsContent(feed, state, sheet, listState, expanded, onReplyTo)
                }
            }
        }
    }
}

/** Under the header: the turned-off state, a failure with "Try again", "No comments yet", or the list. */
@Composable
private fun CommentsContent(
    feed: CommentsFeed,
    state: CommentsState,
    sheet: Boolean,
    listState: LazyListState,
    expanded: ExpandedComments,
    onReplyTo: (Comment?) -> Unit,
) {
    val failure = state.failure
    when {
        state.disabled -> CosmoEmptyState(message = stringResource(Res.string.video_comments_off), icon = VideoIcons.Comment)
        failure != null -> CosmoEmptyState(
            message = commentsFailureText(failure),
            icon = VideoIcons.Comment,
            actionLabel = stringResource(Res.string.video_retry),
            onAction = feed::retry,
        )
        state.empty -> CosmoEmptyState(message = stringResource(Res.string.video_comments_empty), icon = VideoIcons.Comment)
        else -> CommentsList(
            state = state,
            listState = listState,
            expanded = expanded,
            floating = sheet,
            onReplies = { onReplyTo(it) },
            onLoadMore = feed::loadMore,
            onRetry = feed::retry,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * The header (spec 5.9, padding 4 20 8): "Comments" as a title with the count under it at 13 sp, Top and Newest when the source offers
 * them (the sheet's chips are C, the column's B), and on the sheet a 40 dp close button.
 */
@Composable
internal fun CommentsHeader(state: CommentsState, sheet: Boolean, onSort: (CommentSort) -> Unit, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val title = stringResource(Res.string.video_comments)
    val total = state.total
    val count = if (total != null) stringResource(Res.string.video_comments_count, VideoText.compact(total)) else null
    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = HEADER_START, top = HEADER_TOP, end = HEADER_END, bottom = HEADER_BOTTOM),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = type.forText(type.title(TypeScale.TITLE_S), title),
                    color = Color(palette.textPrimary),
                    modifier = Modifier.semantics { heading() },
                )
                if (count != null) {
                    Text(text = count, style = rememberMetaStyle(13f, count), color = Color(palette.textSecondary), maxLines = 1)
                }
            }
            if (sheet) CommentsCloseButton(onDismiss)
        }
        if (state.sortable) {
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (sort in CommentSort.entries) {
                    val label = stringResource(if (sort == CommentSort.TOP) Res.string.video_comments_top else Res.string.video_comments_newest)
                    CosmoChip(
                        label = label,
                        selected = state.sort == sort,
                        onClick = { onSort(sort) },
                        floating = sheet,
                        haptic = state.sort != sort,
                    )
                }
            }
        }
    }
}

/** The sheet's 40 dp close button: the cross in ink on the sheet's glass (C, no fill of its own). */
@Composable
internal fun CommentsCloseButton(onDismiss: () -> Unit) {
    CosmoIconButton(
        icon = CosmoIcons.Close,
        contentDescription = stringResource(SharedRes.string.cosmo_close),
        onClick = onDismiss,
        modifier = Modifier.offset(x = CLOSE_SHIFT),
        size = 40.dp,
        floating = true,
    )
}

/** The cross-fade between the comments and the replies: the controls' own fast spring, or 150 ms under reduced motion. */
private fun fade(reduced: Boolean): FiniteAnimationSpec<Float> =
    if (reduced) tween(Springs.REDUCED_MS) else spring(dampingRatio = Springs.EFFECTS.dampingRatio, stiffness = Springs.EFFECTS.stiffness)
