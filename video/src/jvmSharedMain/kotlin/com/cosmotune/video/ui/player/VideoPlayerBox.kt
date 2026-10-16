package com.cosmotune.video.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.ChromeEvent
import com.cosmotune.video.player.ChromeState
import com.cosmotune.video.player.ChromeVisibility
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerKeyMap
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.settings.VideoSettings
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

/**
 * Where the player box is drawn (spec 5.4, 6.5): [PAGE] the phone's watch page (portrait), [FULLSCREEN] the phone in full screen or
 * landscape (and picture-in-picture), [WINDOW] the desktop's page or its full window.
 */
enum class PlayerLayout { PAGE, FULLSCREEN, WINDOW }

/**
 * The video and the C chrome over it (spec 6.5; every number from [VideoMetrics]): [surface]'s picture on black, the two scrims of the
 * palette's `scrim`, the centre (back, play, forward; previous and next in [PlayerLayout.FULLSCREEN] and [PlayerLayout.WINDOW] with a
 * queue; Replay once the video ended), the top row (collapse in the page when [onCollapse] is given, the [title] in full screen,
 * captions and settings, picture-in-picture when [onEnterPip] is given), the bottom row (the time or the Live badge, full screen) and
 * the minimal scrubber; the gestures (double taps on the left and right 40 %, the long press at 2x, full screen's drags of brightness
 * and volume on a touch screen and the pinch, the page's swipes: down [onCollapse], up [onToggleFullscreen]); the subtitles; the
 * SponsorBlock "Skip" pill, the "Skipped" toast with Undo and the "Muted" chip; the error with "Try again"; the loading blob; the
 * stats card; and the settings (a sheet on the phone, a menu in [PlayerLayout.WINDOW]).
 *
 * [desktopFullWindow] puts the controls in the full window's glass bar (spec 5.4: 80 dp, radius 28, 20 dp from the bottom, at most
 * 720 dp wide). [chrome] false draws the picture and its subtitles only (picture-in-picture). [onBoundsChanged] reports the box's
 * window bounds (picture-in-picture's source rectangle). The settings are read from [LocalVideoGraph].
 *
 * Every C surface here is `cosmoGlass(hazeState = null, ...)`: no backdrop blur over the video. The playhead is never composition
 * state: the scrubber and the "Skip" line read `playback.positionMs()` while drawing, on a frame ticker that only invalidates drawing
 * while the chrome shows and the video plays; the time text is a whole second written by the same ticker.
 */
@Composable
fun VideoPlayerBox(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    layout: PlayerLayout,
    modifier: Modifier = Modifier,
    onCollapse: (() -> Unit)? = null,
    onToggleFullscreen: () -> Unit,
    onEnterPip: (() -> Unit)? = null,
    onBoundsChanged: (Rect) -> Unit = {},
    title: String? = null,
    desktopFullWindow: Boolean = false,
    chrome: Boolean = true,
) {
    val state by playback.state.collectAsState()
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    var scale by remember { mutableStateOf(VideoScale.FIT) }
    val shownScale = if (layout == PlayerLayout.FULLSCREEN && chrome) scale else VideoScale.FIT
    val captionSp = subtitleSp(layout, desktopFullWindow)

    Box(
        modifier
            // The black band around the picture (a media scrim, the documented exception to "colours come from the palette").
            .background(Color.Black)
            .onGloballyPositioned { onBoundsChanged(it.boundsInWindow()) },
    ) {
        surface?.Surface(Modifier.fillMaxSize(), shownScale)
        if (!chrome) {
            val cues by playback.engine.cues.collectAsState()
            SubtitleOverlay(cues, captionSp, settings.captionsScale, settings.captionsBackground, lift = { 0f }, liftDp = 0.dp)
            return@Box
        }
        PlayerChrome(playback, state, settings, layout, desktopFullWindow, title, onCollapse, onToggleFullscreen, onEnterPip, captionSp) { scale = it }
    }
}

