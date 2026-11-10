package com.cosmotune.podio.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.unit.dp
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.item
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitFonts
import com.cosmotune.ui.orbit.OrbitHeardDisc
import com.cosmotune.ui.orbit.OrbitLogoDisc
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitMenu
import com.cosmotune.ui.orbit.OrbitMenuItem
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitSongRow
import com.cosmotune.ui.orbit.OrbitStationRecord
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/*
 * Podio's own pieces, built of the Orbit kit: a station as a song row (its logo disc, the heart at its end), a station as a record in
 * a grid with its menu on a hold, an episode as a song row whose disc carries how much is heard, the heart, and the sheet that stays
 * over the full-screen globe.
 */

/** The heart of a station: a round button, solid ink when [saved]. */
@Composable internal fun HeartButton(saved: Boolean, onClick: () -> Unit, look: OrbitLook, ink: Color = look.ink, onInk: Color = look.ground, size: Dp = 40.dp) {
    OrbitRoundButton(if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart, if (saved) "Remove favourite" else "Save favourite", ink, onClick, size = size, on = saved, onInk = onInk)
}

/** A station's logo disc in a row's disc slot, wearing the orbit while it is the one on air. */
@Composable internal fun StationRowDisc(station: RadioStation, onAir: Boolean, playing: Boolean, fonts: OrbitFonts, look: OrbitLook, size: Dp = 44.dp) {
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        OrbitLogoDisc(station.artwork, station.name, size, fonts)
        if (onAir) OrbitRing(size + 8.dp, look.accent, progress = null, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
    }
}

/**
 * A station in a list (the search, the stations under the globe's ring): the song row with the logo disc, the name over the place,
 * language and stream, or over what is on air while it plays ([onAir]); the heart at the end. A tap plays it; the one that is on
 * is the peach row.
 */
@Composable internal fun StationRow(station: RadioStation, saved: Boolean, current: Boolean, playing: Boolean, onPlay: () -> Unit, onSave: () -> Unit,
    fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, onAir: String? = null, showHeart: Boolean = true) {
    val line = if (current) onAir?.let { "On air: $it" } ?: "On air now"
        else PodioFormat.stationMeta(station.placeLine(), station.language, station.codec, station.bitrate)
    OrbitSongRow(station.name, line, null, fonts, look, onPlay, modifier.fillMaxWidth(), now = current, playing = playing, endPadding = if (showHeart) 8.dp else 16.dp,
        discContent = { StationRowDisc(station, current, playing, fonts, look) },
        trailing = if (showHeart) ({ HeartButton(saved, onSave, look) }) else null)
}

/**
 * The stations as a grid of records (board section C, "Stations"): [columns] to a row, each [cell] wide; the one on air wears the
 * orbit. A hold (or a right click) opens the station's menu: save or remove it, play it.
 */
