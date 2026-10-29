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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
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
import com.cosmotune.ui.components.CosmoSegmented
import com.cosmotune.ui.components.SuperellipseShape
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

/*
 * The wide watch page (spec 5.4 "Wide": 840 dp and up, the desktop page). Two columns 24 dp apart inside the 28 dp page margin: the
 * main column (the player, radius 22, standing still at the top; under it, scrolling, the blocks of the phone with the description
 * expanded inline) and a 400 dp side column with a three-way `CosmoSegmented`: Up next, Chapters, Comments. Theatre mode (key T, or
 * its chip) puts the player across both columns, the two columns under it. The full window (key F, the player's button, a double
 * click) is [FullWindowLayer]. The page takes the keys of `PlayerKeyMap` through `onPreviewKeyEvent` while it has the focus (spec 6.6:
 * the page first, the player box after).
 */

/** The page's top under the frame's bar or the status bar (`.d2-main`: 16 dp), as the desktop page leaves it. */
private val WIDE_TOP = 16.dp

/** The gap between the segmented control and the side column's list. */
private val SIDE_GAP = 12.dp

/** The wide player's corner (spec 5.4: radius 22). */
private val PLAYER_SHAPE = SuperellipseShape(VideoMetrics.PLAYER_RADIUS_DP.dp)

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
 * [playback]; F opens the full window ([onFullWindow]), T toggles theatre mode ([onTheatre]); Escape is not the page's (no full
 * window here), and P opens picture-in-picture ([onPictureInPicture], null where there is none). True when the key was used.
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
): Boolean {
    val action = PlayerKeyMap.action(name, shift, ctrlOrCmd) ?: return false
    return performPlayerKey(
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
    val theatre = WatchSession.theatre
    val fullWindow = WatchSession.fullWindow
    val platform = rememberPlayerPlatform(playback)
    val focus = remember { FocusRequester() }
    // The page takes the focus as it opens and when the full window closes, so its keys are heard until a control (the search
    // field) takes it.
    LaunchedEffect(fullWindow) { if (!fullWindow) runCatching { focus.requestFocus() } }

    val margin = VideoMetrics.DESKTOP_MARGIN_DP.dp
    val gap = VideoMetrics.COLUMN_GAP_DP.dp
    val side = VideoMetrics.SIDE_COLUMN_DP.dp
    val statusTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val top = maxOf(contentPadding.calculateTopPadding(), statusTop + WIDE_TOP)
    val bottom = contentPadding.calculateBottomPadding()
    val contentWidth = (width - margin * 2).coerceAtLeast(0.dp)
    val mainWidth = (contentWidth - gap - side).coerceAtLeast(0.dp)
    val available = (height - top - bottom).coerceAtLeast(0.dp)
    val playerHeight = if (theatre) {
        WatchRules.theatreHeightDp(contentWidth.value, available.value, aspect).dp
    } else {
        VideoMetrics.playerHeightDp(mainWidth.value, available.value, aspect).dp
    }
    val onBounds = remember(pipUpdater) { { rect: Rect -> pipUpdater.bounds(rect) } }
    val openFullWindow = { WatchSession.fullWindow = true }
    val toggleTheatre = { WatchSession.theatre = !WatchSession.theatre }
    val title = view.title.takeIf { it.isNotEmpty() }
    // The desktop's picture-in-picture window (and the system's, on a tablet): its button and key, and the placeholder meanwhile.
    val pip = rememberPictureInPicture()
    val enterPip = rememberPipEntry(pip)
    val pipOpen = pip.manual && pip.active

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
                )
            },
    ) {
        Column(Modifier.fillMaxSize().padding(start = margin, end = margin, top = top)) {
            if (theatre) {
                WidePlayer(playback, surface, fullWindow, title, openFullWindow, onBounds, Modifier.fillMaxWidth().height(playerHeight), enterPip, pipOpen) { pip.exit() }
                Spacer(Modifier.height(gap))
            }
            Row(Modifier.fillMaxWidth().weight(1f)) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (!theatre) WidePlayer(playback, surface, fullWindow, title, openFullWindow, onBounds, Modifier.fillMaxWidth().height(playerHeight), enterPip, pipOpen) { pip.exit() }
                    LazyColumn(Modifier.fillMaxWidth().weight(1f), contentPadding = PaddingValues(bottom = bottom)) {
                        val info = view.info
                        if (info == null) {
                            if (view.loading) item(key = "loading") { MetaShimmer(0.dp) }
                        } else {
                            item(key = "meta") {
                                WatchMeta(
                                    info = info,
                                    wide = true,
                                    theatre = theatre,
                                    sidePadding = 0.dp,
                                    onOpenDescription = null,
                                    onChip = callbacks.onChip,
                                    onOpenChannel = callbacks.onOpenChannel,
                                    onSubscribe = callbacks.onSubscribe,
                                )
                            }
                            view.description?.let { description ->
                                item(key = "description") { DescriptionInline(description, callbacks.onSeek, 0.dp) }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(gap))
                WideSideColumn(view, callbacks, Modifier.width(side).fillMaxHeight().padding(bottom = bottom))
            }
        }
    }

    if (fullWindow) FullWindowLayer(playback, surface, title, onExit = { WatchSession.fullWindow = false }, onBoundsChanged = onBounds)
}

