package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.live.cardLabel
import com.cosmotune.ui.live.cricketScore
import com.cosmotune.ui.live.pillDescription
import com.cosmotune.ui.live.pillScore
import com.cosmotune.ui.live.rowTitle
import com.cosmotune.ui.live.short
import com.cosmotune.ui.live.startText
import com.cosmotune.ui.live.watchLine
import com.cosmotune.ui.live.watchNames
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam

/*
 * The "Live now" pieces of the Orbit kit (Canvas repo, design/cosmotune/live-strip/board.html): the Live pill of every mode's top bar,
 * the colour card of the top event in its teams' colours (Watch in its bite only when a free stream carries it), an event as an
 * Orbit row, and a "Coming up" row with its Remind chip. The pill's red is "on air", apart from peach, which stays "playing now".
 */

/** The board's on-air red (`--live`), its dark ground and the pale red of the pill's words in dark. */
internal val ON_AIR = Color(0xFFFF6A5A)
private val ON_AIR_GROUND = Color(0xFF2A1416)
private val ON_AIR_TEXT = Color(0xFFFFB3A8)
private val ON_AIR_INK_LIGHT = Color(0xFFB8322A)
private val ON_AIR_GROUND_LIGHT = Color(0xFFFBE3DE)

/** The colours teams wear, by short name or country, deepened for light ink ([vivid]); anything else by its name. */
private val TEAM_COLOURS = mapOf(
    // internationals, by country
    "in" to 0xFF123A8A, "au" to 0xFFB08A10, "gb" to 0xFF1D2F6A, "en" to 0xFF1D2F6A, "pk" to 0xFF0F6A2A, "za" to 0xFF0E6A4A, "nz" to 0xFF2A2A2A,
    "lk" to 0xFF1F3F8A, "bd" to 0xFF0F6A3A, "af" to 0xFF1F4FA0,
    // IPL
    "CSK" to 0xFFD8A80E, "MI" to 0xFF0B4DA2, "RCB" to 0xFFB0151E, "KKR" to 0xFF3A225D, "SRH" to 0xFFE2591E, "DC" to 0xFF1F3A93,
    "RR" to 0xFFDB2A85, "PBKS" to 0xFFC8102E, "GT" to 0xFF1B2D53, "LSG" to 0xFF2D6CB5,
    // football
    "ARS" to 0xFFDB0007, "CHE" to 0xFF034694, "MCI" to 0xFF4C8FD0, "MUN" to 0xFFDA291C, "LIV" to 0xFFC8102E, "TOT" to 0xFF132257,
    "RMA" to 0xFF3A3A8A, "BAR" to 0xFFA50044, "BFC" to 0xFF0A3A8A, "CFC" to 0xFF1A5FB4, "MBSG" to 0xFF1A7A3A, "KBFC" to 0xFFE0A010,
).mapValues { Color(it.value) }

/** The deep colour of [team] (its country's or its club's), or a stand-in picked by [key] when it has none known. */
fun liveTeamFill(team: LiveTeam?, key: String): Color {
    val known = team?.let { t -> TEAM_COLOURS[t.shortName?.uppercase().orEmpty()] ?: TEAM_COLOURS[t.country.orEmpty()] }
    return vivid((known ?: STAND_INS[Math.floorMod((team?.name ?: key).hashCode(), STAND_INS.size)]).toArgb()).fill
}

/** A flag of three bands for the sides whose flag is three bands; null for the rest (they wear their colour). */
private fun bands(country: String?): List<Color>? = when (country) {
    "in" -> listOf(Color(0xFFFF9933), Color(0xFFFFFFFF), Color(0xFF138808))
    "ie" -> listOf(Color(0xFF169B62), Color(0xFFFFFFFF), Color(0xFFFF883E))
    "de" -> listOf(Color(0xFF000000), Color(0xFFDD0000), Color(0xFFFFCE00))
    else -> null
}

