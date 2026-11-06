package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing

/*
 * The Watch pieces of the Orbit kit (Canvas repo, design/cosmotune/watch-orbit/board.html). Films, episodes and channels are
 * pictures, not records: they stay rectangles (radius 20-26), the play moon hangs off the picture's corner and nothing is drawn
 * on a picture. Every piece takes the focus with a ring, so the same screens work with a remote or a keyboard.
 */

/** The board's "● LIVE" red. */
internal val WATCH_LIVE = Color(0xFFFF8A7A)
internal val WATCH_LIVE_INK = Color(0xFFC0392B)

/** The indigo of "Add a plugin or source". */
internal val WATCH_ADD_FILL = Color(0xFF2E2758)
internal val WATCH_ADD_INK = Color(0xFFECE8FF)

/** A picture that has not loaded (or has no address): the deep stand-in for [key], lit from one corner like a poster. */
fun pictureStandIn(key: String): Brush {
    val base = STAND_INS[Math.floorMod(key.hashCode(), STAND_INS.size)]
    val hsv = FloatArray(3)
    java.awt.Color.RGBtoHSB((base.red * 255).toInt(), (base.green * 255).toInt(), (base.blue * 255).toInt(), hsv)
    val h = hsv[0] * 360f
    return Brush.linearGradient(
        listOf(Color.hsv(h, 0.55f, 0.36f), Color.hsv(h, 0.5f, 0.09f), Color.hsv((h + 18f) % 360f, 0.62f, 0.86f)),
        start = androidx.compose.ui.geometry.Offset(0f, 0f),
        end = androidx.compose.ui.geometry.Offset(Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
    )
}

/** A rectangle picture (poster, still, backdrop): the image cut to fill, on its stand-in. Nothing is drawn on it. */
@Composable
internal fun WatchPicture(url: String?, key: String, modifier: Modifier = Modifier, radius: Dp = 20.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(pictureStandIn(key))) {
        if (!url.isNullOrBlank()) AsyncImage(model = url, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
    }
}

/** Play as a moon hanging off the bottom-end corner of a picture, ringed in the page's ground. Use inside the picture's box. */
@Composable
fun BoxScope.WatchPlayMoon(look: OrbitLook, onClick: () -> Unit, size: Dp = 42.dp, label: String = "Play") {
    Box(
        Modifier.align(Alignment.BottomEnd).offset(x = 10.dp, y = 10.dp).size(size).clip(CircleShape).background(look.ground)
            .padding(4.dp).clip(CircleShape).background(PEACH).clickable(role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(CosmoIcons.Play, contentDescription = label, tint = Color(0xFF2A100A), modifier = Modifier.size(size * 0.36f)) }
}

/** How far through: a 4 dp line, the watched part in the accent. */
@Composable
fun WatchProgressLine(fraction: Float, look: OrbitLook, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(look.ink.copy(alpha = 0.18f))) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(look.accent))
    }
}

/** A small mono badge ("HD", "CC", "FREE · PLUTO"). */
@Composable
fun WatchBadge(text: String, fonts: OrbitFonts, ink: Color, modifier: Modifier = Modifier) {
    Text(
        text.uppercase(), style = fonts.label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp, fontWeight = FontWeight.Bold), color = ink, maxLines = 1,
        modifier = modifier.clip(RoundedCornerShape(10.dp)).background(ink.copy(alpha = 0.10f)).padding(horizontal = 8.dp, vertical = 3.dp),
    )
}

