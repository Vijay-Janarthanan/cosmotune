package com.cosmotune.podio.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bedtime
import androidx.compose.material.icons.outlined.Forward30
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Replay
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.TypeScale
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.room.RoomPlayButton
import com.cosmotune.ui.room.roomItem
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSlider
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.tabular

/** What the players need besides the playback: the timer and its controls (null: the host has no timer). */
internal class PodioTimer(val minutes: Int?, val afterEpisode: Boolean, val onTimer: ((Int?) -> Unit)?, val onAfterEpisode: (() -> Unit)?)

/** The art of what is on: the station's logo tile, or the episode's (or show's) art. */
@Composable private fun NowArt(station: RadioStation?, artUrl: String?, size: androidx.compose.ui.unit.Dp) {
    if (station != null) StationLogo(station, size) else KitArtwork(artUrl?.takeIf(String::isNotBlank), KitArt.SQUARE, size)
}

/**
 * Podio's mini player in the dock (the shared dock's `miniPlayer`, above the tabs): the art, the title over the station's song on
 * air or the episode's show, and the play button; for an episode a 2 dp accent line runs along its foot, as Music's docked player.
 * A tap opens the station page or the episode's player.
 */
@Composable internal fun PodioMiniPlayer(playback: PodioPlayback, station: RadioStation?, onAir: String?, artUrl: String?, onToggle: () -> Unit, onOpen: () -> Unit) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val live = playback.id?.startsWith("podio:radio:") == true
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("podio:mini")
        .drawBehind {
            // DESIGN_SYSTEM.md 4.16: a 2 dp accent line on a hairline track along the foot; the row itself stays neutral.
            if (live || playback.durationMs <= 0) return@drawBehind
            val fraction = (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
            val thickness = 2.dp.toPx()
            drawRect(Color(palette.textPrimary).copy(alpha = 0.08f), Offset(0f, size.height - thickness), Size(size.width, thickness))
            drawRect(Color(palette.accent), Offset(0f, size.height - thickness), Size(size.width * fraction, thickness))
        }.padding(horizontal = 8.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f).heightIn(min = 48.dp).clip(RoundedCornerShape(CosmoRadii.rMd))
            .clickable(role = Role.Button, onClickLabel = if (live) "Open the station page" else "Open the player", onClick = onOpen),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            NowArt(station, artUrl, 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(playback.title, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (live) LiveMark(label = onAir == null)
                    val line = when { playback.error != null -> "Playback failed"; live -> onAir ?: station?.placeLine().orEmpty(); else -> playback.author }
                    if (line.isNotBlank()) Text(line, style = type.meta, color = Color(if (playback.error != null) palette.danger else palette.textSecondary),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        PodioPlayButton(playback.playing, playback.loading, live, onToggle, size = 48.dp, filled = false)
    }
}

/** The sleep timer as a pill with its menu: 15 to 60 minutes, the end of the episode, off. */
@Composable internal fun SleepTimerPill(timer: PodioTimer, episode: Boolean) {
    val onTimer = timer.onTimer ?: return
    var open by remember { mutableStateOf(false) }
    Box {
        ToolPill(when { timer.afterEpisode -> "After episode"; timer.minutes != null -> "${timer.minutes} min"; else -> "Sleep timer" }, Icons.Outlined.Bedtime,
            onClick = { open = true }, active = timer.afterEpisode || timer.minutes != null)
        DropdownMenu(open, onDismissRequest = { open = false }) {
            listOf(15, 30, 45, 60).forEach { minutes -> DropdownMenuItem(text = { Text("$minutes minutes") }, onClick = { open = false; onTimer(minutes) }) }
            if (episode && timer.onAfterEpisode != null) DropdownMenuItem(text = { Text("End of episode") }, onClick = { open = false; timer.onAfterEpisode.invoke() })
            DropdownMenuItem(text = { Text("Turn off timer") }, onClick = { open = false; onTimer(null) })
        }
    }
}

/** The speed pill of an episode: 1×, 1.25×, 1.5×, 2× in turn. */
@Composable internal fun SpeedPill(playback: PodioPlayback, onSpeed: (Float) -> Unit) {
    var speed by remember(playback.id) { mutableFloatStateOf(playback.rate) }
    LaunchedEffect(playback.rate) { speed = playback.rate }
    ToolPill("${speed}×", Icons.Outlined.Speed, onClick = { speed = when (speed) { 1f -> 1.25f; 1.25f -> 1.5f; 1.5f -> 2f; else -> 1f }; onSpeed(speed) }, active = speed != 1f)
}

/** The scrubber of an episode with the two times under it; it moves the playhead when the finger lifts. */
@Composable internal fun EpisodeScrubber(playback: PodioPlayback, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    if (playback.durationMs <= 0) return
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var scrubbing by remember(playback.id) { mutableFloatStateOf(-1f) }
    val shown = if (scrubbing >= 0) scrubbing else (playback.positionMs.toFloat() / playback.durationMs).coerceIn(0f, 1f)
    Column(modifier) {
        CosmoSlider(shown, { scrubbing = it }, Modifier.fillMaxWidth(), onValueChangeFinished = {
            if (scrubbing >= 0) onSeek((scrubbing * playback.durationMs).toLong())
            scrubbing = -1f
        }, contentDescription = "Episode position")
        Row(Modifier.fillMaxWidth()) {
            Text(PodioFormat.clock((shown * playback.durationMs).toLong()), style = type.caption.tabular(), color = Color(palette.textSecondary))
            Spacer(Modifier.weight(1f))
            Text(PodioFormat.clock(playback.durationMs), style = type.caption.tabular(), color = Color(palette.textSecondary))
        }
    }
}

/**
 * The player bar of the wide layouts (tablet and desktop), docked along the foot of the page: art, title and show, back 15 seconds,
 * play, forward 30 seconds, the speed and the sleep timer, and the scrubber with its times. A live station has no scrubber, speed or
 * skips: only play/stop and the LIVE mark.
 */
@Composable internal fun PodioPlayerBar(playback: PodioPlayback, station: RadioStation?, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit,
    onSpeed: (Float) -> Unit, timer: PodioTimer, onOpen: (() -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val live = playback.id?.startsWith("podio:radio:") == true
    // Docked along the foot (DESIGN_SYSTEM.md 4.17): surface1 with a hairline on top, no corners, no float.
    Column(Modifier.fillMaxWidth().background(Color(palette.surface)).drawBehind {
        drawRect(Color(palette.textPrimary).copy(alpha = 0.08f), Offset.Zero, Size(size.width, 1.dp.toPx()))
    }.padding(horizontal = 16.dp, vertical = 10.dp).testTag("podio:player")) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.weight(1f).then(if (onOpen != null) Modifier.clip(RoundedCornerShape(CosmoRadii.rMd)).clickable(role = Role.Button, onClickLabel = "Open the station page", onClick = onOpen) else Modifier),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                NowArt(station, artUrl, 48.dp)
                Column(Modifier.weight(1f)) {
                    Text(playback.title, style = type.rowTitle, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (live) LiveMark()
                        Text(playback.author, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
            if (!live) CosmoIconButton(Icons.Outlined.Replay, "Back 15 seconds", { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
            PodioPlayButton(playback.playing, playback.loading, live, onToggle, size = 48.dp)
            if (!live) CosmoIconButton(Icons.Outlined.Forward30, "Forward 30 seconds", {
                onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
            }, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
            if (!live) SpeedPill(playback, onSpeed)
            SleepTimerPill(timer, episode = !live)
        }
        if (!live) EpisodeScrubber(playback, onSeek, Modifier.fillMaxWidth().padding(top = 4.dp))
        if (playback.error != null) Text("Playback failed. Try another station or refresh this feed.", color = Color(palette.danger), style = type.meta)
    }
}

/**
 * The phone's episode player, a sheet over the page (Prism's and AntennaPod's): large art, the title light, the show, the scrubber
 * with its times, back 15, the big play, forward 30, then the speed and the sleep timer.
 */
@Composable internal fun EpisodePlayerSheet(playback: PodioPlayback, artUrl: String?, onToggle: () -> Unit, onSeek: (Long) -> Unit, onSpeed: (Float) -> Unit,
    timer: PodioTimer, roomItem: PartyTrack? = null, onDismiss: () -> Unit) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    KitSheet(onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = CosmoSpacing.margin).padding(bottom = 24.dp).testTag("podio:episode-player"),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            KitArtwork(artUrl?.takeIf(String::isNotBlank), KitArt.SQUARE, 240.dp, radius = CosmoRadii.rMd)
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(playback.title, style = type.forText(type.playerTitle, playback.title), color = Color(palette.textPrimary), textAlign = TextAlign.Center, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Text(playback.author, style = type.bodyText, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            EpisodeScrubber(playback, onSeek, Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                CosmoIconButton(Icons.Outlined.Replay, "Back 15 seconds", { onSeek((playback.positionMs - 15_000).coerceAtLeast(0)) }, style = CosmoIconButtonStyle.PLAIN, size = 56.dp)
                PodioPlayButton(playback.playing, playback.loading, live = false, onClick = onToggle, size = 72.dp)
                CosmoIconButton(Icons.Outlined.Forward30, "Forward 30 seconds", {
                    onSeek((playback.positionMs + 30_000).let { if (playback.durationMs > 0) it.coerceAtMost(playback.durationMs) else it })
                }, style = CosmoIconButtonStyle.PLAIN, size = 56.dp)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                SpeedPill(playback, onSpeed)
                SleepTimerPill(timer, episode = true)
            }
            // [roomItem] is the episode as the room would take it; nothing is drawn where rooms are not offered.
            RoomPlayButton(roomItem, dense = true)
            if (playback.error != null) Text("Playback failed. Refresh this feed or try another episode.", color = Color(palette.danger), style = type.bodyText)
        }
    }
}

/**
 * The station that is on, at the foot of the wide layouts' station list: the logo, LIVE, the name and place, the song on air when
 * the station sends it, previous, play/stop, next, the heart and (desktop) the volume. A tap on the top opens the station page.
 */
@Composable internal fun LiveCard(station: RadioStation, playback: PodioPlayback, song: String?, saved: Boolean, onToggle: () -> Unit, onSave: () -> Unit,
    volume: Float, onVolume: ((Float) -> Unit)?, onOpen: () -> Unit, onPrevious: (() -> Unit)?, onNext: (() -> Unit)?, timer: PodioTimer) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).clip(RoundedCornerShape(CosmoRadii.rLg)).background(Color(palette.surface))
        .padding(14.dp).testTag("podio:live"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.clip(RoundedCornerShape(CosmoRadii.rMd)).clickable(role = Role.Button, onClickLabel = "Open the station page", onClick = onOpen),
            horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            StationLogo(station, 56.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                LiveMark()
                Text(station.name, style = type.titleMedium, color = Color(palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(station.placeLine().ifBlank { station.language }, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            HeartButton(saved, onSave)
        }
        if (song != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.GraphicEq, null, Modifier.size(18.dp), tint = Color(palette.accent))
            Text(song, style = type.bodyText, color = Color(palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            CosmoIconButton(CosmoIcons.Previous, "Previous station", { onPrevious?.invoke() }, style = CosmoIconButtonStyle.PLAIN, size = 48.dp, enabled = onPrevious != null)
            PodioPlayButton(playback.playing, playback.loading, live = true, onClick = onToggle, size = 52.dp)
            CosmoIconButton(CosmoIcons.Next, "Next station", { onNext?.invoke() }, style = CosmoIconButtonStyle.PLAIN, size = 48.dp, enabled = onNext != null)
        }
        onVolume?.let { change -> Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(CosmoIcons.Volume, null, Modifier.size(18.dp), tint = Color(palette.textSecondary))
            CosmoSlider(volume, change, Modifier.weight(1f), contentDescription = "Volume", compact = true)
        } }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
            SleepTimerPill(timer, episode = false)
            RoomPlayButton(remember(station) { station.roomItem() }, dense = true)
        }
        if (playback.error != null) Text("Playback failed. Try another station.", color = Color(palette.danger), style = type.meta)
    }
}