/** Everything over the picture while the chrome is on (see [VideoPlayerBox]). */
@Composable
private fun BoxScope.PlayerChrome(
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    layout: PlayerLayout,
    fullWindow: Boolean,
    title: String?,
    onCollapse: (() -> Unit)?,
    onToggleFullscreen: () -> Unit,
    onEnterPip: (() -> Unit)?,
    captionSp: Float,
    onScale: (VideoScale) -> Unit,
) {
    val reduced = LocalReducedMotion.current
    val controller = rememberChromeController()
    val shown by remember(controller) { derivedStateOf { controller.state.shown } }
    val visibility = animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(VideoMetrics.FADE_MS),
        label = "chrome",
    )
    val playing = state.playing && (state.status == PlayerStatus.READY || state.status == PlayerStatus.BUFFERING)
    LaunchedEffect(controller, playing) { controller.send(if (playing) ChromeEvent.PLAY else ChromeEvent.PAUSE) }

    var settingsOpen by remember { mutableStateOf(false) }
    var statsShown by rememberSaveable { mutableStateOf(false) }
    val scrubbing = remember { mutableStateOf(false) }
    val feedback = remember { GestureFeedback() }
    val platform = rememberPlayerPlatform(playback)

    val playhead = rememberPlayhead(playback)
    val tickMode = when {
        playing && (shown || scrubbing.value || state.pendingSkip != null) -> TickMode.FRAME
        shown || statsShown || state.pendingSkip != null -> TickMode.SLOW
        else -> TickMode.OFF
    }
    RunPlayhead(playhead, tickMode)

    val buffering = state.status == PlayerStatus.BUFFERING || state.status == PlayerStatus.LOADING
    val failed = state.error != null || state.status == PlayerStatus.ERROR
    val metrics = ChromeMetrics.of(layout, fullWindow)

    // One box round the chrome takes the desktop's keys that reach it, from itself or a focused control (the page may take them
    // first, spec 6.6); a click on the video gives it focus.
    val focus = remember { FocusRequester() }
    Box(
        Modifier
            .matchParentSize()
            .onKeyEvent { event -> onPlayerKey(event, playback, state, settings, platform, fullWindow, onToggleFullscreen) { controller.send(ChromeEvent.INPUT) } }
            .focusRequester(focus)
            .focusable(enabled = layout == PlayerLayout.WINDOW),
    ) {
        // The scrims: the palette's `scrim`, faded with the chrome in the draw phase.
        ChromeScrims(metrics, visibility, Modifier.matchParentSize())

        val cues by playback.engine.cues.collectAsState()
        SubtitleOverlay(
            cues = cues,
            fontSp = captionSp,
            scale = settings.captionsScale,
            background = settings.captionsBackground,
            lift = { visibility.value },
            liftDp = metrics.subtitleLiftDp.dp,
        )

        // The gestures: under the controls while they show, over them while they are hidden (so the first tap only wakes them).
        Box(
            Modifier
                .matchParentSize()
                .zIndex(if (shown) 0f else 2f)
                .playerGestures(
                    playback = playback,
                    layout = layout,
                    platform = platform,
                    feedback = feedback,
                    stepSeconds = settings.doubleTapSeconds,
                    live = state.live,
                    onTap = {
                        if (layout == PlayerLayout.WINDOW) {
                            runCatching { focus.requestFocus() }
                            controller.send(ChromeEvent.INPUT)
                        } else {
                            controller.send(ChromeEvent.TAP)
                        }
                    },
                    onInput = { controller.send(ChromeEvent.INPUT) },
                    onCollapse = onCollapse,
                    onToggleFullscreen = onToggleFullscreen,
                    onScale = onScale,
                ),
        )

        // The controls, composed always (focus and assistive technology keep them), drawn at the chrome's opacity.
        Box(Modifier.matchParentSize().zIndex(1f).graphicsLayer { alpha = visibility.value }) {
            ChromeControls(
                playback = playback,
                state = state,
                settings = settings,
                layout = layout,
                fullWindow = fullWindow,
                metrics = metrics,
                title = title,
                failed = failed,
                buffering = buffering,
                playhead = playhead,
                scrubbing = scrubbing,
                onCollapse = onCollapse,
                onToggleFullscreen = onToggleFullscreen,
                onEnterPip = onEnterPip,
                onOpenSettings = { settingsOpen = true; controller.send(ChromeEvent.SHEET_OPEN) },
                settingsMenu = if (layout == PlayerLayout.WINDOW && settingsOpen) {
                    {
                        PlayerSettingsMenu(
                            playback = playback,
                            state = state,
                            settings = settings,
                            statsShown = statsShown,
                            onToggleStats = { statsShown = !statsShown },
                            onDismiss = { settingsOpen = false; controller.send(ChromeEvent.SHEET_CLOSE) },
                        )
                    }
                } else null,
                onInput = { controller.send(ChromeEvent.INPUT) },
                onScrub = { start -> scrubbing.value = start; controller.send(if (start) ChromeEvent.SCRUB_START else ChromeEvent.SCRUB_END) },
            )
        }

        // What shows whatever the chrome does: the gesture feedback, the segment pill, toast and chip, the loading blob, the error.
        Box(Modifier.matchParentSize().zIndex(3f)) {
            GestureOverlays(feedback, metrics, state, layout)
            state.pendingSkip?.let { segment ->
                SkipSegmentButton(
                    segment = segment,
                    playback = playback,
                    playhead = playhead,
                    onSkip = { playback.skipPending() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = metrics.skipBottomDp.dp),
                )
            }
            SegmentSkippedToast(state.lastSkip, onUndo = { playback.undoSkip() }, bottomDp = metrics.toastBottomDp, modifier = Modifier.align(Alignment.BottomCenter))
            if (buffering && !shown && !failed) CosmoLoadingBlob(Modifier.align(Alignment.Center))
            if (failed) {
                // The picture that stopped, dimmed with the palette's scrim so the message reads.
                Box(Modifier.matchParentSize().background(Color(LocalCosmoColors.current.palette.scrim).copy(alpha = VideoMetrics.SCRIM_BOTTOM_ALPHA)))
                PlayerErrorState(state, onRetry = { state.item?.let { playback.play(it.id) } }, modifier = Modifier.align(Alignment.Center))
            }
            if (statsShown) PlayerStats(playback, state, playhead, metrics, Modifier.align(Alignment.TopStart))
        }
    }

    if (layout != PlayerLayout.WINDOW && settingsOpen) {
        PlayerSettingsSheet(
            playback = playback,
            state = state,
            settings = settings,
            statsShown = statsShown,
            onToggleStats = { statsShown = !statsShown },
            onDismiss = { settingsOpen = false; controller.send(ChromeEvent.SHEET_CLOSE) },
        )
    }
}

