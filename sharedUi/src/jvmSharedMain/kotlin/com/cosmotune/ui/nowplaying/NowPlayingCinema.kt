package com.cosmotune.ui.nowplaying

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.VideoView
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.shell.LocalCosmoPlayerDismiss
import kotlinx.coroutines.delay

/**
 * Cinema (board section 2): the video takes the whole window (shown as the kept [view] says, Whole letterboxed on black or Fill
 * cut to the window), and nothing is on it but, at first and again whenever the pointer moves or a finger lands, a close button
 * at the top start, a "leave Cinema" button at the top end and the slim song card floating at the foot; they fade away
 * [CINEMA_CHROME_MS] after the last movement (not while [menuOpen]). A double tap or double click on the video leaves, as do Esc
 * and back (the caller's back handler) and the leave button ([onLeave]).
 */
@Composable
internal fun NowPlayingCinema(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    view: VideoView,
    aspect: Float,
    onAspect: (Float) -> Unit,
    reveal: State<Float>,
    onRenderedChanged: (Boolean) -> Unit,
    coverUrl: String?,
    t: NowPlayingTransport,
    fonts: OrbitFonts,
    look: OrbitLook,
    buttons: List<CardButton>,
    /** The window is wide (the desktop): the slim card carries the volume. */
    volume: Boolean = false,
    menuOpen: Boolean,
    onLeave: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val dismiss = LocalCosmoPlayerDismiss.current
    var stir by remember { mutableIntStateOf(0) }
    var chrome by remember { mutableStateOf(true) }
    LaunchedEffect(stir, menuOpen) {
        chrome = true
        if (menuOpen) return@LaunchedEffect
        delay(CINEMA_CHROME_MS)
        chrome = false
    }
    Box(
        modifier.fillMaxSize().background(CINEMA_GROUND).pointerInput(Unit) {
            awaitPointerEventScope {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    if (event.type == PointerEventType.Move || event.type == PointerEventType.Press || event.type == PointerEventType.Scroll) stir++
                }
            }
        },
    ) {
        // Under the video until its first frame is on screen, and whenever the video has nothing to show (the song before or
        // past the stretch the video covers): the cover on its disc with the moon circling it, never a black window.
        CinemaStandIn(coverUrl, reveal, Modifier.fillMaxSize())
        NowPlayingVideo(
            canvas = canvas, isPlaying = isPlaying, view = view, aspect = aspect, onAspect = onAspect, reveal = reveal,
            onRenderedChanged = onRenderedChanged, coverUrl = coverUrl, radius = 0.dp, modifier = Modifier.fillMaxSize(),
            glow = false, onDoubleTap = onLeave,
        )
        AnimatedVisibility(chrome, Modifier.fillMaxSize(), enter = fadeIn(tween(220)), exit = fadeOut(tween(420))) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 24.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CinemaButton(CosmoIcons.ChevronDown, "Close the player", dismiss)
                    CinemaButton(CosmoIcons.Expand, "Leave Cinema (Esc)", onLeave)
                }
                NowPlayingSlimCard(
                    t, fonts, look, buttons, volume = volume,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(start = 24.dp, end = 24.dp, bottom = 24.dp).widthIn(max = 860.dp).fillMaxWidth(),
                )
            }
        }
    }
}

/**
 * What Cinema shows while its video has no frame yet: the cover as a disc in the middle of the window with the orbit's moon
 * circling it, fading out as the video comes in ([reveal], 0 = no frame yet, 1 = the video in). Cinema mounts a decoder of its
 * own, and on a slow network or a song the video does not cover yet that first frame can be a long time coming; before this
 * the window stayed black for all of it.
 */
@Composable
internal fun CinemaStandIn(coverUrl: String?, reveal: State<Float>, modifier: Modifier = Modifier) {
    BoxWithConstraints(modifier.graphicsLayer { alpha = 1f - reveal.value.coerceIn(0f, 1f) }, contentAlignment = Alignment.Center) {
        val disc = (minOf(maxWidth, maxHeight) * CINEMA_STAND_IN_SHARE).coerceAtMost(CINEMA_STAND_IN_MAX)
        Box(contentAlignment = Alignment.Center) {
            Disc(coverUrl, disc)
            OrbitRing(disc + 28.dp, CINEMA_INK, progress = null, spinning = true, stroke = 1.5.dp, moon = 4.dp,
                modifier = Modifier.semantics { contentDescription = "Loading the video" })
        }
    }
}

/** The stand-in disc: about half the shorter side of the window, never larger than this. */
private const val CINEMA_STAND_IN_SHARE = 0.5f
private val CINEMA_STAND_IN_MAX = 360.dp

@Composable
private fun CinemaButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    Box(Modifier.background(CINEMA_GROUND.copy(alpha = 0.6f), androidx.compose.foundation.shape.CircleShape)) {
        OrbitRoundButton(icon, label, CINEMA_INK, onClick)
    }
}

/** Cinema's ground: near black, a touch warm. */
private val CINEMA_GROUND = Color(0xFF050304)
private val CINEMA_INK = Color(0xFFF7ECE4)

/** How long Cinema's buttons and card stay after the pointer last moved. */
internal const val CINEMA_CHROME_MS = 3_000L
