package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.design.VideoView
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.CanvasContentMode
import com.cosmotune.ui.pip.PipBinding
import com.cosmotune.ui.pip.PipControls
import com.cosmotune.ui.pip.PipHandle
import com.cosmotune.ui.pip.PipWish

/*
 * The song's music video in picture-in-picture (the screen menu's "Picture in picture", and leaving the app while the video
 * shows), on the window's one picture-in-picture as `PipOwner.MUSIC_VIDEO` (sharedUi's hook: Music never names Video). Previous,
 * play and pause, and next are the song's. Closing picture-in-picture leaves the music playing, as audio only; expanding it comes
 * back to Now Playing. The phone's page then shows the video alone ([NowPlayingPipPicture]); on the desktop, whose small window
 * holds the video, the page shows the record meanwhile.
 */

/** What the song's video wishes of picture-in-picture now: [ready] (leaving the app takes it there by itself) while it shows and plays. */
internal fun musicVideoPipWish(
    song: Song,
    canvas: CanvasArtwork?,
    ready: Boolean,
    isPlaying: Boolean,
    aspect: Float,
    onPlayPause: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
): PipWish = PipWish(
    ready = ready,
    aspectWidth = PipWish.aspectWidth(aspect),
    aspectHeight = PipWish.ASPECT_BASE,
    playing = isPlaying,
    controls = PipControls(playPause = onPlayPause, previous = onPrevious, next = onNext),
    title = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
    // The desktop's window draws the clip itself, in a window of the clip's own shape: cut to it, every pixel shows.
    picture = canvas?.let { clip -> { m -> CanvasArtworkPlayer(canvas = clip, isPlaying = isPlaying, modifier = m, contentMode = CanvasContentMode.CROP) } },
)

/** Binds the song's video to picture-in-picture through [handle]; the binding takes the video's place in the window too. */
@Composable
internal fun rememberMusicVideoPip(handle: PipHandle, wish: PipWish): PipBinding {
    val binding = remember(handle) { PipBinding(handle) }
    SideEffect { binding.update(wish) }
    return binding
}

/** The phone's picture-in-picture window: the song's video alone, whole, on black. Nothing is drawn on it. */
@Composable
internal fun NowPlayingPipPicture(
    canvas: CanvasArtwork,
    isPlaying: Boolean,
    aspect: Float,
    onAspect: (Float) -> Unit,
    onRenderedChanged: (Boolean) -> Unit,
    coverUrl: String?,
    modifier: Modifier = Modifier,
) {
    val shown: State<Float> = remember { androidx.compose.runtime.mutableFloatStateOf(1f) }
    Box(modifier.fillMaxSize().background(Color.Black)) {
        NowPlayingVideo(
            canvas = canvas, isPlaying = isPlaying, view = VideoView.WHOLE, aspect = aspect, onAspect = onAspect, reveal = shown,
            onRenderedChanged = onRenderedChanged, coverUrl = coverUrl, radius = 0.dp, modifier = Modifier.fillMaxSize(), glow = false,
        )
    }
}
