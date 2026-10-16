package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.music.bitchord.data.model.Song
import com.music.bitchord.playback.PlaybackPosition
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * What the mini capsule shows of the playing video (spec 5.1), as one value: the video as the song the capsule draws ([song]: its
 * title, the channel as the artist, its thumbnail as the cover), whether it plays or is still loading, and its length for the
 * progress line (0 for a live stream, which has none). [song] is null while nothing is loaded.
 */
@Immutable
internal data class VideoCapsuleModel(val song: Song?, val playing: Boolean, val loading: Boolean, val durationMs: Long) {
    companion object {
        /** Nothing is loaded. */
        val None = VideoCapsuleModel(song = null, playing = false, loading = false, durationMs = 0L)
    }
}

/** The capsule's view of this state: [VideoCapsuleModel.None] until a video is loaded, and again after [VideoPlayback.stop]. */
internal fun PlaybackUiState.toCapsule(): VideoCapsuleModel {
    val video = item
    if (video == null || status == PlayerStatus.IDLE) return VideoCapsuleModel.None
    return VideoCapsuleModel(
        song = video.asCapsuleSong(),
        playing = playing,
        loading = status == PlayerStatus.LOADING || status == PlayerStatus.BUFFERING,
        durationMs = if (live) 0L else durationMs,
    )
}

/** A video as the song the shared capsule draws: the capsule is Music's building block and takes a `Song`. */
internal fun VideoItem.asCapsuleSong(): Song =
    Song(videoId = id, title = title, artist = channelName.orEmpty(), thumbnailUrl = thumbnailUrl, isVideo = true)

/**
 * The capsule's model, kept up to date. Only a change of the model recomposes the reader: the engine's buffered figure and the
 * rest of the state move often and the capsule shows none of them.
 */
@Composable
internal fun rememberVideoCapsule(playback: VideoPlayback): State<VideoCapsuleModel> {
    val changes = remember(playback) { playback.state.map { it.toCapsule() }.distinctUntilChanged() }
    return changes.collectAsState(initial = playback.state.value.toCapsule())
}

/**
 * The playhead for the capsule's progress line: a reading twice a second while a video plays (and one when it starts, stops or
 * is replaced), written to a [PlaybackPosition], which the capsule reads only in its draw block. Nothing here recomposes.
 */
@Composable
internal fun rememberCapsulePosition(playback: VideoPlayback, model: VideoCapsuleModel): PlaybackPosition {
    val position = remember(playback) { PlaybackPosition() }
    val shown = model.song != null
    val playing = model.playing
    LaunchedEffect(playback, shown, playing, model.song?.videoId) {
        if (!shown) return@LaunchedEffect
        position.report(playback.positionMs())
        while (playing) {
            delay(TICK_MS)
            position.report(playback.positionMs())
        }
    }
    return position
}

private const val TICK_MS = 500L
