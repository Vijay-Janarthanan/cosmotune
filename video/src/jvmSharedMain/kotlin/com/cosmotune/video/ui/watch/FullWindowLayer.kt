package com.cosmotune.video.ui.watch

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.PlayerKeyMap
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_exit_full_window
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.PlayerPlatformControls
import com.cosmotune.video.ui.player.VideoPlayerBox
import com.cosmotune.video.ui.player.performPlayerKey
import com.cosmotune.video.ui.player.playerKeyName
import com.cosmotune.video.ui.player.rememberPlayerPlatform
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/*
 * The desktop's full window (spec 5.4, after the `np-fullwindow` board): the window is the video on black, the controls in the C glass
 * bar at the bottom (the player box's `desktopFullWindow`: 80 dp, radius 28, 20 dp from the bottom, at most 720 dp wide), and "Esc to
 * exit full window" in 12.5 sp at the top right (20 dp from the top, 24 from the right, as the board places it), which fades with the
 * controls 3 s after the pointer last moved. Escape, F and the bar's button close it; the other keys of `PlayerKeyMap` are the
 * player's. It is a window of its own over the whole app ([VideoFullWindow]: a popup on the desktop, as the profile drawer's).
 */

private val HINT_TOP = 20.dp
private val HINT_END = 24.dp
private const val HINT_SP = 12.5f
private const val HINT_ALPHA = 0.8f
private val HINT_SHADOW = Shadow(Color.Black.copy(alpha = 0.6f), Offset(0f, 1f), blurRadius = 4f)

/**
 * The full window: [FullWindowContent] in its own window. [onExit] closes it (Escape, F, the bar's button, the window's Back);
 * [onBoundsChanged] reports the player's bounds.
 */
@Composable
internal fun FullWindowLayer(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String?,
    onExit: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
) {
    VideoFullWindow(onExit = onExit) {
        FullWindowContent(playback, surface, title, onExit, onBoundsChanged)
    }
}

/**
 * What the full window holds: `VideoPlayerBox(layout = WINDOW, desktopFullWindow = true)` on black and the Escape hint. It takes the
 * focus as it opens and handles the keys first ([onFullWindowKey]); a pointer move shows the hint again.
 */
@Composable
internal fun FullWindowContent(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String?,
    onExit: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
) {
    val settings = LocalVideoGraph.current.settings
    val platform = rememberPlayerPlatform(playback)
    val focus = remember { FocusRequester() }
    val hint = remember { EscHint() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            // The black of the full window (the documented exception).
            .background(Color.Black)
            .focusRequester(focus)
            .focusable()
            .onPreviewKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val name = playerKeyName(event) ?: return@onPreviewKeyEvent false
                onFullWindowKey(name, event.isShiftPressed, event.isCtrlPressed || event.isMetaPressed, playback, settings.settings.value, platform, onExit)
            }
            .pointerInput(hint) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.type == PointerEventType.Move || event.type == PointerEventType.Press) hint.poke()
                    }
                }
            },
    ) {
        VideoPlayerBox(
            playback = playback,
            surface = surface,
            layout = PlayerLayout.WINDOW,
            modifier = Modifier.fillMaxSize(),
            onToggleFullscreen = onExit,
            onBoundsChanged = onBoundsChanged,
            title = title,
            desktopFullWindow = true,
        )
        EscToExit(hint, Modifier.align(Alignment.TopEnd).padding(top = HINT_TOP, end = HINT_END))
    }
}

/**
 * What a key does in the full window (spec 6.6): Escape and F close it ([onExit]); T has no theatre here; the other actions of
 * `PlayerKeyMap` for [name] are carried out on [playback]. True when the key was used.
 */
internal fun onFullWindowKey(
    name: String,
    shift: Boolean,
    ctrlOrCmd: Boolean,
    playback: VideoPlayback,
    settings: VideoSettings,
    platform: PlayerPlatformControls?,
    onExit: () -> Unit,
): Boolean {
    val action = PlayerKeyMap.action(name, shift, ctrlOrCmd) ?: return false
    return performPlayerKey(
        action = action,
        playback = playback,
        state = playback.state.value,
        settings = settings,
        platform = platform,
        onFullWindow = onExit,
        onTheatre = null,
        onExitFullWindow = onExit,
    )
}

/**
 * When the Escape hint shows: from the full window's opening until [VideoMetrics.HIDE_AFTER_MS] after the pointer last moved (the
 * controls' own rhythm). [poke] while it shows only moves the deadline, so moving the pointer recomposes nothing; only showing and
 * hiding do. [clock] is in milliseconds.
 */
@Stable
internal class EscHint(private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    var shown by mutableStateOf(true)
        private set

    private var deadline = clock() + VideoMetrics.HIDE_AFTER_MS

    /** The pointer moved: shown for another [VideoMetrics.HIDE_AFTER_MS]. */
    fun poke() {
        deadline = clock() + VideoMetrics.HIDE_AFTER_MS
        if (!shown) shown = true
    }

    /** Milliseconds left before it hides (0 or less: now). */
    fun remaining(): Long = deadline - clock()

    fun hide() {
        shown = false
    }
}

/** "Esc to exit full window": white over the video (the documented exception), faded in and out with [hint]. */
@Composable
private fun EscToExit(hint: EscHint, modifier: Modifier) {
    val type = LocalCosmoType.current
    val reduced = LocalReducedMotion.current
    val text = stringResource(Res.string.video_exit_full_window)
    LaunchedEffect(hint, hint.shown) {
        if (!hint.shown) return@LaunchedEffect
        while (true) {
            val left = hint.remaining()
            if (left <= 0) break
            delay(left)
        }
        hint.hide()
    }
    val alpha = animateFloatAsState(
        targetValue = if (hint.shown) 1f else 0f,
        animationSpec = if (reduced) snap() else tween(VideoMetrics.FADE_MS),
        label = "esc-hint",
    )
    Text(
        text = text,
        style = type.forText(type.label.copy(fontSize = HINT_SP.sp, shadow = HINT_SHADOW), text),
        color = Color.White.copy(alpha = HINT_ALPHA),
        modifier = modifier.graphicsLayer { this.alpha = alpha.value },
    )
}
