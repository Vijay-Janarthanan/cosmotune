package com.cosmotune.video.ui.watch

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.fullscreen.FullScreenMenu
import com.cosmotune.ui.fullscreen.FullScreenPlayer
import com.cosmotune.ui.fullscreen.FullScreenSpeed
import com.cosmotune.ui.fullscreen.FullScreenTransport
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.VideoScale
import com.cosmotune.video.player.VideoSurfaceProvider
import com.cosmotune.video.ui.player.PlayerErrorState
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.RunPlayhead
import com.cosmotune.video.ui.player.SkipSegmentButton
import com.cosmotune.video.ui.player.TickMode
import com.cosmotune.video.ui.player.VideoPlayerBox
import com.cosmotune.video.ui.player.rememberPlayhead

/*
 * Video's full screen (the phone's full screen and the desktop's full window): the one plain full-screen player of Cosmotune
 * ([FullScreenPlayer], the same as Now Playing's Cinema and Watch's full screen). The picture is `VideoPlayerBox(chrome = false)`:
 * the video and its subtitles, nothing else on it.
 */

/**
 * Full screen for [playback]: a tap shows the title line (⋯ holds captions, quality, picture in picture ([onEnterPip], where
 * the device has it) and leaving), back and ahead 10 s round play,
 * and the seek bar (none while live); they hide 3 s after the last touch; a double tap on the left or right third skips 10 s; a
 * pinch fills the screen or gives the picture back its own shape. Over it whatever the controls do: the loading blob, the error
 * with "Try again" (the controls stay up), and SponsorBlock's "Skip". [onExit] leaves; [onBoundsChanged] reports the picture's
 * place (picture-in-picture's source rectangle).
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
) {
    val state by playback.state.collectAsState()
    val failed = state.error != null || state.status == PlayerStatus.ERROR
    val buffering = state.status == PlayerStatus.BUFFERING || state.status == PlayerStatus.LOADING
    val playhead = rememberPlayhead(playback)
    RunPlayhead(playhead, if (state.pendingSkip != null) TickMode.SLOW else TickMode.OFF)
    FullScreenPlayer(
        title = title ?: state.item?.title.orEmpty(),
        subtitle = state.item?.channelName,
        playing = state.playing,
        position = playback::positionMs,
        durationMs = state.durationMs,
        onPlayPause = playback::togglePlayPause,
        onSeek = playback::seekTo,
        onLeave = onExit,
        transport = FullScreenTransport.Skip,
        live = state.live,
        holdControls = failed,
        menus = videoMenus(playback, state),
        // Picture in picture where the device has it; a long press plays at 2x (not live).
        actions = listOfNotNull(onEnterPip?.let { enter -> OrbitMenuItem("Picture in picture", "A small floating video", CosmoIcons.Pip, onClick = enter) }),
        speed = if (state.live) null else FullScreenSpeed({ playback.state.value.speed }, playback::setSpeed),
        picture = { fill, modifier ->
            VideoPlayerBox(
                playback = playback, surface = surface, layout = layout, modifier = modifier, onToggleFullscreen = onExit,
                onBoundsChanged = onBoundsChanged, title = title, chrome = false, scale = if (fill) VideoScale.FILL else VideoScale.FIT,
            )
        },
        overlay = { shown ->
            if (buffering && !failed) CosmoLoadingBlob(Modifier.align(Alignment.Center))
            if (failed) PlayerErrorState(state, onRetry = { state.item?.let { playback.play(it.id) } }, modifier = Modifier.align(Alignment.Center))
            state.pendingSkip?.let { segment ->
                SkipSegmentButton(segment, playback, playhead, onSkip = { playback.skipPending() },
                    modifier = Modifier.align(Alignment.BottomEnd).padding(bottom = if (shown) 92.dp else 24.dp))
            }
        },
    )
}

/** The ⋯ menu's lists: the captions (Off, then each track) and the quality (Auto, then the ladder), where the video has them. */
private fun videoMenus(playback: VideoPlayback, state: PlaybackUiState): List<FullScreenMenu> = buildList {
    if (state.subtitles.isNotEmpty()) {
        val current = state.subtitles.firstOrNull { it.language == state.subtitleLanguage }
        add(FullScreenMenu("Captions", current?.name ?: "Off", CosmoIcons.Lyrics, buildList {
            add(OrbitMenuItem("Off", selected = state.subtitleLanguage == null) { playback.selectSubtitles(null) })
            state.subtitles.forEach { track ->
                add(OrbitMenuItem(track.name, if (track.autoGenerated) "Made automatically" else null, selected = track.language == state.subtitleLanguage) {
                    playback.selectSubtitles(track.language)
                })
            }
        }))
    }
    if (state.ladder.isNotEmpty()) {
        add(FullScreenMenu("Quality", if (state.autoQuality) "Auto" + (state.quality?.label?.let { " · $it" } ?: "") else state.quality?.label, CosmoIcons.Equaliser, buildList {
            add(OrbitMenuItem("Auto", "Follows the connection", selected = state.autoQuality) { playback.selectQuality(null) })
            state.ladder.forEach { option ->
                add(OrbitMenuItem(option.label, selected = !state.autoQuality && option == state.quality) { playback.selectQuality(option) })
            }
        }))
    }
}
