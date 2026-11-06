package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.ui.components.CosmoIcons

/*
 * The room's Orbit pieces (board batch-a, section 3): a person as a disc with the green orbit ring lit while they speak and a mono
 * status under their name, the big round "Tap to talk" button with its two lines, a song request as a small colour card with
 * "Play it", and the chat's input pill.
 */

/** The green of someone talking: the room card's live dot in dark, a deeper green that reads on paper in light. */
internal fun orbitTalking(look: OrbitLook): Color = if (look.dark) LIVE else Color(0xFF1E8A4C)

/** The colours a face without a picture wears, picked by the person, so a room is not a row of grey. */
private val FACE_FILLS = listOf(0xFFE9875A, 0xFF5B8C6A, 0xFF8B6CC9, 0xFFD9A441, 0xFF4F8FD9, 0xFFC96C9A, 0xFF3FA39A).map { Color(it) }

/** A person's face: their picture as a disc, or a disc of their own colour with their initial while there is none. */
@Composable
internal fun OrbitFace(url: String?, name: String, key: String, size: Dp, modifier: Modifier = Modifier) {
    Box(modifier.size(size).clip(CircleShape).background(FACE_FILLS[Math.floorMod(key.hashCode(), FACE_FILLS.size)]), contentAlignment = Alignment.Center) {
        val initial = name.trim().firstOrNull()?.uppercaseChar()?.toString().orEmpty()
        if (initial.isNotEmpty()) {
            Text(initial, color = Color.White.copy(alpha = 0.92f), fontWeight = FontWeight(800), fontSize = (size.value * 0.4f).sp, maxLines = 1)
        }
        if (url != null) Disc(url, size)
    }
}

/**
 * A person in the room's strip: [face] (a 72 dp [OrbitFace] with its ring), the name under it and the mono [status] ("TALKING" in
 * the green while [talking], the dim ink otherwise). [onClick] makes the whole of it one button.
 */
@Composable
internal fun OrbitPersonDisc(
    name: String,
    status: String,
    talking: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    face: @Composable () -> Unit,
) {
    Column(
        modifier.widthIn(min = 80.dp).clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null) Modifier.clickable(role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 8.dp, horizontal = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(6.dp)) { face() }
        Text(name, style = fonts.title.copy(fontSize = 13.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp).widthIn(max = 96.dp), textAlign = TextAlign.Center)
        Text(status.uppercase(), style = fonts.label, color = if (talking) orbitTalking(look) else look.dim, maxLines = 1, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The microphone as the board draws it: a [size] disc of [fill] with [icon] in [iconInk], [title] and [subtitle] beside it, and
 * [badge] on the disc's corner (the level meter). The caller makes the row one button and says its state.
 */
@Composable
internal fun OrbitTalkButton(
    icon: ImageVector,
    fill: Color,
    iconInk: Color,
    title: String,
    subtitle: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    size: Dp = 64.dp,
    titleInk: Color = look.ink,
    badge: (@Composable BoxScope.() -> Unit)? = null,
) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(size)) {
            Box(Modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
                Icon(icon, contentDescription = null, tint = iconInk, modifier = Modifier.size(size * 0.42f))
            }
            badge?.invoke(this)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f, fill = false)) {
            Text(title, style = fonts.title, color = titleInk, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/**
 * A song asked for in the chat: a small colour card of its cover's own colour ([coverFill]), radius 22, the disc, the mono [label]
 * over the [title], and the [action] ("Play it") as a chip of the card's ink at the end.
 */
@Composable
internal fun OrbitRequestCard(
    label: String,
    title: String,
    subtitle: String?,
    cover: String?,
    key: String,
    fonts: OrbitFonts,
    modifier: Modifier = Modifier,
    action: @Composable RowScope.(fill: Color, ink: Color) -> Unit,
) {
    val (fill, ink) = coverFill(cover, key)
    Row(
        modifier.clip(RoundedCornerShape(22.dp)).background(fill).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Disc(cover, 44.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(label, style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(title, style = fonts.title.copy(fontSize = 14.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
            if (!subtitle.isNullOrBlank()) Text(subtitle, style = fonts.body.copy(fontSize = 12.5.sp), color = ink.copy(alpha = 0.8f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(10.dp))
        action(fill, ink)
    }
}

/** "Play it": a 36 dp chip of the card's [ink] with the card's [fill] as its words, disabled at 45 %. */
@Composable
internal fun OrbitCardAction(text: String, fill: Color, ink: Color, fonts: OrbitFonts, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Row(
        modifier.heightIn(min = 36.dp).clip(CircleShape).background(if (enabled) ink else ink.copy(alpha = 0.45f))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(start = 10.dp, end = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CosmoIcons.Play, contentDescription = null, tint = fill, modifier = Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = fonts.chip.copy(fontSize = 13.sp, fontWeight = FontWeight(800)), color = fill, maxLines = 1)
    }
}

/**
 * The chat's input pill: radius 24, the chip's fill, at least 48 dp high; [field] takes what is left, [trailing] (Ask for a song,
 * Send) sits at its end.
 */
@Composable
internal fun OrbitInputPill(look: OrbitLook, modifier: Modifier = Modifier, field: @Composable BoxScope.() -> Unit, trailing: @Composable RowScope.() -> Unit) {
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(24.dp)).background(look.chip).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Box(Modifier.weight(1f).padding(vertical = 6.dp), contentAlignment = Alignment.CenterStart, content = field)
        trailing()
    }
}

/** A quiet notice in the room (the connection coming back, a refused control): a row of the chip's fill with its dot and words. */
@Composable
internal fun OrbitNotice(text: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, icon: ImageVector? = null, end: (@Composable RowScope.() -> Unit)? = null) {
    Row(
        modifier.heightIn(min = 48.dp).clip(RoundedCornerShape(22.dp)).background(look.chip).padding(start = 16.dp, end = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = look.accent, modifier = Modifier.size(18.dp))
        else Box(Modifier.size(8.dp).clip(CircleShape).background(look.accent))
        Spacer(Modifier.width(12.dp))
        Text(text, style = fonts.body, color = look.ink, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(vertical = 10.dp))
        end?.invoke(this)
    }
}

/** The room's ground wash: the indigo of [ROOM_FILL] falling from the top of the page and gone by three quarters of the way down. */
internal fun orbitRoomWash(look: OrbitLook): androidx.compose.ui.graphics.Brush {
    val indigo = if (look.dark) Color(0xFF3C328C) else Color(0xFF7B6FE0)
    return orbitWash(indigo, look, darkAlpha = 0.6f, lightAlpha = 0.16f, tail = 1f / 3f)
}

/** The room's mono labels on its ground: the room card's lavender in dark, the dim ink in light. */
internal fun orbitRoomLabel(look: OrbitLook): Color = if (look.dark) ROOM_DIM else look.dim
