package com.cosmotune.video.ui.watch

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.WatchCardChip
import com.cosmotune.ui.orbit.clock
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.player.LiveWindow
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.VideoPlayback
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.resources.Res
import com.cosmotune.video.resources.video_badge_live
import com.cosmotune.video.resources.video_captions
import com.cosmotune.video.resources.video_download
import com.cosmotune.video.resources.video_go_live
import com.cosmotune.video.resources.video_next
import com.cosmotune.video.resources.video_player_settings
import com.cosmotune.video.resources.video_previous
import com.cosmotune.video.resources.video_seek_back
import com.cosmotune.video.resources.video_seek_forward
import com.cosmotune.video.resources.video_skip_segment
import com.cosmotune.video.resources.video_skipped
import com.cosmotune.video.resources.video_theatre
import com.cosmotune.video.resources.video_undo
import com.cosmotune.video.resources.video_watch_cinema
import com.cosmotune.video.resources.video_watch_cinema_detail
import com.cosmotune.video.resources.video_watch_fill
import com.cosmotune.video.resources.video_watch_fill_detail
import com.cosmotune.video.resources.video_watch_mini
import com.cosmotune.video.resources.video_watch_mini_detail
import com.cosmotune.video.resources.video_watch_paused
import com.cosmotune.video.resources.video_watch_playing
import com.cosmotune.video.resources.video_watch_screen
import com.cosmotune.video.resources.video_watch_theatre_detail
import com.cosmotune.video.resources.video_watch_whole
import com.cosmotune.video.resources.video_watch_whole_detail
import com.cosmotune.video.ui.VideoIcons
import com.cosmotune.video.ui.player.Playhead
import com.cosmotune.video.ui.player.PlayerScrubber
import com.cosmotune.video.ui.player.goToLive
import com.cosmotune.video.ui.player.showsGoLive
import com.cosmotune.video.ui.player.PlayerSettingsMenu
import com.cosmotune.video.ui.player.PlayerSettingsSheet
import com.cosmotune.video.ui.player.PlayerStatsSwitch
import com.cosmotune.video.ui.player.RunPlayhead
import com.cosmotune.video.ui.player.TickMode
import com.cosmotune.video.ui.player.preferredSubtitle
import com.cosmotune.video.ui.player.rememberPlayhead
import com.cosmotune.video.ui.player.segmentName
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/*
 * The watch page's colour card (Orbit board B, "Video watch page"; the approved Now Playing's song card): the video's own colour,
 * flat, under the whole video. The mono line ("PLAYING · 6:12 / 18:42 · 1080P"), the title in the wide face, the round buttons
 * (previous, next, captions, download, the screen button with Whole, Fill, Cinema, and the player's settings), the seek line with
 * SponsorBlock's segments on it as small marks and its gaps at the chapters, and play / pause in the bite. SponsorBlock's "Skip"
 * and "Skipped · Undo" are chips on the card, never on the video.
 */

/** The most lines the desktop card's title takes before it is cut. */
private const val WIDE_TITLE_LINES = 6

/** What the screen button offers and what it does: the page's view, Cinema, theatre (wide), picture-in-picture where there is one. */
internal class WatchScreen(
    val fill: Boolean,
    val onFill: (Boolean) -> Unit,
    val onCinema: () -> Unit,
    val theatre: Boolean?,
    val onTheatre: () -> Unit,
    val onMini: (() -> Unit)?,
)

/**
 * The card for the video [playback] plays, its colour from [thumbnail] ([key] picks the stand-in while it loads). [title] is the
 * title as shown (DeArrow's when on); a tap on it is [onTitle] (the phone's description). [onDownload] is the download button
 * (null hides it, as for a live stream). [wide] lays it out as the desktop's card (the title beside the buttons, the line across
 * the foot) rather than the phone's column.
 */
