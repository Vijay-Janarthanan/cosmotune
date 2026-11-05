package com.cosmotune.podio.ui

import androidx.compose.animation.core.animate
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.podio.RadioStation
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitArtwork
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoSpacing
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlinx.coroutines.launch

/*
 * Podio's own pieces, written in the kit's manner (Cosmo palette and type, rows on the page, thin chips, one accent) for what the kit
 * does not have yet: a station's logo tile, a station row with a heart and the song on air, an episode row with its progress and
 * download state, a chip that opens a menu, a draggable sheet that stays over a map, and a round play button that can say Stop.
 * Each is a candidate for promotion into com.cosmotune.ui.kit.
 */

/** The station's logo; a station with none (or one that does not load) gets a tile in its own colour with its initials. */
@Composable internal fun StationLogo(station: RadioStation, size: Dp, radius: Dp = CosmoRadii.rSm) {
    var loaded by remember(station.artwork) { mutableStateOf(false) }
    val type = LocalCosmoType.current
    val tile = remember(station.name) { Color.hsv(PodioFormat.hue(station.name).toFloat(), .45f, .42f) }
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(if (loaded) Color.White else tile), contentAlignment = Alignment.Center) {
        if (!loaded) Text(PodioFormat.initials(station.name), color = Color.White, style = type.label.copy(fontSize = (size.value * .34f).sp, fontWeight = FontWeight(600)), maxLines = 1)
        if (station.artwork.isNotBlank()) AsyncImage(station.artwork, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit, onSuccess = { loaded = true })
    }
}

/** The small red dot of a live stream, with "LIVE" beside it when [label] is set. */
@Composable internal fun LiveMark(label: Boolean = true, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(Color(palette.danger)))
        if (label) Text("LIVE", style = type.overline, color = Color(palette.textSecondary), maxLines = 1)
    }
}

/** The heart of a station or a show: a toggle, the filled heart in the accent when [saved]. */
@Composable internal fun HeartButton(saved: Boolean, onClick: () -> Unit, savedLabel: String = "Remove favourite", unsavedLabel: String = "Save favourite") {
    CosmoIconButton(if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart, if (saved) savedLabel else unsavedLabel, onClick,
        style = CosmoIconButtonStyle.PLAIN, size = 40.dp, on = saved, accentWhenOn = true)
}

/**
 * A station in a list (the kit's media row with a logo): the logo, the name over the place, language and stream, or over what is on
 * air while it plays ([onAir]); the heart at the end. A tap plays it; the one that is on draws its name in the accent.
 */