/** A side's small round mark: its logo when the source gives one, its flag of bands, or its colour. */
@Composable
fun LiveTeamMark(team: LiveTeam, key: String, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val stripes = bands(team.country)
    val ground = when {
        stripes != null -> Brush.verticalGradient(0f to stripes[0], 0.333f to stripes[0], 0.334f to stripes[1], 0.666f to stripes[1], 0.667f to stripes[2], 1f to stripes[2])
        else -> Brush.linearGradient(listOf(liveTeamFill(team, key), liveTeamFill(team, key)))
    }
    Box(modifier.size(size).clip(CircleShape).background(ground)) {
        if (!team.logoUrl.isNullOrBlank()) AsyncImage(team.logoUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}

/** The pulsing-free on-air dot of the board (`.dot`): 8 dp of red in a faint ring. */
@Composable
fun OnAirDot(modifier: Modifier = Modifier, size: Dp = 8.dp) {
    Box(modifier.size(size + 8.dp).clip(CircleShape).background(ON_AIR.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
        Box(Modifier.size(size).clip(CircleShape).background(ON_AIR))
    }
}

/**
 * The Live pill (board `.livepill`): the on-air dot, the top event's short score ("IND 245/3 38.2 ov vs AUS", "ARS 2–1 CHE 67'", a
 * show's title) and "+n live" for the others. 40 dp high in a top bar; [stretch] fills the width it is given (the phone's, under its
 * top bar), the count then at the far end. Focusable with a ring; [interactionSource] lets a TV host draw its own.
 */
@Composable
fun OrbitLivePill(
    event: LiveEvent,
    more: Int,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    height: Dp = 40.dp,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val ground = if (look.dark) ON_AIR_GROUND else ON_AIR_GROUND_LIGHT
    val red = if (look.dark) ON_AIR_TEXT else ON_AIR_INK_LIGHT
    val p = pillScore(event)
    val text = fonts.chip.copy(fontSize = 13.5.sp)
    Row(
        modifier.height(height).then(if (interactionSource == null) Modifier.cosmoFocusRing(interaction, height / 2) else Modifier)
            .clip(CircleShape).background(ground).border(1.dp, ON_AIR.copy(alpha = if (look.dark) 0.35f else 0.4f), CircleShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = pillDescription(event, more) }
            .padding(start = 10.dp, end = if (more > 0) 6.dp else 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OnAirDot()
        if (!stretch) OrbitLabel("Live", fonts, red)
        if (p.title != null) {
            Text(p.title, style = text, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = if (stretch) 420.dp else 260.dp).then(if (stretch) Modifier.weight(1f, fill = false) else Modifier))
        } else {
            p.lead?.let { LiveTeamMark(it, event.id, if (stretch) 20.dp else 22.dp) }
            Text(
                buildAnnotatedString {
                    p.lead?.let { append(it.short()) }
                    if (p.score.isNotBlank()) {
                        append(" ")
                        withStyle(SpanStyle(fontFamily = fonts.narrow, fontWeight = FontWeight(900), fontSize = if (stretch) 17.sp else 18.sp)) { append(p.score) }
                    }
                    if (event.kind == LiveEventKind.FOOTBALL) {
                        p.other?.let { append(" ${it.short()}") }
                        p.detail?.let { withStyle(SpanStyle(color = look.dim, fontSize = 12.sp)) { append("  $it") } }
                    } else {
                        p.detail?.let { withStyle(SpanStyle(color = look.dim, fontSize = if (stretch) 11.sp else 12.sp)) { append(" ${if (stretch) it.removeSuffix(" ov") else it}") } }
                        p.other?.let { withStyle(SpanStyle(color = look.ink.copy(alpha = 0.6f))) { append("  vs  ") }; append(it.short()) }
                    }
                },
                style = text, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            if (event.kind != LiveEventKind.FOOTBALL) p.other?.let { LiveTeamMark(it, event.id + "b", if (stretch) 20.dp else 22.dp) }
        }
        if (more > 0) {
            if (stretch) Spacer(Modifier.weight(1f))
            Box(Modifier.height(height - 12.dp).clip(CircleShape).background(ON_AIR.copy(alpha = if (look.dark) 0.18f else 0.14f)).padding(horizontal = 10.dp), contentAlignment = Alignment.Center) {
                Text(if (stretch) "+$more" else "+$more live", style = fonts.chip.copy(fontSize = 12.sp), color = red, maxLines = 1)
            }
        }
    }
}

/** The bite of a live card: 36 dp round, its centre 40 dp in from the corner (the board's mask). */
private val LiveBite = BiteShape(corner = 30.dp, bite = 36.dp, inset = 40.dp)

/**
 * The top event as a colour card (board `.card`): flat in its first team's colour running into the second's, the mono label ("● Live ·
 * 2nd T20I · Australia tour of India"), each side with its mark, name and score (the overs dim beside it), then the status line with
 * the free stream that carries it. Watch stands in the bite only when [onWatch] is given (a free official stream); otherwise there is
 * no bite and the card shows the score alone. A show is its title in the wide face.
 */
@Composable
fun OrbitLiveCard(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook, onWatch: (() -> Unit)?, modifier: Modifier = Modifier) {
    val first = liveTeamFill(event.teams.getOrNull(0), event.id)
    val second = if (event.teams.size > 1) liveTeamFill(event.teams[1], event.id + "b") else first
    val fill = Brush.horizontalGradient(0f to first, 0.55f to first, 1f to second)
    val ink = vivid(first.toArgb()).ink
    val content: @Composable BoxScope.() -> Unit = {
        Box(Modifier.matchParentSize().background(fill))
        Column(Modifier.padding(start = 22.dp, top = 20.dp, end = if (onWatch != null) 92.dp else 22.dp, bottom = 22.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (event.isLive) { Box(Modifier.size(7.dp).clip(CircleShape).background(ON_AIR)); Spacer(Modifier.width(8.dp)) }
                OrbitLabel(cardLabel(event), fonts, ink.copy(alpha = 0.78f))
            }
            Spacer(Modifier.height(12.dp))
            if (event.teams.isEmpty()) {
                Text(event.title, style = fonts.section.copy(fontSize = 22.sp, lineHeight = 26.sp), color = ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    event.teams.take(2).forEachIndexed { i, team ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LiveTeamMark(team, event.id + if (i == 0) "" else "b", 24.dp)
                            Spacer(Modifier.width(10.dp))
                            Text(team.name, style = fonts.title.copy(fontSize = 16.sp), color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                            val score = if (event.kind == LiveEventKind.CRICKET) cricketScore(team.score) else team.score?.let { it to null }
                            if (score != null) {
                                Spacer(Modifier.width(12.dp))
                                Text(score.first, style = fonts.number.copy(fontSize = 30.sp, lineHeight = 30.sp), color = ink, maxLines = 1)
                                score.second?.let { Text("  $it", style = fonts.body.copy(fontSize = 12.5.sp), color = ink.copy(alpha = 0.7f), maxLines = 1) }
                            }
                        }
                    }
                }
            }
            val status = listOfNotNull(event.statusText, watchLine(event)).joinToString(" · ")
            if (status.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(status, style = fonts.body.copy(fontSize = 13.sp), color = ink.copy(alpha = 0.86f), maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
    if (onWatch != null) {
        OrbitBiteCard(Color.Transparent, modifier.fillMaxWidth(), shape = LiveBite, biteInset = 11.dp, onClick = onWatch,
            bite = { OrbitPlayButton(playing = false, onClick = onWatch, look = look, size = 58.dp, modifier = Modifier.semantics { contentDescription = "Watch ${event.title}" }) },
        ) { content() }
    } else {
        Box(modifier.fillMaxWidth().clip(RoundedCornerShape(30.dp))) { content() }
    }
}

/** The tile at the start of an event's row: "LIVE" in red for a show, the competition's letters ("IPL", "PL") in the first team's colour. */
@Composable
private fun LiveEventTile(event: LiveEvent, fonts: OrbitFonts) {
    val mark = when (event.kind) {
        LiveEventKind.SHOW -> "LIVE"
        else -> event.competition?.let { c ->
            c.split(' ').firstOrNull { w -> w.length in 2..4 && w.all { it.isUpperCase() || it.isDigit() } && w.any { it.isLetter() } }
                ?: c.split(' ').filter { it.isNotEmpty() && it.first().isUpperCase() }.joinToString("") { it.take(1) }.take(3)
        }?.takeIf { it.isNotBlank() } ?: event.teams.firstOrNull()?.short() ?: "LIVE"
    }
    val fill = if (event.kind == LiveEventKind.SHOW) Color(0xFFC0302A) else liveTeamFill(event.teams.firstOrNull(), event.id)
    WatchLogoTile(mark, fill, fonts, logoUrl = event.imageUrl.takeIf { event.kind == LiveEventKind.SHOW })
}

/**
 * An event as an Orbit row (board `.r`): its tile, the title ("Arsenal 2 – 1 Chelsea"), the line ("● LIVE · 67' · Premier League ·
 * score only", "Full time · La Liga"), and Watch at the end when a free stream carries it.
 */
@Composable
fun OrbitLiveRow(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook, onWatch: (() -> Unit)?, modifier: Modifier = Modifier) {
    WatchRowFrame(look, modifier, height = 64.dp, onClick = onWatch) {
        LiveEventTile(event, fonts)
        Column(Modifier.weight(1f)) {
            Text(rowTitle(event), style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val rest = listOfNotNull(
                event.statusText.takeIf { event.kind != LiveEventKind.SHOW },
                event.competition ?: watchNames(event).ifBlank { null },
                "score only".takeIf { onWatch == null && event.kind != LiveEventKind.SHOW },
            ).joinToString(" · ")
            if (event.status == LiveEventStatus.LIVE) WatchLiveLine(rest.ifBlank { null }, fonts, look)
            else Text(rest, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onWatch != null) WatchCardChip("Watch", look.ink, fonts, onWatch, icon = CosmoIcons.Play, iconGap = 6.dp)
    }
}

/** A "Coming up" row: the tile, "CSK v MI", "Tomorrow 19:30 · IPL 2027", and Remind (solid once set, "Reminder on"). */
@Composable
fun OrbitLiveComingRow(event: LiveEvent, nowMs: Long, reminded: Boolean, onRemind: (() -> Unit)?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    WatchRowFrame(look, modifier, height = 64.dp) {
        LiveEventTile(event, fonts)
        Column(Modifier.weight(1f)) {
            val a = event.teams.getOrNull(0); val b = event.teams.getOrNull(1)
            Text(if (a != null && b != null) "${a.short()} v ${b.short()}" else event.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(listOfNotNull(startText(event.startMs, nowMs), event.competition).joinToString(" · "), style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp),
                color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemind != null) {
            WatchCardChip(if (reminded) "Reminder on" else "Remind", look.ink, fonts, onRemind, icon = if (reminded) CosmoIcons.BellFill else CosmoIcons.Bell,
                solid = reminded, onSolid = look.ground, iconGap = 6.dp)
        }
    }
}
