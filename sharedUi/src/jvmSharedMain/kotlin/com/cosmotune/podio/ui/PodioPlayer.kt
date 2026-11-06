package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.orbit.*
import com.cosmotune.ui.room.RoomPlayButton

/** What the players need besides the playback: the timer and its controls (null: the host has no timer). */
internal class PodioTimer(val minutes: Int?, val afterEpisode: Boolean, val onTimer: ((Int?) -> Unit)?, val onAfterEpisode: (() -> Unit)?)

/** The disc of what is on: the station's logo disc, or the episode's (or show's) cover; the orbit round it in [ring]. */
@Composable private fun NowDisc(station: RadioStation?, artUrl: String?, size: Dp, ring: Color, playback: PodioPlayback, fonts: OrbitFonts, outer: Dp = size + 12.dp) {
    val live = playback.id?.startsWith("podio:radio:") == true
    Box(Modifier.size(outer), contentAlignment = Alignment.Center) {
        if (station != null) OrbitLogoDisc(station.artwork, station.name, size, fonts) else Disc(artUrl?.takeIf(String::isNotBlank), size)
        val progress: (() -> Float?)? = if (live || playback.durationMs <= 0) null else ({ (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) })
        OrbitRing(outer, ring, progress = progress, spinning = playback.playing, stroke = if (outer > 120.dp) 2.dp else 1.5.dp, moon = if (outer > 120.dp) 5.dp else 3.dp)
    }
}

/**
 * Podio's mini player in the dock (the shared dock's `miniPlayer`, above the tabs): the disc wearing the orbit, the title over the
 * station's song on air ("● LIVE") or the episode's show, and play. A tap opens the station page or the episode's player.
 */