@Composable
internal fun WatchTransportCard(
    playback: VideoPlayback,
    title: String,
    thumbnail: String?,
    key: String,
    screen: WatchScreen,
    wide: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    onTitle: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null,
    downloaded: Boolean = false,
    cinema: Boolean = false,
    onInteraction: (Boolean) -> Unit = {},
) {
    val state by playback.state.collectAsState()
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    val (fill, ink) = coverFill(thumbnail, key)
    val playhead = rememberPlayhead(playback)
    val playing = state.playing && (state.status == PlayerStatus.READY || state.status == PlayerStatus.BUFFERING)
    RunPlayhead(playhead, if (playing) TickMode.FRAME else TickMode.SLOW)
    val ended = state.status == PlayerStatus.ENDED && state.item != null
    val onPlay = {
        val item = state.item
        if (ended && item != null) playback.play(item.id, startMs = 0L) else playback.togglePlayPause()
    }
    var settingsOpen by remember { mutableStateOf(false) }
    var screenOpen by remember { mutableStateOf(false) }
    var scrubbing by remember { mutableStateOf(false) }
    val interacting = settingsOpen || screenOpen || scrubbing
    LaunchedEffect(interacting) { onInteraction(interacting) }
    val buttons: @Composable () -> Unit = {
        WatchCardButtons(playback, state, ink, fill, screen, wide, onDownload, downloaded,
            screenOpen = screenOpen, onScreenOpen = { screenOpen = it }, onSettings = { settingsOpen = true }, settingsMenu = if (wide && settingsOpen) {
            {
                PlayerSettingsMenu(playback, state, settings, PlayerStatsSwitch.shown, onToggleStats = { PlayerStatsSwitch.shown = !PlayerStatsSwitch.shown }, onDismiss = { settingsOpen = false })
            }
        } else null)
    }
    val scrubber: @Composable (Modifier) -> Unit = { m ->
        PlayerScrubber(
            playback = playback, state = state, settings = settings, playhead = playhead, lineAtBottom = false, onGlass = false,
            onScrub = { scrubbing = it }, onInput = {}, modifier = m.fillMaxWidth().height(32.dp).testTag("video:watch:line"), ink = ink,
        )
    }
    val line: @Composable (Modifier) -> Unit = { m ->
        if (cinema && !state.live && state.durationMs > 0) {
            Row(m.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                OrbitRoundButton(CosmoIcons.Replay30, stringResource(Res.string.video_seek_back, 30), ink, { playback.seekBy(-30_000L) })
                scrubber(Modifier.weight(1f))
                OrbitRoundButton(CosmoIcons.Forward30, stringResource(Res.string.video_seek_forward, 30), ink, { playback.seekBy(30_000L) })
            }
        } else scrubber(m)
    }
    OrbitBiteCard(fill, modifier.fillMaxWidth().testTag("video:watch:card"), bite = { OrbitPlayButton(playing, onPlay, look, Modifier.testTag("video:watch:play")) }) {
        if (wide) {
            Column(Modifier.fillMaxWidth().padding(start = 24.dp, top = 20.dp, end = 24.dp, bottom = 12.dp)) {
                // The mono line and the whole title take the card's width (the title wraps, as long as it is); the buttons stand under them.
                WatchCardLabel(state, playhead.second.longValue, fonts, ink)
                WatchCardTitle(title, fonts, ink, 22, WIDE_TITLE_LINES, onTitle, Modifier.padding(top = 8.dp))
                Box(Modifier.padding(top = 12.dp, end = 70.dp)) { buttons() }
                WatchSegmentChips(playback, state, playhead, ink, fill, fonts, Modifier.padding(top = 10.dp))
                line(Modifier.padding(top = 6.dp, end = 70.dp))
            }
        } else {
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, top = 20.dp, end = 20.dp, bottom = 18.dp)) {
                WatchCardLabel(state, playhead.second.longValue, fonts, ink)
                WatchCardTitle(title, fonts, ink, 21, 2, onTitle, Modifier.padding(top = 10.dp))
                WatchSegmentChips(playback, state, playhead, ink, fill, fonts, Modifier.padding(top = 10.dp))
                line(Modifier.padding(top = 8.dp, end = 74.dp))
                Box(Modifier.padding(top = 6.dp, end = 70.dp)) { buttons() }
            }
        }
    }
    if (!wide && settingsOpen) {
        PlayerSettingsSheet(playback, state, settings, PlayerStatsSwitch.shown, onToggleStats = { PlayerStatsSwitch.shown = !PlayerStatsSwitch.shown }, onDismiss = { settingsOpen = false })
    }
}

