package com.cosmotune.video.ui.shorts

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.SuperellipseShape
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_next_short
import com.cosmotune.video.resources.video_previous_short
import com.cosmotune.video.ui.comments.CommentsPanel
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/*
 * The desktop's Shorts page (spec 5.15). The numbers VideoMetrics does not carry: 16 dp between the column and its buttons (and the
 * same room kept at the column's left, so the column itself is centred), 12 dp between previous and next, 24 dp between the page and
 * the comments column; the buttons' actions end 28 dp above the column's bottom, as the phone's bottom block does.
 */

/** The column of Shorts: radius 22, 16 dp from the page's top and bottom (spec 5.15). */
private val COLUMN_SHAPE = SuperellipseShape(VideoMetrics.PLAYER_RADIUS_DP.dp)
private val COLUMN_VMARGIN = VideoMetrics.DESKTOP_SHORTS_VMARGIN_DP.dp

/** Between the column and its buttons; the buttons' width. */
private val BUTTONS_GAP = 16.dp
private val BUTTON = VideoMetrics.QUEUE_BUTTON_DP.dp
private val QUEUE_GAP = 12.dp
private val COMMENTS_GAP = 24.dp
private val ACTIONS_BOTTOM = VideoMetrics.PAGER_BLOCK_BOTTOM_DP.dp

/**
 * Below this column height the actions cannot stand at the bottom with previous and next at the exact centre (four 48 dp buttons, a
 * count, the gaps, twice, around the pair): the buttons then stack, previous and next centred in the room above the actions.
 */
private val ROOMY_COLUMN = 720.dp

/** The steps between Shorts: one per gesture of the wheel or trackpad (a gesture ends after 250 ms without a scroll), and per key. */
internal class ShortsStepGate(private val quietMs: Long = QUIET_MS) {
    private var lastWheelMs = Long.MIN_VALUE / 2
    private var lastKeyMs = Long.MIN_VALUE / 2

    /** A scroll event at [nowMs]: true when it starts a new gesture (a step), false while the same one goes on. */
    fun wheel(nowMs: Long): Boolean {
        val fresh = nowMs - lastWheelMs > quietMs
        lastWheelMs = nowMs
        return fresh
    }

    /** A key press (or its repeat) at [nowMs]: true at most once every [quietMs]. */
    fun key(nowMs: Long): Boolean {
        if (nowMs - lastKeyMs < quietMs) return false
        lastKeyMs = nowMs
        return true
    }

    companion object {
        /** The debounce of spec 5.15: 250 ms. */
        const val QUIET_MS = 250L
    }
}

/**
 * The desktop's Shorts page (spec 5.15), inside the page area: a centred 9:16 column as tall as the page less 16 dp above and below
 * (radius 22; narrower windows shrink it to fit), holding the pager with the same overlay as the phone (the top row, the scrims, the
 * bottom block, the progress line); to its right a column of C icon buttons: previous and next (48 dp) at the vertical centre, then
 * like, comments, share and more. Up and Down, the mouse wheel and the trackpad move to the previous or next Short (one step per
 * gesture, debounced 250 ms); Space pauses; M mutes. Comments open in a 400 dp side column.
 */
@Composable
internal fun ShortsDesktopPage(route: VideoRoute.Shorts, queue: ShortsQueue, controller: ShortsController, contentPadding: PaddingValues) {
    val state by controller.playback.state.collectAsState()
    val marks = rememberShortsMarks()
    val startPage = remember(queue) { queue.indexOf(route.startVideoId) }
    val pagerState = rememberPagerState(initialPage = startPage) { queue.pageCount }
    DriveShorts(queue, pagerState, controller)
    val scope = rememberCoroutineScope()
    val gate = remember { ShortsStepGate() }
    val step: (Int) -> Unit = remember(pagerState, scope) {
        { delta ->
            val last = (pagerState.pageCount - 1).coerceAtLeast(0)
            val target = (pagerState.currentPage + delta).coerceIn(0, last)
            if (target != pagerState.currentPage) scope.launch { pagerState.animateScrollToPage(target) }
        }
    }
    val focus = remember { FocusRequester() }
    LaunchedEffect(focus) { runCatching { focus.requestFocus() } }

    Row(Modifier.fillMaxSize().padding(contentPadding)) {
        BoxWithConstraints(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .focusRequester(focus)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionUp -> { if (gate.key(nowMs())) step(-1); true }
                        Key.DirectionDown -> { if (gate.key(nowMs())) step(1); true }
                        Key.Spacebar -> { controller.togglePlay(); true }
                        Key.M -> { controller.toggleMute(); true }
                        else -> false
                    }
                }
                .shortsWheel(gate, step),
        ) {
            val sideRoom = BUTTONS_GAP + BUTTON
            val tallest = (maxHeight - COLUMN_VMARGIN * 2).coerceAtLeast(0.dp)
            val widest = (maxWidth - sideRoom * 2 - BUTTONS_GAP * 2).coerceAtLeast(0.dp)
            val columnWidth = minOf(tallest * 9f / 16f, widest)
            val columnHeight = columnWidth * 16f / 9f
            val item = queue.items.getOrNull(pagerState.settledPage)
            Row(Modifier.align(Alignment.Center), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(sideRoom))
                ShortsColumn(queue, controller, pagerState, state, marks, Modifier.size(columnWidth, columnHeight))
                Spacer(Modifier.width(BUTTONS_GAP))
                ShortsSideButtons(
                    height = columnHeight,
                    item = item,
                    state = state,
                    marks = marks,
                    controller = controller,
                    canPrevious = pagerState.currentPage > 0,
                    canNext = pagerState.currentPage < pagerState.pageCount - 1,
                    onStep = step,
                )
            }
        }
        controller.commentsFor?.let { id ->
            Spacer(Modifier.width(COMMENTS_GAP))
            CommentsPanel(
                videoId = id,
                sheet = false,
                onDismiss = { controller.commentsFor = null },
                modifier = Modifier
                    .width(VideoMetrics.SIDE_COLUMN_DP.dp)
                    .fillMaxHeight()
                    .padding(top = COLUMN_VMARGIN, bottom = COLUMN_VMARGIN, end = VideoMetrics.DESKTOP_MARGIN_DP.dp),
            )
        }
    }
}