@Composable internal fun PodioMiniPlayer(playback: PodioPlayback, station: RadioStation?, onAir: String?, artUrl: String?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val live = playback.id?.startsWith("podio:radio:") == true
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("podio:mini").padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).clip(RoundedCornerShape(22.dp))
            .clickable(role = Role.Button, onClickLabel = if (live) "Open the station page" else "Open the player", onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NowDisc(station, artUrl, 44.dp, look.accent, playback, fonts)
            Column(Modifier.weight(1f)) {
                Text(playback.title, style = fonts.title, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                when {
                    playback.error != null -> Text("Playback failed", style = fonts.body, color = look.accent, maxLines = 1)
                    live -> OrbitLiveLabel(onAir ?: station?.placeLine().orEmpty(), look.dim, liveRed(look), fonts)
                    playback.author.isNotBlank() -> Text(playback.author, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        OrbitPlayButton(playback.playing, onToggle, look, size = 48.dp)
    }
}

/** The sleep timer as a chip with its menu: 15 to 60 minutes, the end of the episode, off. On a colour card when [card] is given. */
@Composable internal fun SleepTimerChip(timer: PodioTimer, episode: Boolean, card: OrbitCardColors? = null) {
    val onTimer = timer.onTimer ?: return
    val fonts = orbitFonts()
    val look = orbitLook()
    var open by remember { mutableStateOf(false) }
    val active = timer.afterEpisode || timer.minutes != null
    val text = when { timer.afterEpisode -> "After episode"; timer.minutes != null -> "${timer.minutes} min"; else -> "Sleep timer" }
    Box {
        if (card != null) OrbitCardChip(text, card, { open = true }, icon = Icons.Outlined.Bedtime, solid = active)
        else OrbitSelectChip(text, active, fonts, look, { open = true }, icon = Icons.Outlined.Bedtime, haptic = false)
        val items = listOf(15, 30, 45, 60).map { minutes -> OrbitMenuItem("$minutes minutes", selected = timer.minutes == minutes && !timer.afterEpisode) { onTimer(minutes) } } +
            listOfNotNull(timer.onAfterEpisode?.takeIf { episode }?.let { after -> OrbitMenuItem("End of episode", selected = timer.afterEpisode) { after() } }) +
            OrbitMenuItem("Turn off timer", enabled = active) { onTimer(null) }
        OrbitMenu(open, { open = false }, items, fonts, look, header = "Sleep timer", width = 220.dp)
    }
}

/** The speed of an episode as a chip: 1×, 1.25×, 1.5×, 2× in turn. On a colour card when [card] is given. */
@Composable internal fun SpeedChip(playback: PodioPlayback, onSpeed: (Float) -> Unit, card: OrbitCardColors? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var speed by remember(playback.id) { mutableFloatStateOf(playback.rate) }
    LaunchedEffect(playback.rate) { speed = playback.rate }
    val next = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }; onSpeed(speed) }
    if (card != null) OrbitCardChip("${speed}×", card, next, icon = Icons.Outlined.Speed, solid = speed != 1f)
    else OrbitSelectChip("${speed}×", speed != 1f, fonts, look, next, icon = Icons.Outlined.Speed, haptic = false)
}

/** Back 15 seconds and forward 30, as the card's or the page's round buttons. */
@Composable private fun RowScope.Skips(playback: PodioPlayback, onSeek: (Long) -> Unit, ink: Color, onInk: Color, size: Dp = 40.dp) {
    OrbitRoundButton(Icons.Outlined.Replay, "Back 15 seconds", ink, { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, size = size, onInk = onInk)
    OrbitRoundButton(Icons.Outlined.Forward30, "Forward 30 seconds", ink, {
        onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
    }, size = size, onInk = onInk)
}

/** The seek line of an episode with its two times under it, in [ink]. */
@Composable private fun EpisodeLine(playback: PodioPlayback, onSeek: (Long) -> Unit, ink: Color, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    if (playback.durationMs <= 0) return
    Column(modifier) {
        OrbitProgressLine({ (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f) }, ink, Modifier.fillMaxWidth(),
            onSeek = { onSeek((it * playback.durationMs).toLong()) })
        Row(Modifier.fillMaxWidth()) {
            Text(PodioFormat.clock(playback.positionMs), style = fonts.label, color = ink.copy(alpha = 0.7f))
            Spacer(Modifier.weight(1f))
            Text(PodioFormat.clock(playback.durationMs), style = fonts.label, color = ink.copy(alpha = 0.7f))
        }
    }
}

/**
 * The player bar of the wide layouts (tablet and desktop) in Orbit, docked along the foot of the page: the colour card made slim
 * in what plays' own colour, the disc wearing the orbit, the title over the show (or "● LIVE"), back 15 and forward 30, the seek
 * line with its times, the speed and the sleep timer, and play standing in the bite cut out of its end. A live station has no line,
 * speed or skips.
 */
@Composable internal fun PodioPlayerBar(playback: PodioPlayback, station: RadioStation?, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit, timer: PodioTimer, onOpen: (() -> Unit)?) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val live = playback.id?.startsWith("podio:radio:") == true
    val colors = if (station != null) stationCardColors(station) else coverFill(artUrl?.takeIf(String::isNotBlank), playback.title).let { OrbitCardColors(it.first, it.second) }
    val ink = colors.ink
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).height(OrbitBarMetrics.HEIGHT_DP.dp).testTag("podio:player")) {
        val roomy = maxWidth >= 900.dp
        Box(Modifier.fillMaxSize().clip(OrbitBarMetrics.shape).background(colors.fill)) {
            Row(Modifier.fillMaxSize().padding(start = OrbitBarMetrics.PAD_START_DP.dp, end = OrbitBarMetrics.END_PAD_DP.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(Modifier.weight(1f).clip(CircleShape).then(if (onOpen != null) Modifier.clickable(role = Role.Button, onClickLabel = "Open the station page", onClick = onOpen) else Modifier),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    NowDisc(station, artUrl, OrbitBarMetrics.DISC_DP.dp, ink, playback, fonts, outer = OrbitBarMetrics.RING_DP.dp)
                    Column(Modifier.weight(1f)) {
                        Text(playback.title, style = fonts.title, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        when {
                            playback.error != null -> Text("Playback failed. Try another station or refresh this feed.", style = fonts.body, color = LIVE_ON_CARD, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            live -> OrbitLiveLabel(playback.author, ink.copy(alpha = 0.75f), LIVE_ON_CARD, fonts)
                            else -> Text(playback.author, style = fonts.body, color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
                if (!live) {
                    Skips(playback, onSeek, ink, colors.fill, size = 36.dp)
                    EpisodeLine(playback, onSeek, ink, fonts, Modifier.width(if (roomy) 260.dp else 160.dp))
                    SpeedChip(playback, onSpeed, colors)
                }
                SleepTimerChip(timer, episode = !live, card = colors)
            }
        }
        Box(Modifier.align(Alignment.CenterEnd).padding(end = (OrbitBarMetrics.BITE_INSET_DP - OrbitBarMetrics.PLAY_DP / 2).dp)) {
            OrbitPlayButton(playback.playing, onToggle, look, size = OrbitBarMetrics.PLAY_DP.dp)
        }
    }
}

/**
 * The phone's episode player, a sheet over the page: the cover as a big disc wearing the orbit of the episode, the title and the
 * show, the seek line with its times, back 15, play and forward 30, then the speed and the sleep timer, and the room.
 */
@Composable internal fun EpisodePlayerSheet(playback: PodioPlayback, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    timer: PodioTimer, roomItem: PartyTrack? = null, onDismiss: () -> Unit) {
    val fonts = orbitFonts()
    val look = orbitLook()
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 24.dp).testTag("podio:episode-player"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
            NowDisc(null, artUrl, 232.dp, look.accent, playback, fonts, outer = 262.dp)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(playback.title, style = fonts.heroTitle.copy(fontSize = 22.sp, lineHeight = 26.sp), color = look.ink, textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(playback.author, style = fonts.body.copy(fontSize = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            EpisodeLine(playback, onSeek, look.ink, fonts, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                OrbitRoundButton(Icons.Outlined.Replay, "Back 15 seconds", look.ink, { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, size = 52.dp, onInk = look.ground)
                OrbitPlayButton(playback.playing, onToggle, look, size = 72.dp)
                OrbitRoundButton(Icons.Outlined.Forward30, "Forward 30 seconds", look.ink, {
                    onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
                }, size = 52.dp, onInk = look.ground)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeedChip(playback, onSpeed)
                SleepTimerChip(timer, episode = true)
            }
            // [roomItem] is the episode as the room would take it; nothing is drawn where rooms are not offered.
            RoomPlayButton(roomItem, dense = true)
            if (playback.error != null) Text("Playback failed. Refresh this feed or try another episode.", color = look.accent, style = fonts.body)
        }
    }
}