internal fun LazyListScope.stationGrid(key: String, stations: List<RadioStation>, columns: Int, cell: Dp, side: Dp, env: PodioEnv, m: PodioModel,
    fonts: OrbitFonts, look: OrbitLook) {
    val rows = stations.chunked(columns.coerceAtLeast(1))
    items(rows.size, key = { "$key-" + rows[it].first().id }) { index ->
        Row(Modifier.fillMaxWidth().padding(horizontal = side).padding(top = if (index == 0) 4.dp else 14.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            rows[index].forEach { station -> StationTile(station, env, m, fonts, look, cell) }
            repeat(columns - rows[index].size) { Spacer(Modifier.width(cell + 12.dp)) }
        }
    }
}

/** One station of a grid or a shelf: its record, and its menu on a hold. */
@Composable internal fun StationTile(station: RadioStation, env: PodioEnv, m: PodioModel, fonts: OrbitFonts, look: OrbitLook, size: Dp) {
    var menu by remember { mutableStateOf(false) }
    val onAir = env.playback.id == station.item().id
    val saved = env.saved.stations.any { it.id == station.id }
    Box {
        OrbitStationRecord(station.name, if (onAir) "On air" else station.shortPlace(), station.artwork, fonts, look, onClick = { m.playStation(station) },
            onAir = onAir, playing = onAir && env.playback.playing, onLongClick = { menu = true }, size = size)
        OrbitMenu(menu, { menu = false }, listOf(
            OrbitMenuItem(if (onAir) "Playing" else "Play", icon = CosmoIcons.Play, enabled = !onAir) { m.playStation(station) },
            OrbitMenuItem(if (saved) "Remove from your stations" else "Save to your stations", icon = if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart) { m.toggleFavourite(station) },
        ), fonts, look, header = station.name)
    }
}

/** What a download button shows for an episode. */
internal enum class DownloadMark { NONE, AVAILABLE, RUNNING, DONE }

/**
 * An episode as the song row (board section C, "show page = the album page"): the cover as a disc with how much is [heard] as its
 * faint orbit, the title, "date · length · 18 min left" under it, and the download and details buttons at the end. The one that is
 * on ([now]) is the peach row, its orbit moving with [progress]. A tap plays it (or resumes); a hold or [onMore] opens its sheet.
 */
@Composable internal fun EpisodeRow(title: String, meta: String, artUrl: String?, heard: Float, now: Boolean, playing: Boolean, progress: (() -> Float?)?,
    download: DownloadMark, onPlay: () -> Unit, onDownload: () -> Unit, onMore: (() -> Unit)?, fonts: OrbitFonts, look: OrbitLook,
    modifier: Modifier = Modifier, moreLabel: String = "Episode details") {
    val art = artUrl?.takeIf(String::isNotBlank)
    OrbitSongRow(title, meta, art, fonts, look, onPlay, modifier.fillMaxWidth(), now = now, playing = playing, progress = progress, onLongClick = onMore, endPadding = 6.dp,
        discContent = if (now) null else ({ OrbitHeardDisc(art, 44.dp, heard, look) }),
        trailing = {
            when (download) {
                DownloadMark.NONE -> {}
                DownloadMark.AVAILABLE -> OrbitRoundButton(CosmoIcons.Download, "Download episode", look.ink, onDownload, size = 36.dp)
                DownloadMark.RUNNING -> OrbitRoundButton(CosmoIcons.Close, "Cancel episode download", look.ink, onDownload, size = 36.dp)
                DownloadMark.DONE -> Box(Modifier.size(36.dp).semantics { contentDescription = "Episode downloaded"; disabled() }, contentAlignment = Alignment.Center) {
                    Icon(CosmoIcons.Downloaded, null, tint = look.dim, modifier = Modifier.size(18.dp))
                }
            }
            if (onMore != null) { Spacer(Modifier.width(4.dp)); OrbitRoundButton(CosmoIcons.MoreVertical, moreLabel, look.ink, onMore, size = 36.dp) }
        })
}

/**
 * A sheet that stays over a full-bleed map (Radio Garden's station list): it rests at [peek], a drag on its [header] (or a tap on the
 * handle) moves it between [peek], about half the room and the whole of it, and a fling goes on to the next stop
 * ([PodioFormat.snap]). A list in [content] scrolls the sheet open first and, back at its top, closes it again (nested scrolling).
 * [expand] asks for the middle stop from outside (a chip that needs the list). The handle is a button for screen readers, "Show
 * stations" or "Hide stations".
 */
@Composable internal fun MapSheet(peek: Dp, maxHeight: Dp, modifier: Modifier = Modifier, expand: Int = 0,
    header: @Composable ColumnScope.() -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val look = orbitLook()
    val density = LocalDensity.current
    val reduced = LocalReducedMotion.current
    val scope = rememberCoroutineScope()
    val peekPx = with(density) { peek.toPx() }
    val maxPx = with(density) { maxHeight.toPx() }.coerceAtLeast(peekPx)
    val anchors = remember(peekPx, maxPx) { listOf(peekPx, (maxPx * .55f).coerceAtLeast(peekPx), maxPx) }
    val stored = rememberSaveable { mutableStateOf(-1f) }
    fun now(): Float = if (stored.value < 0f) peekPx else stored.value.coerceIn(peekPx, maxPx)
    suspend fun settle(target: Float) {
        if (reduced) stored.value = target else animate(now(), target) { value, _ -> stored.value = value }
    }
    LaunchedEffect(expand) { if (expand > 0 && now() < anchors[1] - 1f) settle(anchors[1]) }
    val open = now() > peekPx + 1f
    val drag = rememberDraggableState { delta -> stored.value = (now() - delta).coerceIn(peekPx, maxPx) }
    val nested = remember(peekPx, maxPx, reduced) { object : NestedScrollConnection {
        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
            val height = now()
            if (available.y >= 0f || height >= maxPx) return Offset.Zero
            val next = (height - available.y).coerceAtMost(maxPx)
            stored.value = next
            return Offset(0f, height - next)
        }
        override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
            val height = now()
            if (available.y <= 0f || height <= peekPx) return Offset.Zero
            val next = (height - available.y).coerceAtLeast(peekPx)
            stored.value = next
            return Offset(0f, height - next)
        }
        override suspend fun onPreFling(available: Velocity): Velocity {
            val height = now()
            if (height <= peekPx + 1f || height >= maxPx - 1f) return Velocity.Zero
            settle(PodioFormat.snap(height, available.y, anchors))
            return available
        }
    } }
    Column(modifier.fillMaxWidth().height(with(density) { now().toDp() })
        .clip(RoundedCornerShape(topStart = 30.dp, topEnd = 30.dp)).background(look.ground).testTag("podio:sheet")) {
        Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> settle(PodioFormat.snap(now(), velocity, anchors)) })) {
            Box(Modifier.fillMaxWidth().height(24.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide stations" else "Show stations") {
                    scope.launch { settle(if (open) peekPx else anchors[1]) }
                }.semantics { contentDescription = if (open) "Hide stations" else "Show stations" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(look.ink.copy(alpha = .3f)))
            }
            header()
        }
        Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(nested), content = content)
    }
}

internal fun RadioStation.placeLine(): String = listOf(state, country).filter(String::isNotBlank).distinct().joinToString(", ")

/** The shortest useful place of a station under its disc: the state or the country, else the language. */
internal fun RadioStation.shortPlace(): String = state.ifBlank { country }.ifBlank { language.replaceFirstChar { it.uppercase() } }