/** The subtitles' size for each layout (spec 6.5): 16 sp in the phone's page, 20 in its full screen, 22 on the desktop page, 28 in the full window. */
private fun subtitleSp(layout: PlayerLayout, fullWindow: Boolean): Float = when {
    fullWindow -> VideoMetrics.SUB_SP_DESKTOP_FULL
    layout == PlayerLayout.WINDOW -> VideoMetrics.SUB_SP_DESKTOP
    layout == PlayerLayout.FULLSCREEN -> VideoMetrics.SUB_SP_PHONE_FULL
    else -> VideoMetrics.SUB_SP_PHONE
}

/**
 * The two scrims (spec 6.5): the palette's `scrim` from 60 % to 0 over the top band and from 0 to 72 % over the bottom band, faded
 * with the chrome. The brushes are rebuilt only when the size or the palette changes; the fade is read while drawing.
 */
@Composable
private fun ChromeScrims(metrics: ChromeMetrics, visibility: State<Float>, modifier: Modifier) {
    val colors = LocalCosmoColors.current
    Box(
        modifier
            .graphicsLayer()
            .drawWithCache {
                val scrim = colors.live(CoverField.SCRIM)
                val topH = metrics.scrimTopDp.dp.toPx()
                val bottomH = metrics.scrimBottomDp.dp.toPx()
                val top = Brush.verticalGradient(0f to scrim.copy(alpha = VideoMetrics.SCRIM_TOP_ALPHA), 1f to scrim.copy(alpha = 0f), startY = 0f, endY = topH)
                val bottom = Brush.verticalGradient(0f to scrim.copy(alpha = 0f), 1f to scrim.copy(alpha = VideoMetrics.SCRIM_BOTTOM_ALPHA), startY = size.height - bottomH, endY = size.height)
                onDrawBehind {
                    val alpha = visibility.value
                    if (alpha > 0f) {
                        drawRect(top, size = androidx.compose.ui.geometry.Size(size.width, topH), alpha = alpha)
                        drawRect(
                            bottom,
                            topLeft = androidx.compose.ui.geometry.Offset(0f, size.height - bottomH),
                            size = androidx.compose.ui.geometry.Size(size.width, bottomH),
                            alpha = alpha,
                        )
                    }
                }
            },
    )
}

