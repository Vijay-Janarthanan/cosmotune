package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
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
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.ui.components.CosmoIcons
import androidx.compose.foundation.clickable

/*
 * The radio and podcast pieces of the Orbit kit (Canvas repo, design/cosmotune/batch-be/board.html, section C). A station is a
 * disc like a song, its logo inside it; the one on air wears the orbit. An episode is a song row whose disc carries how much of it
 * has been heard as a faint orbit. A chip can open a list of choices (a region, a language) in an [OrbitMenu].
 */

/** The "● LIVE" red on a deep colour card (the card is deep in both palettes). */
internal val LIVE_ON_CARD = Color(0xFFFF8A7A)

/**
 * A station's logo as a disc: the logo fitted inside a pale disc once it has loaded, else the station's own deep colour (the same
 * for the same name) with its short mark in the narrow face ("SUN", "FM98"), as the board draws them.
 */
@Composable
internal fun OrbitLogoDisc(logo: String?, name: String, size: Dp, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    var loaded by remember(logo) { mutableStateOf(false) }
    val fill = remember(name) { stationFill(name) }
    Box(modifier.size(size).clip(CircleShape).background(if (loaded) LOGO_GROUND else fill), contentAlignment = Alignment.Center) {
        if (!loaded) {
            Text(
                channelMark(name.ifBlank { "FM" }), style = fonts.number.copy(fontSize = (size.value * 0.24f).sp, lineHeight = (size.value * 0.26f).sp),
                color = CARD_INK, maxLines = 1, textAlign = TextAlign.Center,
            )
        }
        if (!logo.isNullOrBlank()) {
            AsyncImage(
                logo, contentDescription = null, contentScale = ContentScale.Fit, onSuccess = { loaded = true },
                modifier = Modifier.fillMaxSize().padding(size * 0.16f),
            )
        }
    }
}

private val LOGO_GROUND = Color(0xFFF7F1EC)

/** A station's own deep colour, picked from its name: saturated enough to tell stations apart, deep enough for pale ink. */
internal fun stationFill(name: String): Color {
    val hue = (name.fold(7) { total, char -> total * 31 + char.code } and 0x7fffffff) % 360
    return Color.hsv(hue.toFloat(), 0.58f, 0.52f)
}

/**
 * A station on a shelf or in a grid (board section C, "Stations"): its [OrbitLogoDisc] [size] wide, the name and a line under it,
 * centred. The one [onAir] wears the orbit, circling while [playing]. A tap plays; a hold or a right click is [onLongClick].
 */
@Composable
internal fun OrbitStationRecord(
    name: String,
    line: String,
    logo: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onAir: Boolean = false,
    playing: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    size: Dp = 110.dp,
) {
    Column(
        modifier.width(size + 12.dp).clip(androidx.compose.foundation.shape.RoundedCornerShape(18.dp))
            .longPressMenuClickable(onClick = onClick, onLongClick = onLongClick).contextClick(onLongClick)
            .semantics { if (onAir) stateDescription = "On air" },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.size(size + 12.dp), contentAlignment = Alignment.Center) {
            OrbitLogoDisc(logo, name, size - 8.dp, fonts)
            if (onAir) OrbitRing(size + 12.dp, look.accent, progress = null, spinning = playing, stroke = 2.dp, moon = 5.dp)
        }
        Text(name, style = fonts.title.copy(fontSize = 13.5.sp, lineHeight = 17.sp), color = if (onAir) look.accent else look.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp, start = 4.dp, end = 4.dp))
        if (line.isNotBlank()) {
            Text(line, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/**
 * The disc of an episode in a row: its cover, and round it a faint orbit of how much has been [heard] (0..1) with the moon where
 * the listening stopped. Fits [OrbitSongRow]'s disc slot (the slot is 8 dp wider than [size]).
 */
@Composable
internal fun OrbitHeardDisc(cover: String?, size: Dp, heard: Float, look: OrbitLook) {
    Box(Modifier.size(size + 8.dp), contentAlignment = Alignment.Center) {
        Disc(cover, size)
        if (heard > 0f) OrbitRing(size + 8.dp, look.dim, progress = { heard }, spinning = false, stroke = 1.5.dp, moon = 3.dp)
    }
}

/** "● LIVE · Chennai · Tamil": the live mark in red, then the rest, in the mono label face, on [ink] (a card's, or the page's dim). */
@Composable
internal fun OrbitLiveLabel(text: String, ink: Color, live: Color, fonts: OrbitFonts, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = live, fontWeight = FontWeight.Bold)) { append("● LIVE") }
            if (text.isNotBlank()) append(" · " + text.uppercase())
        },
        style = fonts.label, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier,
    )
}

/**
 * A chip that opens a list of [choices] (id to label) in an [OrbitMenu]: it reads the chosen label and a chevron, and is solid ink
 * while a choice other than the first ("World", "All languages") is in force. [title] names it for screen readers.
 */
@Composable
internal fun OrbitChoiceChip(
    title: String,
    value: String,
    choices: List<Pair<String, String>>,
    onSelect: (String) -> Unit,
    fonts: OrbitFonts,
    look: OrbitLook,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
) {
    var open by remember { mutableStateOf(false) }
    val active = value != choices.firstOrNull()?.first
    val label = choices.firstOrNull { it.first == value }?.second ?: value
    val ink = if (active) look.ground else look.ink
    Box(modifier) {
        Row(
            Modifier.height(40.dp).clip(CircleShape).background(if (active) look.ink else look.chip)
                .clickable(role = Role.Button, onClickLabel = "Choose $title") { open = true }
                .semantics { stateDescription = "$title: $label" }
                .padding(start = if (icon != null) 12.dp else 16.dp, end = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, tint = if (active) ink else look.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
            Text(label, style = fonts.chip, color = ink, maxLines = 1)
            Spacer(Modifier.width(4.dp))
            Icon(CosmoIcons.ChevronDown, null, tint = ink, modifier = Modifier.size(16.dp))
        }
        OrbitMenu(open, { open = false }, choices.map { (id, name) -> OrbitMenuItem(name, selected = id == value) { onSelect(id) } }, fonts, look, header = title)
    }
}
