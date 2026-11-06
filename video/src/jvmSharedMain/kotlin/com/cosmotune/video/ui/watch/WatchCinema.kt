package com.cosmotune.video.ui.watch

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.VideoPlayerBox
import kotlinx.coroutines.delay

/*
 * Cinema (the phone's full screen and the desktop's full window), as Now Playing's Cinema: the video alone on near black (Whole or
 * Fill as the page's screen button says), nothing on it; at first and again whenever the pointer moves or a finger lands, the round
 * "leave Cinema" button at the top and the slim colour card (the watch page's own: the time, the title, the buttons, the line, play
 * in its bite) floating at the foot. Both go [VideoMetrics.HIDE_AFTER_MS] after the last movement while the video plays.
 */

/** Cinema's ground: near black, a touch warm (Now Playing's). */
private val CINEMA_GROUND = Color(0xFF050304)
private val CINEMA_INK = Color(0xFFF7ECE4)

/**
 * Cinema for [playback]: the picture through `VideoPlayerBox(controls = false)` (its subtitles, gestures, loading and error; a tap
 * brings the card back rather than pausing), the leave button ([onExit]) and the card. [onEnterPip] is the screen menu's Mini where
 * the device has picture-in-picture; [onBoundsChanged] reports the picture's place.
 */
@Composable
internal fun WatchCinema(
    playback: VideoPlayback,
    surface: VideoSurfaceProvider?,
    title: String?,
    layout: PlayerLayout,
    onExit: () -> Unit,
    onBoundsChanged: (Rect) -> Unit,
    onEnterPip: (() -> Unit)? = null,
    onStir: () -> Unit = {},
) {
    val state by playback.state.collectAsState()
    val fonts = orbitFonts()
    val look = orbitLook()
    var stir by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    LaunchedEffect(stir, state.playing) {
        chrome = true
        // Paused, the card stays: it holds the play button.
        if (!state.playing) return@LaunchedEffect
        delay(VideoMetrics.HIDE_AFTER_MS)
        chrome = false
    }
    val screen = WatchScreen(
        fill = WatchSession.fill, onFill = { WatchSession.fill = it }, onCinema = onExit,
        theatre = null, onTheatre = {}, onMini = onEnterPip,
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().background(CINEMA_GROUND).testTag("video:cinema").pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move || event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) {
                        stir++
                        onStir()
                    }
                }
            }
        },
    ) {
        val wide = maxWidth >= 600.dp
        VideoPlayerBox(
            playback = playback,
            surface = surface,
            layout = layout,
            modifier = Modifier.fillMaxSize(),
            onToggleFullscreen = onExit,
            onBoundsChanged = onBoundsChanged,
            title = title,
            controls = false,
            scale = if (WatchSession.fill) VideoScale.FILL else VideoScale.FIT,
            ground = CINEMA_GROUND,
            onTap = { stir++ },
            // A pinch fills or fits the picture, as it did before the controls moved to the card: the screen menu's Whole or Fill.
            onPinch = { WatchSession.fill = it == VideoScale.FILL },
        )
        AnimatedVisibility(chrome, Modifier.fillMaxSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(420))) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Box(Modifier.align(Alignment.TopStart).padding(horizontal = 28.dp, vertical = 24.dp).background(CINEMA_GROUND.copy(alpha = 0.6f), CircleShape)) {
                    OrbitRoundButton(CosmoIcons.Expand, "Leave Cinema (Esc)", CINEMA_INK, onExit, modifier = Modifier.testTag("video:cinema:leave"))
                }
                WatchTransportCard(
                    playback = playback, title = title ?: state.item?.title.orEmpty(), thumbnail = state.item?.thumbnailUrl, key = state.item?.id ?: "cinema",
                    screen = screen, wide = wide, fonts = fonts, look = look,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).widthIn(max = 860.dp).fillMaxWidth(),
                )
            }
        }
    }
}