/** Carries out a key of [PlayerKeyMap] that reached the box (spec 6.6); true when it was the player's. */
private fun onPlayerKey(
    event: KeyEvent,
    playback: VideoPlayback,
    state: PlaybackUiState,
    settings: VideoSettings,
    platform: PlayerPlatformControls,
    fullWindow: Boolean,
    onToggleFullscreen: () -> Unit,
    onInput: () -> Unit,
): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val name = playerKeyName(event) ?: return false
    val action = PlayerKeyMap.action(name, event.isShiftPressed, event.isCtrlPressed || event.isMetaPressed) ?: return false
    val done = performPlayerKey(
        action = action,
        playback = playback,
        state = state,
        settings = settings,
        platform = platform,
        onFullWindow = onToggleFullscreen,
        onExitFullWindow = if (fullWindow) onToggleFullscreen else null,
    )
    if (done) onInput()
    return done
}

// ---- the chrome's clock -------------------------------------------------------------------------------------------------------

/** The milliseconds the chrome's timers count in: monotonic, so a change of the wall clock moves nothing. */
internal fun chromeNowMs(): Long = System.nanoTime() / 1_000_000

/**
 * The chrome's visibility ([ChromeVisibility]) as snapshot state, with its own timer: the state is replaced on every event, and a
 * coroutine waits until [ChromeState.hiddenAt] and sends [ChromeEvent.TICK] (a later event restarts the wait).
 */
@Stable
internal class ChromeController {
    var state: ChromeState by mutableStateOf(ChromeState())
        private set

    fun send(event: ChromeEvent) {
        val next = ChromeVisibility.next(state, event, chromeNowMs())
        if (next != state) state = next
    }
}

@Composable
internal fun rememberChromeController(): ChromeController {
    val controller = remember { ChromeController() }
    LaunchedEffect(controller) {
        snapshotFlow { controller.state.hiddenAt }.collectLatest { at ->
            if (at != null) {
                delay((at - chromeNowMs()).coerceAtLeast(0))
                controller.send(ChromeEvent.TICK)
            }
        }
    }
    return controller
}

// ---- the playhead ticker --------------------------------------------------------------------------------------------------------

/** How often the playhead is sampled: every frame (playing, chrome shown), four times a second (paused or only the stats), or not at all. */
internal enum class TickMode { FRAME, SLOW, OFF }

/**
 * The playhead for the chrome without making it composition state: [frame] changes on every sample and is read only inside draw
 * lambdas (so a sample invalidates drawing, never composition), [second] is the whole second the time text shows (it changes once a
 * second while playing). [sample] is the only place that asks the playback for its position outside a draw lambda.
 */
@Stable
internal class Playhead(private val playback: VideoPlayback) {
    val frame = mutableLongStateOf(0L)
    val second = mutableLongStateOf(0L)

    fun sample(stamp: Long) {
        frame.longValue = stamp
        val now = playback.positionMs() / 1000
        if (now != second.longValue) second.longValue = now
    }

    /** Draws again now (after a seek made while paused). */
    fun refresh() = sample(frame.longValue + 1)

    /** The playhead now, for an event handler (a key that starts a scrub); never read it in composition. */
    fun now(): Long = playback.positionMs()
}

@Composable
internal fun rememberPlayhead(playback: VideoPlayback): Playhead = remember(playback) { Playhead(playback) }

@Composable
private fun RunPlayhead(playhead: Playhead, mode: TickMode) {
    // One lambda for every frame: nothing is allocated per frame by this loop.
    val onFrame: (Long) -> Unit = remember(playhead) { { nanos -> playhead.sample(nanos / 1_000_000) } }
    LaunchedEffect(playhead, mode) {
        playhead.refresh()
        when (mode) {
            TickMode.FRAME -> while (true) withFrameNanos(onFrame)
            TickMode.SLOW -> while (true) {
                delay(VideoPlayerController.TICK_MS)
                playhead.refresh()
            }
            TickMode.OFF -> Unit
        }
    }
}
