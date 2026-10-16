package com.cosmotune.video.ui.shell

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

/**
 * Makes the app's accent follow the playing video (spec 10): the thumbnail of the video that is loaded, the next one in the queue
 * (so its colours are ready when it starts) and the channel (two videos of one channel settle on the same palette instead of
 * re-theming). It hands them to the shell with [com.cosmotune.ui.theme.CosmoShellState.followCover], the way Music hands over the
 * playing song's cover, and the palette engine does the rest. Nothing loaded means null for all three: the brand palette.
 * Call it once from the screen's root; it draws nothing and only a change of those three recomposes it.
 */
@Composable
internal fun VideoAccent(playback: VideoPlayback) {
    val shell = LocalCosmoShell.current ?: return
    val changes = remember(playback) { playback.state.map { it.toAccent() }.distinctUntilChanged() }
    val accent = changes.collectAsState(initial = playback.state.value.toAccent()).value
    SideEffect { shell.followCover(accent.coverUrl, accent.nextUrl, accent.channelId) }
}

/** The three values [VideoAccent] hands over. */
@Immutable
internal data class VideoAccentTarget(val coverUrl: String?, val nextUrl: String?, val channelId: String?)

internal fun PlaybackUiState.toAccent(): VideoAccentTarget =
    VideoAccentTarget(coverUrl = item?.thumbnailUrl, nextUrl = queue.firstOrNull()?.thumbnailUrl, channelId = item?.channelId)
