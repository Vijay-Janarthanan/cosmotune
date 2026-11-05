package com.cosmotune.video.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.video.player.LiveWindow
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_go_live
import com.cosmotune.video.ui.VideoIcons
import org.jetbrains.compose.resources.stringResource

/** The air between the Live badge and "Go to live" (the bottom row's 8 dp, spec 6.5). */
private const val PILL_GAP_DP = 8f

/**
 * "Go to live" (spec 5.13; plan task Y-28): a C secondary pill, the floating QUIET [CosmoButton] (glass, ink label, 40 dp with its
 * 48 dp target), with the Live icon. Its label is its content description.
 */
@Composable
internal fun GoLivePill(onClick: () -> Unit, modifier: Modifier = Modifier) {
    CosmoButton(
        text = stringResource(Res.string.video_go_live),
        onClick = onClick,
        modifier = modifier,
        kind = CosmoButtonKind.QUIET,
        size = CosmoButtonSize.SMALL,
        icon = VideoIcons.Live,
        floating = true,
    )
}

/**
 * What the bottom row shows for a live stream instead of the time (spec 5.13, 6.5): the Live badge, and [GoLivePill] beside it
 * while the playhead is more than 10 s behind the live edge ([LiveWindow]). The distance is read from the playhead's whole second
 * (composition state that changes once a second while the chrome shows), and the row recomposes only when the pill comes or goes.
 * The pill seeks to the live edge.
 */
@Composable
internal fun LiveStatus(playback: VideoPlayback, state: PlaybackUiState, playhead: Playhead, onInput: () -> Unit, modifier: Modifier = Modifier) {
    val duration = state.durationMs
    val behind by remember(playhead, duration) { derivedStateOf { showsGoLive(playhead.second.longValue * 1000, duration) } }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(PILL_GAP_DP.dp), verticalAlignment = Alignment.CenterVertically) {
        LiveBadge()
        if (behind) {
            GoLivePill(onClick = {
                onInput()
                goToLive(playback, state)
                playhead.refresh()
            })
        }
    }
}

/** Whether "Go to live" shows at [positionMs] in a live window ending at [durationMs]: more than 10 s behind the live edge. */
internal fun showsGoLive(positionMs: Long, durationMs: Long): Boolean =
    LiveWindow.showGoLive(LiveWindow.behindLiveEdgeMs(positionMs, durationMs))

/** Seeks [playback] to the live edge of [state]'s window (the controller keeps it inside the window it has now). */
internal fun goToLive(playback: VideoPlayback, state: PlaybackUiState) {
    playback.seekTo(LiveWindow.liveEdgeMs(state.durationMs))
}
