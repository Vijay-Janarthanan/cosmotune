package com.cosmotune.video.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.PlayerKeyMap
import com.cosmotune.video.player.rememberPictureInPicture
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_chapters
import com.cosmotune.video.resources.video_comments
import com.cosmotune.video.resources.video_up_next
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.actions.rememberDownloadedIds
import com.cosmotune.video.ui.comments.CommentsPanel
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.PlayerPlatformControls
import com.cosmotune.video.ui.player.VideoPlayerBox
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.playerKeyName
import com.cosmotune.video.ui.player.rememberPlayerPlatform
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cosmotune.ui.theme.CosmoRadii
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.lazy.LazyListScope
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.ui.shell.LocalVideoNavigator

/*
 * The wide watch page (Orbit board B, "Video watch page": 840 dp and up, the desktop page). Inside the 28 dp page margin: the top row
 * (collapse, "VIDEO · CHANNEL", the chips Up next, Chapters, Comments, and more); the main column, two thirds of the width (the whole
 * video in its frame with nothing on it, the colour card under it with play in its bite, the screen button and the seek line with
 * SponsorBlock's marks, then, scrolling, the actions, the meta line and the description); and a third for the side column (the
 * channel with Subscribe, then what the chosen chip shows). Theatre (key T, or the screen button) puts the video and the card across
 * both columns. Cinema, the full window (key F, the screen button, a double click), is [FullWindowLayer]. The page takes the keys of
 * `PlayerKeyMap` through `onPreviewKeyEvent` while it has the focus (spec 6.6).
 */

/** The page's top under the frame's bar or the status bar (`.d2-main`: 16 dp), as the desktop page leaves it. */
private val WIDE_TOP = 16.dp

/** The narrowest the side column is let be: a third of an 840 dp window would leave its rows no room for their words. */
private val SIDE_MIN = 320.dp

/** The side column's segments (spec 5.4), each with its label. */
internal enum class WideSegment(val label: StringResource) {
    UP_NEXT(Res.string.video_up_next),
    CHAPTERS(Res.string.video_chapters),
    COMMENTS(Res.string.video_comments),
}

/** The segments the side column offers: Up next always, Chapters when the video has some, Comments unless the page is offline. */
internal fun wideSegments(hasChapters: Boolean, comments: Boolean): List<WideSegment> = buildList {
    add(WideSegment.UP_NEXT)
    if (hasChapters) add(WideSegment.CHAPTERS)
    if (comments) add(WideSegment.COMMENTS)
}

/**
 * What a key does on the wide page (spec 6.6): the action of `PlayerKeyMap` for [name] (as `playerKeyName` names it), carried out on
 * [playback]; F opens the full window ([onFullWindow]), T toggles theatre mode ([onTheatre]), and P opens picture-in-picture
 * ([onPictureInPicture], null where there is none). Escape has no full window to leave here: it leaves the page ([onLeave], the
 * page's Minimise, as Back does; null where there is nowhere to go). True when the key was used.
 */
internal fun onWatchPageKey(
    name: String,
    shift: Boolean,
    ctrlOrCmd: Boolean,
    playback: VideoPlayback,
    settings: VideoSettings,
    platform: PlayerPlatformControls?,
    onFullWindow: () -> Unit,
    onTheatre: () -> Unit,
    onPictureInPicture: (() -> Unit)? = null,
    onLeave: (() -> Unit)? = null,
): Boolean {
    val action = PlayerKeyMap.action(name, shift, ctrlOrCmd)
    val done = action != null && performPlayerKey(
        action = action,
        playback = playback,
        state = playback.state.value,
        settings = settings,
        platform = platform,
        onFullWindow = onFullWindow,
        onTheatre = onTheatre,
        onExitFullWindow = null,
        onPictureInPicture = onPictureInPicture,
    )
    if (done) return true
    if (name == "Escape" && !shift && !ctrlOrCmd && onLeave != null) {
        onLeave()
        return true
    }
    return false
}

/**
 * The wide page in a [width] x [height] area (see the file's comment). [contentPadding] is the shell's: the desktop frame's top margin,
 * or the phone shell's floating chrome at the bottom on a tablet (which also draws under the status bar there).
 */
