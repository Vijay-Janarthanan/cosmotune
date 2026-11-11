package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
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
import com.cosmotune.music.ui.player.CanvasSyncSource
import com.cosmotune.music.ui.player.rememberCanvasArtwork
import com.cosmotune.music.playback.PlaybackPosition
import kotlinx.coroutines.delay
import com.cosmotune.ui.pip.PipBinding
import com.cosmotune.ui.pip.PipControls
import com.cosmotune.ui.pip.PipHandle
import com.cosmotune.ui.pip.PipWish

/*
 * The song's music video in picture-in-picture (the screen menu's "Picture in picture", and leaving the app while the video
 * shows), on the window's one picture-in-picture as `PipOwner.MUSIC_VIDEO` (sharedUi's hook: Music never names Video). Previous,
 * play and pause, and next are the song's. Closing picture-in-picture leaves the music playing, as audio only; expanding it comes
 * back to Now Playing. The phone's page then shows the video alone ([NowPlayingPipPicture]); on the desktop, whose small window
 * holds the video, the page shows the record meanwhile. Leaving Now Playing (or the mode) while the small window plays keeps the
 * video there ([MusicVideoSession]): it follows the song that plays, and goes when the music stops or a song has no video.
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
    position: PlaybackPosition? = null,
): PipWish = PipWish(
    ready = ready,
    aspectWidth = PipWish.aspectWidth(aspect),
    aspectHeight = PipWish.ASPECT_BASE,
    playing = isPlaying,
    controls = PipControls(playPause = onPlayPause, previous = onPrevious, next = onNext),
    title = listOf(song.title, song.artist).filter { it.isNotBlank() }.joinToString(" · "),
    // The desktop's window draws the clip itself, in a window of the clip's own shape: cut to it, every pixel shows.
    picture = canvas?.let { clip -> { m -> CanvasArtworkPlayer(canvas = clip, isPlaying = isPlaying, modifier = m, contentMode = CanvasContentMode.CROP) } },
    // Kept when Now Playing goes while the small window plays: the root's playhead and the song the root says plays.
    keep = position?.let { playhead -> { MusicVideoSession(playhead).wish(aspect, onPlayPause, onPrevious, onNext) } },
)

/**
 * The song the app's root says plays, in every mode ([com.cosmotune.ui.theme.FollowCover] writes it), for a music video kept in
 * picture-in-picture after Now Playing has gone.
 */
internal object NowPlayingFeed {
    var song: Song? by mutableStateOf(null)
}

/**
 * The music video once Now Playing has gone and the desktop's small window plays on: it follows [NowPlayingFeed.song] (looking
 * up each song's video as Now Playing does) on the root's [position], which keeps ticking after the page, and tells whether the
 * music plays by whether the playhead moves. It ends when the music stops (no song) or a song has no music video.
 */
internal class MusicVideoSession(private val position: PlaybackPosition) {
    /** The playhead the clip follows. */
    val positionForSync: PlaybackPosition get() = position

    private var lastPositionMs = position.positionMs
    private var movedAtNanos = System.nanoTime()

    /** Set once a song's lookup has settled on no music video. */
    var noVideo by mutableStateOf(false)

    /** Whether the playhead moved within the last moment and a half. */
    fun playing(): Boolean {
        val now = System.nanoTime()
        val at = position.positionMs
        if (at != lastPositionMs) {
            lastPositionMs = at
            movedAtNanos = now
        }
        return now - movedAtNanos < PLAYING_WINDOW_NANOS
    }

    fun ended(): Boolean = NowPlayingFeed.song == null || noVideo

    fun wish(aspect: Float, onPlayPause: () -> Unit, onPrevious: () -> Unit, onNext: () -> Unit): PipWish = PipWish(
        ready = false,
        aspectWidth = PipWish.aspectWidth(aspect),
        aspectHeight = PipWish.ASPECT_BASE,
        playing = playing(),
        controls = PipControls(playPause = onPlayPause, previous = onPrevious, next = onNext, isPlaying = ::playing),
        title = NowPlayingFeed.song?.let { listOf(it.title, it.artist).filter(String::isNotBlank).joinToString(" · ") }.orEmpty(),
        picture = { m -> KeptMusicVideo(this, m) },
        ended = ::ended,
    )

    internal companion object {
        const val PLAYING_WINDOW_NANOS = 1_500_000_000L

        /** How long a song's lookup may take before the session decides it has no music video. */
        const val LOOKUP_MS = 10_000L
    }
}

/** The kept session's picture: the playing song's music video, on the root's playhead. */
@Composable
private fun KeptMusicVideo(session: MusicVideoSession, modifier: Modifier) {
    val song = NowPlayingFeed.song ?: return
    // The clip follows the song through the shared playhead slot, as under Now Playing; attached while composing, as there.
    val position = session.positionForSync
    remember(position) { CanvasSyncSource.attach(position) }
    DisposableEffect(position) { onDispose { CanvasSyncSource.detach(position) } }
    val playing by produceState(session.playing()) {
        while (true) {
            value = session.playing()
            delay(POLL_MS)
        }
    }
    val canvas = rememberCanvasArtwork(song)
    val video = canvas?.takeIf { it.syncMap != null }
    LaunchedEffect(song.videoId, video?.url) {
        session.noVideo = false
        if (video == null) {
            delay(MusicVideoSession.LOOKUP_MS)
            session.noVideo = true
        }
    }
    if (video != null) CanvasArtworkPlayer(canvas = video, isPlaying = playing, modifier = modifier, contentMode = CanvasContentMode.CROP)
}

private const val POLL_MS = 500L

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