/** "PLAYING · 6:12 / 18:42 · 1080P" (PAUSED; "● LIVE" for a stream): the only words that read the playhead's second. */
@Composable
private fun WatchCardLabel(state: PlaybackUiState, second: Long, fonts: OrbitFonts, ink: Color) {
    val head = when {
        state.live -> "● " + stringResource(Res.string.video_badge_live)
        state.playing -> stringResource(Res.string.video_watch_playing)
        else -> stringResource(Res.string.video_watch_paused)
    }
    val duration = if (state.live) LiveWindow.liveEdgeMs(state.durationMs) else state.durationMs
    val time = if (!state.live && duration > 0) "${clock(second * 1000)} / ${clock(duration)}" else null
    val text = listOfNotNull(head, time, state.quality?.label).joinToString(" · ").uppercase()
    Text(text, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun WatchCardTitle(title: String, fonts: OrbitFonts, ink: Color, sizeSp: Int, lines: Int, onTitle: (() -> Unit)?, modifier: Modifier) {
    Text(
        title, style = fonts.heroTitle.copy(fontSize = sizeSp.sp, lineHeight = (sizeSp + 3).sp), color = ink, maxLines = lines, overflow = TextOverflow.Ellipsis,
        modifier = modifier.semantics { heading() }.clip(RoundedCornerShape(10.dp))
            .then(if (onTitle != null) Modifier.clickable(role = Role.Button, onClick = onTitle) else Modifier),
    )
}

/**
 * The card's round buttons: previous and next (next only with a queue), captions (on while shown, faded with none), download (the
 * card's [onDownload], solid once [downloaded]), the screen button and its menu, and the player's settings ([onSettings]; on the
 * desktop its menu, [settingsMenu], opens under it). The phone leaves the download to the actions under the card.
 */
@Composable
private fun WatchCardButtons(
    playback: VideoPlayback,
    state: PlaybackUiState,
    ink: Color,
    fill: Color,
    screen: WatchScreen,
    wide: Boolean,
    onDownload: (() -> Unit)?,
    downloaded: Boolean,
    screenOpen: Boolean,
    onScreenOpen: (Boolean) -> Unit,
    onSettings: () -> Unit,
    settingsMenu: (@Composable () -> Unit)?,
) {
    val settings by LocalVideoGraph.current.settings.settings.collectAsState()
    val fonts = com.cosmotune.ui.orbit.orbitFonts()
    val look = com.cosmotune.ui.orbit.orbitLook()
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
        OrbitRoundButton(CosmoIcons.Previous, stringResource(Res.string.video_previous), ink, { playback.playPrevious() }, onInk = fill)
        val next = state.queue.isNotEmpty() || state.autoplay
        OrbitRoundButton(CosmoIcons.Next, stringResource(Res.string.video_next), if (next) ink else ink.copy(alpha = 0.4f), { if (next) playback.playNext() }, onInk = fill)
        val captionsOn = state.subtitleLanguage != null
        val hasCaptions = state.subtitles.isNotEmpty()
        OrbitRoundButton(
            VideoIcons.Captions, stringResource(Res.string.video_captions), if (hasCaptions) ink else ink.copy(alpha = 0.4f),
            { if (hasCaptions) playback.selectSubtitles(if (captionsOn) null else preferredSubtitle(state, settings)) }, on = captionsOn, onInk = fill,
        )
        if (wide && onDownload != null) {
            OrbitRoundButton(if (downloaded) CosmoIcons.Downloaded else CosmoIcons.Download, stringResource(Res.string.video_download), ink, onDownload, on = downloaded, onInk = fill)
        }
        Box {
            OrbitRoundButton(CosmoIcons.Canvas, stringResource(Res.string.video_watch_screen), ink, { onScreenOpen(true) }, onInk = fill, modifier = Modifier.testTag("video:watch:screen"))
            OrbitMenu(
                open = screenOpen, onDismiss = { onScreenOpen(false) }, fonts = fonts, look = look, header = stringResource(Res.string.video_watch_screen),
                items = buildList {
                    add(OrbitMenuItem(stringResource(Res.string.video_watch_whole), stringResource(Res.string.video_watch_whole_detail), selected = !screen.fill && screen.theatre != true) {
                        screen.onFill(false); if (screen.theatre == true) screen.onTheatre()
                    })
                    add(OrbitMenuItem(stringResource(Res.string.video_watch_fill), stringResource(Res.string.video_watch_fill_detail), selected = screen.fill && screen.theatre != true) {
                        screen.onFill(true); if (screen.theatre == true) screen.onTheatre()
                    })
                    if (screen.theatre != null) {
                        add(OrbitMenuItem(stringResource(Res.string.video_theatre), stringResource(Res.string.video_watch_theatre_detail), selected = screen.theatre) { screen.onTheatre() })
                    }
                    add(OrbitMenuItem(stringResource(Res.string.video_watch_cinema), stringResource(Res.string.video_watch_cinema_detail)) { screen.onCinema() })
                    val mini = screen.onMini
                    add(OrbitMenuItem(stringResource(Res.string.video_watch_mini), stringResource(Res.string.video_watch_mini_detail), enabled = mini != null) { mini?.invoke() })
                },
            )
        }
        Box {
            OrbitRoundButton(CosmoIcons.Settings, stringResource(Res.string.video_player_settings), ink, onSettings, onInk = fill)
            settingsMenu?.invoke()
        }
    }
}

/**
 * The card's chips: "Go to live" (solid, while a live stream plays more than 10 s behind its live edge; the overlay's pill before
 * the controls moved to the card), and SponsorBlock's "Skip sponsor" (solid, while a segment waits to be skipped) and "Skipped
 * sponsor · Undo" (for the controller's last skip, a few seconds), in the card's ink. Nothing when none is there.
 */
@Composable
private fun WatchSegmentChips(playback: VideoPlayback, state: PlaybackUiState, playhead: Playhead, ink: Color, fill: Color, fonts: OrbitFonts, modifier: Modifier) {
    var skipped by remember { mutableStateOf(state.lastSkip) }
    LaunchedEffect(state.lastSkip?.uuid) {
        skipped = state.lastSkip
        if (state.lastSkip != null) {
            delay(VideoPlayerController.LAST_SKIP_MS)
            skipped = null
        }
    }
    val pending = state.pendingSkip
    val last = skipped
    val duration = state.durationMs
    val behind by remember(playhead, duration, state.live) { derivedStateOf { state.live && showsGoLive(playhead.second.longValue * 1000, duration) } }
    if (pending == null && last == null && !behind) return
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (behind) {
            WatchCardChip(stringResource(Res.string.video_go_live), ink, fonts, { goToLive(playback, state); playhead.refresh() }, Modifier.testTag("video:watch:go-live"),
                icon = VideoIcons.Live, solid = true, onSolid = fill)
        }
        if (pending != null) {
            val text = stringResource(Res.string.video_skip_segment, stringResource(segmentName(pending.category)))
            WatchCardChip(text, ink, fonts, { playback.skipPending() }, Modifier.testTag("video:watch:skip"), icon = VideoIcons.SkipSegment, solid = true, onSolid = fill)
        }
        if (last != null) {
            val text = stringResource(Res.string.video_skipped, stringResource(segmentName(last.category))) + " · " + stringResource(Res.string.video_undo)
            WatchCardChip(text, ink, fonts, { skipped = null; playback.undoSkip() })
        }
    }
}