@Composable
internal fun WatchPageWide(
    view: WatchView,
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    contentPadding: PaddingValues,
    width: Dp,
    height: Dp,
    aspect: Float,
    callbacks: WatchCallbacks,
    pipUpdater: PipUpdater,
) {
    val graph = LocalVideoGraph.current
    val navigator = LocalVideoNavigator.current
    val theatre = WatchSession.theatre
    val fullWindow = WatchSession.fullWindow
    val platform = rememberPlayerPlatform(playback)
    val focus = remember { FocusRequester() }
    // The page takes the focus as it opens and when the full window closes, so its keys are heard until a control (the search
    // field) takes it.
    LaunchedEffect(fullWindow) { if (!fullWindow) runCatching { focus.requestFocus() } }
    val fonts = orbitFonts()
    val look = orbitLook()
    KeepWatchFill()

    val margin = VideoMetrics.DESKTOP_MARGIN_DP.dp
    val gap = VideoMetrics.COLUMN_GAP_DP.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxOf(contentPadding.calculateTopPadding(), statusTop + WIDE_TOP)
    val bottom = contentPadding.calculateBottomPadding()
    val contentWidth = (width - margin * 2).coerceAtLeast(0.dp)
    val side = ((contentWidth - gap) / 3).coerceAtLeast(SIDE_MIN)
    val mainWidth = (contentWidth - gap - side).coerceAtLeast(0.dp)
    // The video leaves the top row, the card and a little of the page under them their room.
    val available = (height - top - bottom - TOP_ROW_H - CARD_ROOM).coerceAtLeast(120.dp)
    val playerHeight = watchFrameHeight(if (theatre) contentWidth else mainWidth, if (theatre) available * WatchRules.THEATRE_MAX_SHARE else available, aspect, WatchSession.fill)
    val onBounds = remember(pipUpdater) { { rect: Rect -> pipUpdater.bounds(rect) } }
    val openFullWindow = { WatchSession.fullWindow = true }
    val toggleTheatre = { WatchSession.theatre = !WatchSession.theatre }
    val title = view.title.takeIf { it.isNotEmpty() }
    // The desktop's picture-in-picture window (and the system's, on a tablet): its button and key, and the placeholder meanwhile.
    val pip = rememberPictureInPicture()
    val enterPip = rememberPipEntry(pip)
    val pipOpen = pip.manual && pip.active
    val info = view.info
    val (ground, _) = coverFill(info?.item?.thumbnailUrl, view.videoId)
    val segments = remember(view.chapters.isNotEmpty(), view.commentsShown) { wideSegments(view.chapters.isNotEmpty(), view.commentsShown) }
    var chosen by rememberSaveable(view.videoId) { mutableStateOf(WideSegment.UP_NEXT) }
    val shownSegment = if (chosen in segments) chosen else WideSegment.UP_NEXT
    val screen = WatchScreen(
        fill = WatchSession.fill, onFill = { WatchSession.fill = it }, onCinema = openFullWindow,
        theatre = theatre, onTheatre = toggleTheatre, onMini = enterPip,
    )
    val streamable = info != null && !info.item.isLive && !info.item.isUpcoming

    val video: @Composable (Modifier) -> Unit = { m ->
        WidePlayer(playback, surface, fullWindow, title, ground, openFullWindow, onBounds, m.height(playerHeight), pipOpen) { pip.exit() }
    }
    val card: @Composable (Modifier) -> Unit = { m ->
        WatchTransportCard(
            playback = playback, title = view.title, thumbnail = info?.item?.thumbnailUrl, key = view.videoId, screen = screen, wide = true,
            fonts = fonts, look = look, modifier = m,
            onDownload = if (streamable) ({ callbacks.onChip(WatchChip.DOWNLOAD) }) else null,
            downloaded = info?.downloadedLabel != null,
        )
    }
    val about: LazyListScope.() -> Unit = {
        if (info == null) {
            if (view.loading) item(key = "loading") { MetaShimmer(0.dp) }
        } else {
            item(key = "meta") {
                WatchMeta(
                    info = info, wide = true, theatre = theatre, sidePadding = 0.dp, onOpenDescription = null,
                    onChip = callbacks.onChip, onOpenChannel = callbacks.onOpenChannel, onSubscribe = callbacks.onSubscribe, channel = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            view.description?.let { description ->
                item(key = "description") { DescriptionInline(description, callbacks.onSeek, 0.dp) }
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val name = playerKeyName(event) ?: return@onPreviewKeyEvent false
                onWatchPageKey(
                    name = name,
                    shift = event.isShiftPressed,
                    ctrlOrCmd = event.isCtrlPressed || event.isMetaPressed,
                    playback = playback,
                    settings = graph.settings.settings.value,
                    platform = platform,
                    onFullWindow = openFullWindow,
                    onTheatre = toggleTheatre,
                    onPictureInPicture = enterPip,
                    onLeave = if (navigator.canPop && !fullWindow) ({ navigator.pop(); Unit }) else null,
                )
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = margin, end = margin, top = top)) {
            WatchTopRow(
                label = watchLabel(info), fonts = fonts, look = look,
                onCollapse = if (navigator.canPop) ({ navigator.pop(); Unit }) else null,
                onMore = info?.let { { callbacks.onMore(it.item) } },
                chips = if (segments.size > 1) ({ WatchSegmentChips(segments, shownSegment, { chosen = it }, fonts, look) }) else null,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (theatre) {
                video(Modifier.fillMaxWidth())
                card(Modifier.padding(top = 14.dp))
                Spacer(Modifier.height(gap))
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!theatre) {
                        video(Modifier.fillMaxWidth())
                        card(Modifier.padding(top = 14.dp))
                    }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(top = 8.dp, bottom = bottom), content = about)
                }
                Spacer(Modifier.width(gap))
                WideSideColumn(view, shownSegment, callbacks, Modifier.width(side).fillMaxHeight().padding(bottom = bottom))
            }
        }
    }

    if (fullWindow) FullWindowLayer(playback, surface, title, onExit = { WatchSession.fullWindow = false }, onBoundsChanged = onBounds)
}

