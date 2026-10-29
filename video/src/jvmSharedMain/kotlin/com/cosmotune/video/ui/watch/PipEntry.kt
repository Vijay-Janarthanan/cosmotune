package com.cosmotune.video.ui.watch

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoButtonSize
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.video.design.VideoMetrics
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.PictureInPictureState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_pip_playing
import com.cosmotune.video.resources.video_pip_return
import com.cosmotune.video.resources.video_player_settings
import com.cosmotune.video.resources.video_stats
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.blocks.ThumbnailFrame
import com.cosmotune.video.ui.branding.rememberVideoThumbnail
import com.cosmotune.video.ui.branding.rememberVideoTitle
import com.cosmotune.video.ui.player.CaptionsButton
import com.cosmotune.video.ui.player.CentreControls
import com.cosmotune.video.ui.player.ChromeMetrics
import com.cosmotune.video.ui.player.LiveStatus
import com.cosmotune.video.ui.player.PlayerLayout
import com.cosmotune.video.ui.player.PlayerScrubber
import com.cosmotune.video.ui.player.PlayerSettingsMenu
import com.cosmotune.video.ui.player.PlayerStats
import com.cosmotune.video.ui.player.PlayerStatsSwitch
import com.cosmotune.video.ui.player.RunPlayhead
import com.cosmotune.video.ui.player.SCRUB_TOUCH_DP
import com.cosmotune.video.ui.player.TickMode
import com.cosmotune.video.ui.player.TimeText
import com.cosmotune.video.ui.player.rememberPlayhead
import org.jetbrains.compose.resources.stringResource

/**
 * What the picture-in-picture button does on this device, or null where there is no such button: on the desktop it opens the window
 * of its own ([PictureInPictureState.enter]); on the phone it is the system's `enterPictureInPictureMode` (null on a device without
 * the feature). Both pages of the player ask for it, so their buttons agree.
 */
@Composable
internal fun rememberPipEntry(pip: PictureInPictureState): (() -> Unit)? {
    val system = rememberEnterPictureInPicture()
    return remember(pip, system, pip.supported) {
        when {
            !pip.supported -> null
            pip.manual -> ({ pip.enter() })
            else -> system
        }
    }
}

/**
 * What the page shows in place of the player while the desktop's picture-in-picture window holds the picture: a small control surface
 * for the video that is playing there. The video's own picture is the background (dimmed), its title and "Playing in
 * picture-in-picture" on top, the controls the player has (captions, the "Stats for nerds" switch, the settings menu with speed,
 * quality, captions, audio track, aspect, smoothness and loop; previous, back, play and pause, forward, next; the scrubber and the
 * time) and "Return" ([onReturn]). The stats card draws here, live, while the stats are on ([PlayerStatsSwitch]); the
 * picture-in-picture window itself stays clean. White on black: the player's own black, the documented exception.
 */
@Composable
internal fun PipPlaceholder(playback: VideoPlayback, onReturn: () -> Unit, modifier: Modifier = Modifier) {
    val state by playback.state.collectAsState()
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    val playhead = rememberPlayhead(playback)
    RunPlayhead(playhead, TickMode.SLOW)
    val metrics = ChromeMetrics.of(PlayerLayout.WINDOW, fullWindow = false)
    var settingsOpen by remember { mutableStateOf(false) }
    var statsShown by PlayerStatsSwitch::shown
    val item = state.item
    val buffering = state.status == PlayerStatus.BUFFERING || state.status == PlayerStatus.LOADING
    val failed = state.error != null || state.status == PlayerStatus.ERROR
    val noInput = remember { { } }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        // The video's own picture, dimmed so the controls read.
        if (item != null) ThumbnailFrame(rememberVideoThumbnail(item), Modifier.fillMaxSize(), RectangleShape)
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = POSTER_DIM)))

        // The top row: the title and where the picture is, the tools at the end.
        Row(Modifier.fillMaxWidth().padding(PAD_DP.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f).padding(end = PAD_DP.dp)) {
                if (item != null) {
                    Text(
                        rememberVideoTitle(item), color = Color.White, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        fontSize = TITLE_SP.sp, fontWeight = FontWeight.SemiBold,
                    )
                }
                Text(
                    stringResource(Res.string.video_pip_playing), color = Color.White.copy(alpha = STATUS_ALPHA), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, fontSize = STATUS_SP.sp,
                )
            }
            CaptionsButton(playback, state, settings, onInput = noInput, floatingPlain = false)
            CosmoIconButton(
                icon = VideoIcons.Info,
                contentDescription = stringResource(Res.string.video_stats),
                onClick = { statsShown = !statsShown },
                style = CosmoIconButtonStyle.GLASS,
                floating = true,
                size = VideoMetrics.CHROME_ICON_DP.dp,
                on = statsShown,
            )
            Box {
                CosmoIconButton(
                    icon = CosmoIcons.Settings,
                    contentDescription = stringResource(Res.string.video_player_settings),
                    onClick = { settingsOpen = true },
                    style = CosmoIconButtonStyle.GLASS,
                    floating = true,
                    size = VideoMetrics.CHROME_ICON_DP.dp,
                )
                if (settingsOpen) {
                    PlayerSettingsMenu(
                        playback = playback,
                        state = state,
                        settings = settings,
                        statsShown = statsShown,
                        onToggleStats = { statsShown = !statsShown },
                        onDismiss = { settingsOpen = false },
                    )
                }
            }
        }

        // The stats card, under the top row on the left as in the player.
        if (statsShown) PlayerStats(playback, state, playhead, metrics, Modifier.align(Alignment.TopStart))

        // The bottom: the transport, the scrubber, the time and Return.
        Column(Modifier.align(Alignment.BottomStart).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (!failed) {
                CentreControls(playback, state, PlayerLayout.WINDOW, metrics, settings.doubleTapSeconds, buffering, playhead, noInput, Modifier)
            }
            PlayerScrubber(
                playback = playback,
                state = state,
                settings = settings,
                playhead = playhead,
                lineAtBottom = false,
                onGlass = false,
                onScrub = { },
                onInput = noInput,
                modifier = Modifier.fillMaxWidth().padding(horizontal = VideoMetrics.SCRUB_FULL_SIDE_DP.dp).height(SCRUB_TOUCH_DP.dp),
            )
            Row(
                Modifier.fillMaxWidth().padding(start = BOTTOM_SIDE_DP.dp, end = BOTTOM_SIDE_DP.dp, bottom = PAD_DP.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (state.live) LiveStatus(playback, state, playhead, noInput) else TimeText(playhead, state.durationMs, onGlass = false, onInput = noInput)
                Box(Modifier.weight(1f))
                CosmoButton(stringResource(Res.string.video_pip_return), onReturn, kind = CosmoButtonKind.QUIET, size = CosmoButtonSize.SMALL, floating = true)
            }
        }
    }
}

private const val POSTER_DIM = 0.62f
private const val PAD_DP = 8f
private const val BOTTOM_SIDE_DP = 12f
private const val TITLE_SP = 15.5f
private const val STATUS_SP = 12.5f
private const val STATUS_ALPHA = 0.8f