/**
 * The wide player: `VideoPlayerBox(layout = WINDOW)` cut to radius 22 on black; its full-window button and a double click open the
 * full window ([onFullWindow]), its picture-in-picture button ([onEnterPip]) and the P key open the picture-in-picture window. While the
 * full window shows, the page keeps the black box in its place (one player, one picture); while the picture-in-picture window holds
 * the picture ([pipOpen]) the box says so, with a Return button ([onReturnFromPip]).
 */
@Composable
private fun WidePlayer(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    fullWindow: Boolean,
    title: String?,
    onFullWindow: () -> Unit,
    onBounds: (Rect) -> Unit,
    modifier: Modifier,
    onEnterPip: (() -> Unit)?,
    pipOpen: Boolean,
    onReturnFromPip: () -> Unit,
) {
    // The black of the player around its picture (the documented exception).
    Box(modifier.clip(PLAYER_SHAPE).background(Color.Black)) {
        if (pipOpen) {
            PipPlaceholder(onReturn = onReturnFromPip)
        } else if (!fullWindow) {
            VideoPlayerBox(
                playback = playback,
                surface = surface,
                layout = PlayerLayout.WINDOW,
                modifier = Modifier.fillMaxSize(),
                onToggleFullscreen = onFullWindow,
                onEnterPip = onEnterPip,
                onBoundsChanged = onBounds,
                title = title,
            )
        }
    }
}

/**
 * The side column (spec 5.4): the segmented control (when there is more than one segment), then Up next (the Autoplay switch and the
 * related videos as desktop rows), the chapters (rows that seek) or the comments (`CommentsPanel(sheet = false)`).
 */
@Composable
private fun WideSideColumn(view: WatchView, callbacks: WatchCallbacks, modifier: Modifier) {
    val segments = remember(view.chapters.isNotEmpty(), view.commentsShown) { wideSegments(view.chapters.isNotEmpty(), view.commentsShown) }
    var chosen by rememberSaveable(view.videoId) { mutableStateOf(WideSegment.UP_NEXT) }
    val selected = segments.indexOf(chosen).coerceAtLeast(0)
    Column(modifier) {
        if (segments.size > 1) {
            val labels = segments.map { stringResource(it.label) }
            CosmoSegmented(options = labels, selectedIndex = selected, onSelect = { chosen = segments[it] }, fill = true)
            Spacer(Modifier.height(SIDE_GAP))
        }
        when (segments[selected]) {
            WideSegment.UP_NEXT -> {
                val downloaded = rememberDownloadedIds()
                val watched = rememberWatchedFractions(view.related)
                LazyColumn(Modifier.fillMaxWidth().weight(1f)) {
                    if (view.related.isNotEmpty()) {
                        item(key = "upnext") { UpNextHeader(view.autoplay, callbacks.onAutoplay) }
                        upNextRows(view.related, watched, downloaded, desktop = true, onPlay = callbacks.onPlay, onMore = callbacks.onMore)
                    }
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
