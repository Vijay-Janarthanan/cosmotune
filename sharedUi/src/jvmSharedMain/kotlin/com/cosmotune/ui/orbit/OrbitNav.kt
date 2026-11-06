package com.cosmotune.ui.orbit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.cosmo_mode_switch
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.kit.KitModeMenuList
import com.cosmotune.ui.kit.modeName
import com.cosmotune.ui.room.LocalRoomLauncher
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.ui.theme.LocalCosmoType
import org.jetbrains.compose.resources.stringResource
import kotlin.math.cos
import kotlin.math.sin

/*
 * The Orbit left navigation's pieces (board v2, section 6), public so the desktop's sidebar can be built of them: the mode switcher
 * as a chip with the orbit mark, pages as Home's 40 dp chips (the selected one solid ink with a small peach dot), "Your playlists"
 * as small discs, and Home's indigo room card made small for the foot.
 */

/** A row's wash while the pointer is on it or presses it: the page's ink, faint. */
private fun wash(look: OrbitLook, hovered: Boolean, pressed: Boolean): Color =
    if (pressed) look.ink.copy(alpha = 0.10f) else if (hovered) look.ink.copy(alpha = 0.06f) else Color.Transparent

/** The orbit mark: a small planet and its tilted ring, in the accent. */
@Composable
private fun OrbitMark(color: Color, size: Dp = 20.dp) {
    Canvas(Modifier.size(size)) {
        val u = this.size.minDimension / 24f
        drawCircle(color, 3.2f * u, center)
        rotate(-25f, center) {
            drawOval(color, topLeft = center - Offset(10f * u, 4.5f * u), size = Size(20f * u, 9f * u), style = Stroke(1.8f * u))
        }
    }
}

/**
 * The mode switcher as the navigation's first chip: the orbit mark, the mode's name and a chevron, on the chip fill. A click opens
 * the list of modes under it ([KitModeMenuList], with its shortcuts); picking one switches. Nothing without a shell.
 */
@Composable
fun OrbitNavModeChip(modifier: Modifier = Modifier) {
    val settings = LocalCosmoShell.current?.settings ?: return
    val look = orbitLook()
    val fonts = orbitFonts()
    val mode by settings.mode.collectAsState()
    var open by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(CHIP).clip(CircleShape).background(look.chip)
                .clickable(role = Role.Button, onClickLabel = stringResource(Res.string.cosmo_mode_switch)) { open = !open }
                .pointerHoverIcon(PointerIcon.Hand)
                .padding(start = 10.dp, end = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) { OrbitMark(look.accent) }
            Spacer(Modifier.width(8.dp))
            Text(modeName(mode), style = fonts.chip, color = look.ink, maxLines = 1, modifier = Modifier.weight(1f))
            Icon(if (open) CosmoIcons.ChevronUp else CosmoIcons.ChevronDown, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp))
        }
        if (open) KitModeMenuList(mode, onSelect = { settings.setMode(it); open = false }, modifier = Modifier.padding(top = 4.dp))
    }
}

/**
 * A page of the navigation as a 40 dp Orbit chip: the icon and the label on no fill (a faint wash under the pointer); the
 * [selected] one is solid ink with the ground's colour for its words and a small peach dot at its end. [aside] is a short count
 * before the end (the queued downloads).
 */
@Composable
fun OrbitNavChip(icon: ImageVector, label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, aside: String? = null) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val ink = if (selected) look.ground else look.ink.copy(alpha = 0.86f)
    Row(
        modifier
            .fillMaxWidth()
            .height(CHIP)
            .cosmoFocusRing(interaction, CHIP / 2)
            .clip(CircleShape)
            .background(if (selected) look.ink else wash(look, hovered, pressed))
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Tab, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(start = 12.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp), contentAlignment = Alignment.Center) { Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(18.dp)) }
        Spacer(Modifier.width(8.dp))
        Text(label, style = type.forText(fonts.chip, label), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (aside != null) {
            Text(aside, style = fonts.label, color = if (selected) ink else look.accent, maxLines = 1, modifier = Modifier.padding(start = 6.dp))
        }
        if (selected) {
            // Peach is "playing now" and "here": the dark peach on a light chip, the light one on a dark chip.
            Box(Modifier.padding(start = 8.dp).size(7.dp).clip(CircleShape).background(if (look.dark) PEACH_INK else PEACH))
        }
    }
}

