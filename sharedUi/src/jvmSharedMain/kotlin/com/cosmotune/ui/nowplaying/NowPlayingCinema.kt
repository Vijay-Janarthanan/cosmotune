package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.cosmotune.design.VideoView
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.ui.fullscreen.FullScreenPlayer
import com.cosmotune.ui.fullscreen.FullScreenTransport
import com.cosmotune.ui.fullscreen.FullScreenWindow
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.OrbitRing

/**
 * Cinema (the Now Playing board, V6 and C): the song's video in the one plain full-screen player ([FullScreenPlayer], the same as
 * Video's and Watch's), in the host's full window ([FullScreenWindow]: the system bars hidden, the picture into the cutout). The
 * video keeps its own shape until a pinch out fills the screen; a tap shows the title line, back / play-pause / next and the seek
 * bar, which hide after three seconds; a double tap on the left or right third skips 10 s. Back, Esc, F and the ⋯ menu's
 * "Leave full screen" leave ([onLeave]), as does turning the phone upright again when turning it sideways brought Cinema.
 */
@Composable
internal fun NowPlayingCinema(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    aspect: Float,
    onAspect: (Float) -> Unit,
    reveal: State<Float>,
    onRenderedChanged: (Boolean) -> Unit,
    coverUrl: String?,
    t: NowPlayingTransport,
    onSeek: (Long) -> Unit,
    hasPrevious: Boolean,
    hasNext: Boolean,
    onLeave: () -> Unit,
    /** Picture in picture where the device has it (the screen menu's own item); null hides it. */
    pictureInPicture: (() -> Unit)? = null,
) {
    FullScreenWindow(onExit = onLeave) {
        FullScreenPlayer(
            title = t.song.title, subtitle = t.song.artist, playing = t.isPlaying,
            position = { t.live?.positionMs() ?: t.position.positionMs }, durationMs = t.durationMs,
            onPlayPause = t.onPlayPause, onSeek = onSeek, onLeave = onLeave,
            transport = FullScreenTransport.Tracks(t.onPrevious, t.onNext, hasPrevious, hasNext),
            // The song's video follows the song, so there is no 2x here; picture in picture where the device has it.
            actions = listOfNotNull(pictureInPicture?.let { enter -> com.cosmotune.ui.orbit.OrbitMenuItem("Picture in picture", "A small floating video, the music plays on", com.cosmotune.ui.components.CosmoIcons.Pip, onClick = enter) }),
            picture = { fill, modifier ->
                Box(modifier) {
                    // Under the video until its first frame is on screen, and whenever the video has nothing to show (the song
                    // before or past the stretch the video covers): the cover on its disc with the moon circling it, never black.
                    CinemaStandIn(coverUrl, reveal, Modifier.fillMaxSize())
                    NowPlayingVideo(
                        canvas = canvas, isPlaying = isPlaying, view = if (fill) VideoView.FILL else VideoView.WHOLE, aspect = aspect, onAspect = onAspect,
                        reveal = reveal, onRenderedChanged = onRenderedChanged, coverUrl = coverUrl, radius = 0.dp, modifier = Modifier.fillMaxSize(),
                        glow = false,
                    )
                }
            },
        )
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

private val CINEMA_INK = Color(0xFFF7ECE4)