@Composable internal fun StationRow(station: RadioStation, saved: Boolean, current: Boolean, onPlay: () -> Unit, onSave: () -> Unit, onAir: String? = null, showHeart: Boolean = true) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).combinedClickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay)
        .padding(start = CosmoSpacing.margin, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        StationLogo(station, 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(station.name, style = type.forText(type.rowTitle, station.name),
                color = Color(if (current) palette.accentStrong else palette.textPrimary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (current) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LiveMark(label = false)
                Text(onAir?.let { "On air: $it" } ?: "On air now", style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                val meta = PodioFormat.stationMeta(station.placeLine(), station.language, station.codec, station.bitrate)
                if (meta.isNotBlank()) Text(meta, style = type.meta, color = Color(palette.textSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (showHeart) HeartButton(saved, onSave) else Spacer(Modifier.width(12.dp))
    }
}

/** What a download button shows for an episode. */
internal enum class DownloadMark { NONE, AVAILABLE, RUNNING, DONE }

/**
 * An episode in a show's list (Prism's and AntennaPod's): the art, the title on up to two lines, "date · length · progress ·
 * download" under it, a thin progress line for a started episode, and the download button at the end. A tap plays it (or resumes);
 * [onMore] opens the episode's sheet.
 */
@Composable internal fun EpisodeRow(title: String, meta: String, artUrl: String?, progress: Float, current: Boolean, download: DownloadMark,
    onPlay: () -> Unit, onDownload: () -> Unit, onMore: (() -> Unit)?) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    Row(Modifier.fillMaxWidth().heightIn(min = 72.dp).combinedClickable(role = Role.Button, onClickLabel = "Play", onClick = onPlay, onLongClick = onMore)
        .padding(start = CosmoSpacing.margin, end = 4.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
        KitArtwork(artUrl?.takeIf(String::isNotBlank), KitArt.SQUARE, 56.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(title, style = type.forText(type.rowTitle, title),
                color = Color(if (current) palette.accentStrong else palette.textPrimary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (meta.isNotBlank()) Text(meta, style = type.meta, color = Color(palette.textSecondary), maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (progress > 0f) Box(Modifier.padding(top = 4.dp).fillMaxWidth(.6f).height(3.dp).clip(CircleShape).background(Color(palette.track))) {
                Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(Color(palette.accent)))
            }
        }
        when (download) {
            DownloadMark.NONE -> {}
            DownloadMark.AVAILABLE -> CosmoIconButton(CosmoIcons.Download, "Download episode", onDownload, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
            DownloadMark.RUNNING -> CosmoIconButton(CosmoIcons.Close, "Cancel episode download", onDownload, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
            DownloadMark.DONE -> CosmoIconButton(CosmoIcons.Downloaded, "Episode downloaded", onDownload, style = CosmoIconButtonStyle.PLAIN, size = 40.dp, enabled = false)
        }
        if (onMore != null) CosmoIconButton(CosmoIcons.MoreVertical, "Episode details", onMore, style = CosmoIconButtonStyle.PLAIN, size = 40.dp)
    }
}

/**
 * A thin chip (the kit's chip, design system 4.4's dropdown chip) that opens a menu of [choices] (id to label): it reads the chosen
 * label with a chevron after it, and takes the inverse neutral while a choice other than the first ("World", "All languages") is made.
 */
@Composable internal fun MenuChip(title: String, value: String, choices: List<Pair<String, String>>, onSelect: (String) -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    var expanded by remember { mutableStateOf(false) }
    val active = value != choices.firstOrNull()?.first
    val label = choices.firstOrNull { it.first == value }?.second ?: value
    // The kit chip's colours (DESIGN_SYSTEM.md 4.4): surface2 at rest, the inverse neutral when a choice is made; never the accent.
    val ink = Color(if (active) palette.backgroundBottom else palette.textPrimary)
    Box(modifier) {
        Row(Modifier.minimumInteractiveComponentSize().height(34.dp).clip(RoundedCornerShape(50)).background(Color(if (active) palette.textPrimary else palette.surfaceHigh))
            .clickable(role = Role.Button, onClickLabel = "Choose $title") { expanded = true }
            .semantics { stateDescription = "$title: $label" }
            .padding(start = 14.dp, end = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = type.control, color = ink, maxLines = 1)
            Icon(CosmoIcons.ChevronDown, null, tint = ink, modifier = Modifier.size(16.dp))
        }
        DropdownMenu(expanded, { expanded = false }, modifier = Modifier.heightIn(max = 360.dp)) {
            choices.forEach { (id, name) -> DropdownMenuItem(text = { Text(name) }, onClick = { expanded = false; onSelect(id) },
                trailingIcon = if (id == value) ({ Icon(CosmoIcons.Check, null, Modifier.size(18.dp)) }) else null) }
        }
    }
}

/**
 * The round play button of Podio's pages: the accent filled circle with the play, pause or (a live station) stop glyph, a spinner
 * while it loads. Named for what a press does.
 */
@Composable internal fun PodioPlayButton(playing: Boolean, loading: Boolean, live: Boolean, onClick: () -> Unit, size: Dp = 48.dp, modifier: Modifier = Modifier,
    /** The accent circle (a player, a station page); false is the mini player's plain glyph on the neutral dock. */ filled: Boolean = true) {
    val palette = LocalCosmoColors.current.palette
    val label = when { playing && live -> "Stop"; playing -> "Pause"; else -> "Play" }
    val ink = Color(if (filled) palette.onAccent else palette.textPrimary)
    Box(modifier.minimumInteractiveComponentSize().size(size).clip(CircleShape).then(if (filled) Modifier.background(Color(palette.accent)) else Modifier)
        .clickable(role = Role.Button, onClickLabel = label, onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        if (loading) CircularProgressIndicator(Modifier.size(size * .45f), color = if (filled) ink else Color(palette.textSecondary), strokeWidth = 2.dp)
        else Icon(when { playing && live -> Icons.Outlined.Stop; playing -> CosmoIcons.Pause; else -> CosmoIcons.Play }, null,
            tint = ink, modifier = Modifier.size(if (filled) size * .5f else 24.dp))
    }
}

/** A labelled text-and-icon pill that toggles, for the player's speed and timer ("1.5×", "Sleep timer"). */
@Composable internal fun ToolPill(text: String, icon: ImageVector?, onClick: () -> Unit, modifier: Modifier = Modifier, active: Boolean = false) {
    val palette = LocalCosmoColors.current.palette
    val type = LocalCosmoType.current
    val ink = Color(palette.textPrimary)
    Row(modifier.minimumInteractiveComponentSize().heightIn(min = 36.dp).clip(RoundedCornerShape(50))
        .background(Color(if (active) palette.surfaceVariant else palette.surfaceHigh)).clickable(role = Role.Button, onClick = onClick)
        .padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (icon != null) Icon(icon, null, tint = ink, modifier = Modifier.size(16.dp))
        Text(text, style = type.control, color = ink, maxLines = 1)
    }
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
    val palette = LocalCosmoColors.current.palette
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
        .clip(RoundedCornerShape(topStart = CosmoRadii.rXl, topEnd = CosmoRadii.rXl)).background(Color(palette.backgroundBottom)).testTag("podio:sheet")) {
        Column(Modifier.fillMaxWidth().draggable(drag, Orientation.Vertical, onDragStopped = { velocity -> settle(PodioFormat.snap(now(), velocity, anchors)) })) {
            Box(Modifier.fillMaxWidth().height(24.dp)
                .clickable(role = Role.Button, onClickLabel = if (open) "Hide stations" else "Show stations") {
                    scope.launch { settle(if (open) peekPx else anchors[1]) }
                }.semantics { contentDescription = if (open) "Hide stations" else "Show stations" }, contentAlignment = Alignment.Center) {
                Box(Modifier.size(36.dp, 4.dp).clip(CircleShape).background(Color(palette.textTertiary).copy(alpha = .6f)))
            }
            header()
        }
        Column(Modifier.fillMaxWidth().weight(1f).nestedScroll(nested), content = content)
    }
}

internal fun RadioStation.placeLine(): String = listOf(state, country).filter(String::isNotBlank).distinct().joinToString(", ")