/** The top row's height and the room the colour card and a line of the page under it take below the video. */
private val TOP_ROW_H = 62.dp
private val CARD_ROOM = 200.dp

/**
 * The wide video: [WatchVideoFrame] in the page layout of the desktop; a double click opens the full window ([onFullWindow]). While
 * the full window shows, the page keeps the frame in its place (one player, one picture); while the picture-in-picture window holds
 * the picture ([pipOpen]) the frame says so, with a Return button ([onReturnFromPip]).
 */
@Composable
private fun WidePlayer(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    fullWindow: Boolean,
    title: String?,
    ground: Color,
    onFullWindow: () -> Unit,
    onBounds: (Rect) -> Unit,
    modifier: Modifier,
    pipOpen: Boolean,
    onReturnFromPip: () -> Unit,
) {
    if (pipOpen || fullWindow) {
        Box(modifier.clip(RoundedCornerShape(28.dp)).background(ground)) {
            if (pipOpen) PipPlaceholder(playback, onReturn = onReturnFromPip)
        }
    } else {
        WatchVideoFrame(playback, surface, PlayerLayout.WINDOW, ground, modifier, onCinema = onFullWindow, onBounds = onBounds, title = title)
    }
}

/**
 * The side column (board B): the channel row with Subscribe at its top, then what the chosen chip shows: Up next (the Autoplay
 * switch and the related videos as rows), the chapters (rows that seek) or the comments (`CommentsPanel(sheet = false)`).
 */
@Composable
private fun WideSideColumn(view: WatchView, segment: WideSegment, callbacks: WatchCallbacks, modifier: Modifier) {
    Column(modifier) {
        view.info?.let { info -> WatchChannelRow(info, 0.dp, callbacks.onOpenChannel, callbacks.onSubscribe) }
        when (segment) {
            WideSegment.UP_NEXT -> {
                val downloaded = rememberDownloadedIds()
                val watched = rememberWatchedFractions(view.related)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    upNextSection(view, watched, downloaded, desktop = true, callbacks = callbacks)
                }
            }
            WideSegment.CHAPTERS -> LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                itemsIndexed(view.chapters, key = { index, chapter -> "chapter:$index:${chapter.startMs}" }) { _, chapter ->
                    ChapterRow(chapter, callbacks.onSeek)
                }
            }
            WideSegment.COMMENTS -> CommentsPanel(
                videoId = view.videoId,
                sheet = false,
                onDismiss = {},
                modifier = Modifier.fillMaxWidth().weight(1f).padding(top = CosmoSpacing.s1),
            )
        }
    }
}