/** The column: the pager on black, clipped to radius 22, with the top row and the floating pieces over it. */
@Composable
private fun ShortsColumn(
    queue: ShortsQueue,
    controller: ShortsController,
    pagerState: PagerState,
    state: PlaybackUiState,
    marks: ShortsMarks,
    modifier: Modifier,
) {
    Box(modifier.clip(COLUMN_SHAPE).background(Color.Black)) {
        VerticalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize(),
            beyondViewportPageCount = 1,
            key = { page -> queue.keyAt(page) },
        ) { page ->
            val item = queue.items.getOrNull(page)
            if (item == null) {
                ShortsEndPage(queue, Modifier.fillMaxSize())
            } else {
                ShortsPageContent(
                    item = item,
                    active = page == pagerState.settledPage,
                    state = state,
                    controller = controller,
                    marks = marks,
                    rail = false,
                    bottomInset = 0.dp,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (queue.pageCount == 0) CosmoLoadingBlob(Modifier.align(Alignment.Center))
        ShortsTopRow(
            muted = state.muted,
            onBack = controller::back,
            onToggleMute = controller::toggleMute,
            topInset = 0.dp,
            modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
        )
        ShortsFloating(controller, below = TOP_ROW_TOP * 2 + VideoMetrics.PAGER_TOP_BUTTON_DP.dp)
    }
}

/**
 * The buttons beside the column (spec 5.15): previous and next at the vertical centre of the column, the actions of the settled Short
 * (like with its count, comments, share, more) at its bottom; a column too short for both stacks them.
 */
@Composable
private fun ShortsSideButtons(
    height: Dp,
    item: VideoItem?,
    state: PlaybackUiState,
    marks: ShortsMarks,
    controller: ShortsController,
    canPrevious: Boolean,
    canNext: Boolean,
    onStep: (Int) -> Unit,
) {
    val queueButtons: @Composable () -> Unit = {
        Column(verticalArrangement = Arrangement.spacedBy(QUEUE_GAP)) {
            CosmoIconButton(
                icon = CosmoIcons.ChevronUp,
                contentDescription = stringResource(Res.string.video_previous_short),
                onClick = { onStep(-1) },
                style = CosmoIconButtonStyle.GLASS,
                size = BUTTON,
                enabled = canPrevious,
            )
            CosmoIconButton(
                icon = CosmoIcons.ChevronDown,
                contentDescription = stringResource(Res.string.video_next_short),
                onClick = { onStep(1) },
                style = CosmoIconButtonStyle.GLASS,
                size = BUTTON,
                enabled = canNext,
            )
        }
    }
    val actions: @Composable () -> Unit = {
        if (item != null) {
            val liked = item.id in marks.liked
            val likeCount = state.details?.takeIf { it.item.id == item.id }?.likeCount
            ShortsRail(
                liked = liked,
                likeCount = likeCount,
                commentCount = null,
                onLike = { controller.toggleLike(item, liked) },
                onComments = { controller.commentsFor = if (controller.commentsFor == item.id) null else item.id },
                onShare = { controller.share(item) },
                onMore = { controller.sheetFor = item },
                overMedia = false,
            )
        }
    }
    if (height >= ROOMY_COLUMN) {
        Box(Modifier.height(height).width(BUTTON)) {
            Box(Modifier.align(Alignment.Center)) { queueButtons() }
            Box(Modifier.align(Alignment.BottomCenter).padding(bottom = ACTIONS_BOTTOM)) { actions() }
        }
    } else {
        Column(Modifier.height(height).width(BUTTON), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.weight(1f))
            queueButtons()
            Spacer(Modifier.weight(1f))
            actions()
            Spacer(Modifier.height(ACTIONS_BOTTOM))
        }
    }
}

/**
 * The wheel and the trackpad over the page: each gesture (scroll events with less than 250 ms between them) is one [step] down or
 * up; the events are taken before the pager sees them, so it does not scroll by itself.
 */
private fun Modifier.shortsWheel(gate: ShortsStepGate, step: (Int) -> Unit): Modifier = pointerInput(gate, step) {
    awaitPointerEventScope {
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (event.type != PointerEventType.Scroll) continue
            val change = event.changes.firstOrNull() ?: continue
            val dy = change.scrollDelta.y
            if (dy != 0f && gate.wheel(change.uptimeMillis)) step(if (dy > 0f) 1 else -1)
            for (each in event.changes) each.consume()
        }
    }
}

/** The clock of a key press (the key events carry none common to both platforms). */
private fun nowMs(): Long = System.nanoTime() / 1_000_000