/** A chip on a colour card ("＋ My list", "Watch together"): the card's [ink] at 12 %, or solid ink with [onSolid] text. */
@Composable
fun WatchCardChip(
    text: String,
    ink: Color,
    fonts: OrbitFonts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    solid: Boolean = false,
    onSolid: Color = Color(0xFF1B1416),
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val text2 = if (solid) onSolid else ink
    Row(
        modifier.alpha(if (enabled) 1f else 0.5f).height(40.dp).cosmoFocusRing(interaction, 20.dp).clip(CircleShape).background(if (solid) ink else ink.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = text2, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(text, style = fonts.chip, color = text2, maxLines = 1)
    }
}

/** The fixed colours of the built-in badges and the channel categories (board section 5), anything else by its name. */
private val LOGO_FILLS = mapOf(
    "IPTV" to 0xFF3AA06A, "PLUTO" to 0xFFE0C030, "TV+" to 0xFF3A6AB2, "PLEX" to 0xFFE0A030, "ARC" to 0xFF7A7A7A, "YT" to 0xFFC0302A,
    "PD" to 0xFF6A4AB0, "SUB" to 0xFF2A8A5A, "HUB" to 0xFF8A5A2A, "JF" to 0xFF7A5AD0, "M3U" to 0xFF3A8AD0, "ADD" to 0xFF3A8AD0,
    "EMBY" to 0xFF4A9A5A, "DIR" to 0xFF6A6A6A,
    "news" to 0xFFB23A3A, "movies" to 0xFF3A6AB2, "music" to 0xFF3AA06A, "kids" to 0xFFA05AD0, "sports" to 0xFFD08A3A,
    "documentary" to 0xFF5A8A3A, "entertainment" to 0xFFB2503A,
).mapValues { Color(it.value) }
private val LOGO_SPARE = listOf(0xFFB23A3A, 0xFF3A6AB2, 0xFF3AA06A, 0xFFA05AD0, 0xFFD08A3A, 0xFF5A8A3A, 0xFF7A5AD0).map { Color(it) }

/** The tile colour for a badge or a channel category. */
internal fun logoFill(key: String): Color = LOGO_FILLS[key] ?: LOGO_FILLS[key.lowercase()] ?: LOGO_SPARE[Math.floorMod(key.hashCode(), LOGO_SPARE.size)]

/** A channel's short mark when it has no logo: "News 24" is N24, "Cine Classics" CINE, "Tamil Music" TAMIL. */
internal fun channelMark(name: String): String {
    val words = name.split(' ', '-', '_').filter { it.isNotBlank() }
    val number = words.firstOrNull { w -> w.all(Char::isDigit) }
    val first = words.firstOrNull().orEmpty()
    return when {
        number != null && first != number -> (first.take(1) + number).uppercase().take(5)
        first.length <= 5 -> first.uppercase()
        else -> first.take(4).uppercase()
    }
}

/** A 46 dp rounded tile: the logo on its colour, or the short [mark] in the narrow face. Plugins and channels use it. */
@Composable
internal fun WatchLogoTile(mark: String, fill: Color, fonts: OrbitFonts, modifier: Modifier = Modifier, logoUrl: String? = null, size: Dp = 46.dp) {
    val dark = fill.red * 0.299f + fill.green * 0.587f + fill.blue * 0.114f > 0.62f
    Box(modifier.size(size).clip(RoundedCornerShape(size * 0.3f)).background(fill), contentAlignment = Alignment.Center) {
        if (!logoUrl.isNullOrBlank()) {
            AsyncImage(model = logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(6.dp))
        } else {
            Text(
                mark, style = fonts.number.copy(fontSize = (size.value * 0.33f).sp, lineHeight = (size.value * 0.36f).sp), maxLines = 1,
                color = if (dark) Color(0xFF1B1416) else Color.White, textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A Watch row (board `.r`): radius 22, ink at 5 %, or the accent wash when [highlighted] (the best source, the next episode).
 * Focusable with a ring; [onClick] null makes it a plain row.
 */
@Composable
fun WatchRowFrame(
    look: OrbitLook,
    modifier: Modifier = Modifier,
    height: Dp = 62.dp,
    highlighted: Boolean = false,
    onClick: (() -> Unit)? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val fill = if (highlighted) Brush.horizontalGradient(0f to look.accent.copy(alpha = if (look.dark) 0.22f else 0.18f), 0.75f to look.row)
    else Brush.linearGradient(listOf(look.row, look.row))
    Row(
        modifier.fillMaxWidth().height(height).cosmoFocusRing(interaction, 22.dp).clip(RoundedCornerShape(22.dp)).background(fill)
            .then(
                if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
                else Modifier,
            )
            .padding(start = 8.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        content = content,
    )
}

/** A poster or a still with its title and line under it; Play hangs off the picture's corner when [onPlay] is given. */
@Composable
internal fun WatchCard(
    title: String,
    line: String?,
    pictureUrl: String?,
    key: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    width: Dp? = 132.dp,
    wide: Boolean = false,
    progress: Float? = null,
    onPlay: (() -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.then(if (width != null) Modifier.width(width) else Modifier.fillMaxWidth()).cosmoFocusRing(interaction, 22.dp)
            .clickable(interactionSource = interaction, indication = null, role = Role.Button, onClick = onClick),
    ) {
        // The moon hangs 10 dp past the picture: the column leaves it room at the end and under it.
        Box(Modifier.padding(end = if (onPlay != null) 10.dp else 0.dp).fillMaxWidth().aspectRatio(if (wide) 16f / 9f else 2f / 3f)) {
            WatchPicture(pictureUrl, key, Modifier.fillMaxSize(), radius = 20.dp)
            if (onPlay != null) WatchPlayMoon(look, onPlay, size = if (wide || width == null || width >= 140.dp) 42.dp else 38.dp)
        }
        if (progress != null) WatchProgressLine(progress, look, Modifier.padding(top = 8.dp, end = if (onPlay != null) 10.dp else 0.dp))
        Text(title, style = fonts.title.copy(fontSize = 13.5.sp, lineHeight = 17.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 10.dp, end = 8.dp))
        if (!line.isNullOrBlank()) Text(line, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** "● LIVE · Evening bulletin": the live mark in red, then what is on. */
@Composable
internal fun WatchLiveLine(now: String?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (look.dark) WATCH_LIVE else WATCH_LIVE_INK, fontWeight = FontWeight.Bold, fontSize = 11.sp)) { append("● LIVE") }
            if (!now.isNullOrBlank()) append(" · $now")
        },
        style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/** The switch of a plugin (board `.tog`): 46 by 28, the accent with a dark knob at the end when on, faint ink with a pale knob when off. */
@Composable
fun OrbitSwitch(checked: Boolean, onChange: (Boolean) -> Unit, look: OrbitLook, label: String, modifier: Modifier = Modifier, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val knobOn = if (look.dark) Color(0xFF2A100A) else Color.White
    val knobOff = if (look.dark) Color(0xFFE3D0C6) else Color.White
    Box(
        modifier.size(46.dp, 28.dp).cosmoFocusRing(interaction, 14.dp).clip(CircleShape)
            .background(if (checked) look.accent else look.ink.copy(alpha = 0.18f))
            .toggleable(checked, interactionSource = interaction, indication = null, enabled = enabled, role = Role.Switch, onValueChange = onChange)
            .semantics { contentDescription = label }
            .padding(3.dp),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) { Box(Modifier.size(22.dp).clip(CircleShape).background(if (checked) knobOn else knobOff)) }
}

/** A plugin as the Plugins page shows it (board `.src`): radius 26, its tile, name and line, and [trailing] (the switch). */
@Composable
internal fun WatchPluginCard(
    name: String,
    line: String,
    badge: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    error: Boolean = false,
    trailing: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier.fillMaxWidth().clip(RoundedCornerShape(26.dp)).background(look.row).padding(horizontal = 18.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        WatchLogoTile(badge, logoFill(badge), fonts)
        Column(Modifier.weight(1f)) {
            Text(name, style = fonts.title.copy(fontSize = 14.5.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(line, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp), color = if (error) (if (look.dark) WATCH_LIVE else WATCH_LIVE_INK) else look.dim,
                maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        trailing()
    }
}

/** A quiet stand-in block while a row or a card loads. */
@Composable
fun WatchSkeleton(look: OrbitLook, modifier: Modifier, radius: Dp = 20.dp) {
    Box(modifier.clip(RoundedCornerShape(radius)).background(look.row))
}

/** An empty or problem state: a mono label, a wide heading, a line and an optional action chip. */
@Composable
fun WatchMessage(label: String, title: String, text: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(modifier.widthIn(max = 560.dp).clip(RoundedCornerShape(26.dp)).background(look.row).padding(22.dp)) {
        OrbitLabel(label, fonts, look.dim)
        Text(title, style = fonts.section, color = look.ink, modifier = Modifier.padding(top = 10.dp))
        Text(text, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 6.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(14.dp))
            WatchCardChip(action, look.ink, fonts, onAction, solid = true, onSolid = look.ground)
        }
    }
}

/** A round tonal button that takes the focus (back, more, a star). */
@Composable
fun WatchRoundButton(icon: ImageVector, label: String, look: OrbitLook, onClick: () -> Unit, modifier: Modifier = Modifier, ink: Color = look.ink, size: Dp = 40.dp, on: Boolean = false, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier.alpha(if (enabled) 1f else 0.45f).size(size).cosmoFocusRing(interaction, size / 2).clip(CircleShape).background(if (on) ink else ink.copy(alpha = 0.12f))
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, enabled = enabled, role = Role.Button, onClickLabel = label, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = label, tint = if (on) look.ground else ink, modifier = Modifier.size(size * 0.45f)) }
}

/** A focus target with no look of its own (TV rows that have no click). */
internal fun Modifier.watchFocusable(interaction: MutableInteractionSource): Modifier = this.focusable(interactionSource = interaction)