/** A heading of the navigation ("Your playlists"): the mono label, uppercase and tracked, in the dim ink. */
@Composable
fun OrbitNavSectionLabel(text: String, modifier: Modifier = Modifier) {
    val look = orbitLook()
    Text(text.uppercase(), style = orbitFonts().label, color = look.dim, maxLines = 1, modifier = modifier)
}

/**
 * A playlist of the navigation: a 34 dp [Disc] of its cover (the queue glyph on the chip fill without one), its title and the line
 * under it (the song count). 46 dp, round; the [selected] one wears the chip fill.
 */
@Composable
fun OrbitNavPlaylist(title: String, subtitle: String?, imageUrl: String?, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val look = orbitLook()
    val fonts = orbitFonts()
    val type = LocalCosmoType.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    Row(
        modifier
            .fillMaxWidth()
            .height(46.dp)
            .cosmoFocusRing(interaction, 23.dp)
            .clip(CircleShape)
            .background(if (selected) look.chip else wash(look, hovered, pressed))
            .selectable(selected = selected, interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        if (imageUrl != null) {
            Disc(imageUrl, PLAYLIST_DISC)
        } else {
            Box(Modifier.size(PLAYLIST_DISC).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                Icon(CosmoIcons.Queue, contentDescription = null, tint = look.dim, modifier = Modifier.size(16.dp))
            }
        }
        Column(Modifier.weight(1f)) {
            val titleStyle = fonts.body.copy(fontSize = 13.5.sp, fontWeight = FontWeight(700), lineHeight = 17.sp)
            Text(title, style = type.forText(titleStyle, title), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                val subStyle = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 15.sp)
                Text(subtitle, style = type.forText(subStyle, subtitle), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Home's indigo room card made small, for the foot of the navigation: the live dot and "LIVE ROOM · n", "Your room", and the room
 * drawn as a small system at its corner (a moon per person). A click opens the room, after [onOpen] (the profile drawer closes
 * itself there, so the room is not left under it). Nothing outside a room.
 */
@Composable
fun OrbitNavRoomCard(modifier: Modifier = Modifier, onOpen: () -> Unit = {}) {
    val room = LocalRoomLauncher.current?.takeIf { it.inRoom } ?: return
    val fonts = orbitFonts()
    val tone = roomCardColors(orbitLook())
    Box(
        modifier.fillMaxWidth().height(96.dp).clip(RoundedCornerShape(26.dp)).background(tone.fill)
            .clickable(role = Role.Button) { onOpen(); room.openRoom() }.pointerHoverIcon(PointerIcon.Hand),
    ) {
        Canvas(Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-44).dp).size(140.dp)) {
            val c = center
            drawCircle(tone.ink.copy(alpha = 0.16f), 30.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(tone.ink.copy(alpha = 0.16f), 54.dp.toPx(), c, style = Stroke(1.dp.toPx()))
            drawCircle(tone.ink.copy(alpha = 0.22f), 13.dp.toPx(), c)
            val colors = listOf(Color(0xFFE9875A), Color(0xFF5B8C6A), Color(0xFF8B6CC9), Color(0xFFD9A441), Color(0xFF4F8FD9), Color(0xFFC96C9A))
            repeat(room.memberCount.coerceIn(1, 6)) { i ->
                val r = if (i % 2 == 0) 54.dp.toPx() else 30.dp.toPx()
                val a = Math.toRadians(115.0 + i * 40.0)
                drawCircle(colors[i % colors.size], 8.dp.toPx(), c + Offset((r * cos(a)).toFloat(), (r * sin(a)).toFloat()))
            }
        }
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(tone.live))
                Spacer(Modifier.width(8.dp))
                Text("LIVE ROOM · ${room.memberCount}", style = fonts.label, color = tone.dim, maxLines = 1)
            }
            Spacer(Modifier.height(6.dp))
            Text("Your room", style = fonts.section.copy(fontSize = 17.sp, lineHeight = 20.sp), color = tone.ink, maxLines = 1)
        }
    }
}

private val CHIP = 40.dp
private val PLAYLIST_DISC = 34.dp
