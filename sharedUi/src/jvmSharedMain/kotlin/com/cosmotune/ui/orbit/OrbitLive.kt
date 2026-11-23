package com.cosmotune.ui.orbit

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.live.ScorerLine
import com.cosmotune.ui.live.agoText
import com.cosmotune.ui.live.comingLine
import com.cosmotune.ui.live.cricketScore
import com.cosmotune.ui.live.initials
import com.cosmotune.ui.live.outletsLine
import com.cosmotune.ui.live.outletsTag
import com.cosmotune.ui.live.scorerLines
import com.cosmotune.ui.live.stripDescription
import com.cosmotune.ui.live.stripLine
import com.cosmotune.ui.live.surname
import com.cosmotune.ui.live.watchLine
import com.cosmotune.watch.live.SpotlightClock
import com.cosmotune.watch.model.CricketDetail
import com.cosmotune.watch.model.LineupPlayer
import com.cosmotune.watch.model.Lineup
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.LiveEventKind
import com.cosmotune.watch.model.LiveEventStatus
import com.cosmotune.watch.model.LiveTeam
import com.cosmotune.watch.model.StatPair
import com.cosmotune.watch.model.TimelineEntry
import com.cosmotune.watch.model.TimelineKind

/*
 * The Spotlight's pieces of the Orbit kit (Canvas repo, design/cosmotune/search-spotlight/board.html, section 4): the strip of every
 * mode, a match as a card (football with crests, score, scorers and cards; cricket with each side's runs and overs), a story as a row
 * with its picture and outlets, a coming match or event as a row with Remind, and a match's detail (timeline, stats, line-ups,
 * scorecard). The live red is "on air" and only a match in play wears it; a story never does.
 */

/** The board's on-air red (`--live`), deep for light ink and pale in dark. */
private val LIVE_DARK = Color(0xFFFF6B55)
private val LIVE_LIGHT = Color(0xFFC8341F)
private val YELLOW_CARD = Color(0xFFF2C230)
private val RED_CARD = Color(0xFFD7301F)

private fun spotlightRed(look: OrbitLook): Color = if (look.dark) LIVE_DARK else LIVE_LIGHT

/** The card's ground of the board (`.match`): a quiet fill with a hairline edge, radius 18. */
private fun Modifier.spotlightCard(look: OrbitLook): Modifier {
    val shape = RoundedCornerShape(18.dp)
    return clip(shape).background(look.row).border(1.dp, look.ink.copy(alpha = if (look.dark) 0.10f else 0.09f), shape)
}

/** The board's live dot: 7 dp of red. */
@Composable
fun SpotlightLiveDot(look: OrbitLook, modifier: Modifier = Modifier) {
    Box(modifier.size(7.dp).clip(CircleShape).background(spotlightRed(look)))
}

// ---- the strip ---------------------------------------------------------------------------------------------------------------

/**
 * The strip (board `.strip`): one line of the single most important thing now: a match in play with its minute and latest scorer
 * ("● 67' Arsenal 2–1 Chelsea · Saka 54'"), else the biggest world story ("WORLD Climate talks end without a deal"), and "+n" for
 * the rest of the sheet. In the accent's tint with its edge; the dot only while a match is in play. [stretch] fills the width it is
 * given (the phone, under its top bar); the desktop's sits in its top bar at [maxWidth].
 */
@Composable
fun OrbitSpotlightStrip(
    item: LiveEvent?,
    more: Int,
    nowMs: Long,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stretch: Boolean = false,
    height: Dp = 40.dp,
    maxWidth: Dp = 460.dp,
    interactionSource: MutableInteractionSource? = null,
) {
    val interaction = interactionSource ?: remember { MutableInteractionSource() }
    val line = item?.let { stripLine(it, nowMs) }
    val accent = look.accent
    Row(
        modifier.height(height).then(if (stretch) Modifier.fillMaxWidth() else Modifier.widthIn(max = maxWidth))
            .then(if (interactionSource == null) Modifier.cosmoFocusRing(interaction, height / 2) else Modifier)
            .clip(CircleShape).background(accent.copy(alpha = 0.10f)).border(1.5.dp, accent.copy(alpha = 0.55f), CircleShape)
            .clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = stripDescription(item, more, nowMs) }
            .padding(start = 12.dp, end = if (more > 0) 8.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (line?.inPlay == true) SpotlightLiveDot(look)
        Text((line?.label ?: "Spotlight").uppercase(), style = fonts.label.copy(fontSize = 10.5.sp), color = accent, maxLines = 1)
        Text(line?.text ?: "News and scores", style = fonts.chip.copy(fontSize = 13.sp, fontWeight = FontWeight(600)), color = look.ink, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = stretch))
        if (more > 0) {
            Box(Modifier.clip(CircleShape).background(accent.copy(alpha = 0.20f)).padding(horizontal = 8.dp, vertical = 3.dp)) {
                Text("+$more", style = fonts.label.copy(fontSize = 11.sp, letterSpacing = 0.5.sp), color = accent, maxLines = 1)
            }
        }
    }
}

// ---- marks ------------------------------------------------------------------------------------------------------------------

/** A side's crest from the source, or its initials on its own colour when there is none. */
@Composable
fun SpotlightCrest(team: LiveTeam, key: String, size: Dp = 22.dp, modifier: Modifier = Modifier) {
    val fill = team.colour?.let { hex -> runCatching { Color(("FF" + hex.removePrefix("#").take(6).padStart(6, '0')).toLong(16)) }.getOrNull() }
        ?.let { vivid(it.toArgb()).fill }
        ?: STAND_INS[Math.floorMod((team.name + key).hashCode(), STAND_INS.size)]
    if (!team.logoUrl.isNullOrBlank()) {
        Box(modifier.size(size), contentAlignment = Alignment.Center) {
            AsyncImage(team.logoUrl, contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.size(size))
        }
    } else {
        Box(modifier.size(size).clip(CircleShape).background(fill), contentAlignment = Alignment.Center) {
            Text(initials(team).take(3), style = androidx.compose.ui.text.TextStyle(fontWeight = FontWeight(800), fontSize = (size.value * 0.36f).sp), color = Color.White, maxLines = 1)
        }
    }
}

/** The minute pill (board `.min`): "● 67'" in the live red while in play; "Half-time", "Full time" quiet. */
@Composable
fun SpotlightPill(text: String, inPlay: Boolean, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    val red = spotlightRed(look)
    Row(
        modifier.clip(CircleShape).background(if (inPlay) red.copy(alpha = 0.14f) else look.ink.copy(alpha = 0.08f)).padding(horizontal = 8.dp, vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
    ) {
        if (inPlay) SpotlightLiveDot(look)
        Text(text, style = fonts.label.copy(fontSize = 11.sp, letterSpacing = 0.3.sp), color = if (inPlay) red else look.ink, maxLines = 1)
    }
}

/** A goal (a ball), a yellow card or a red card, drawn small. */
@Composable
private fun IncidentMark(goal: Boolean, red: Boolean, look: OrbitLook) {
    if (goal) Box(Modifier.size(9.dp).clip(CircleShape).border(1.5.dp, look.ink.copy(alpha = 0.7f), CircleShape))
    else Box(Modifier.size(width = 7.dp, height = 10.dp).clip(RoundedCornerShape(1.5.dp)).background(if (red) RED_CARD else YELLOW_CARD))
}

// ---- match cards ------------------------------------------------------------------------------------------------------------

/**
 * A match as a card (board `.match`). Football: the competition and the minute pill, each side's crest, full name and score, then
 * the scorers with their minutes under their side and the cards; a goal that has just come in glows for a moment where it lands.
 * Cricket: the format and day, each side's runs and wickets with the overs under them, the status line and the batters in. Tap opens
 * its detail; Watch shows when a free stream carries it.
 */
@Composable
fun SpotlightMatchCard(event: LiveEvent, nowMs: Long, fonts: OrbitFonts, look: OrbitLook, onClick: (() -> Unit)?, onWatch: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Column(
        modifier.fillMaxWidth().cosmoFocusRing(interaction, 18.dp).spotlightCard(look)
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick) else Modifier)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        val pill = SpotlightClock.pill(event, nowMs) ?: if (event.status == LiveEventStatus.UPCOMING) com.cosmotune.ui.live.startText(event.startMs, nowMs) else null
        Row(verticalAlignment = Alignment.CenterVertically) {
            val head = if (event.kind == LiveEventKind.CRICKET) listOfNotNull(event.format, event.competition).joinToString(" · ") else event.competition.orEmpty()
            Text(head, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (pill != null) { Spacer(Modifier.width(8.dp)); SpotlightPill(pill, SpotlightClock.inPlay(event), fonts, look) }
        }
        if (event.kind == LiveEventKind.CRICKET) CricketSides(event, fonts, look) else FootballSides(event, fonts, look)
        val watch = watchLine(event)
        if (onWatch != null && watch != null) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                Text(watch, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(12.dp))
                WatchCardChip("Watch", look.ink, fonts, onWatch, icon = CosmoIcons.Play, iconGap = 6.dp)
            }
        }
    }
}

@Composable
private fun TeamLine(team: LiveTeam, key: String, fonts: OrbitFonts, look: OrbitLook, size: Float = 14f, trailing: @Composable RowScope.() -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        SpotlightCrest(team, key, (size + 8).dp)
        Spacer(Modifier.width(8.dp))
        Text(team.name, style = fonts.title.copy(fontSize = size.sp, lineHeight = (size + 4).sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f))
        Spacer(Modifier.width(8.dp))
        trailing()
    }
}

@Composable
private fun FootballSides(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook, size: Float = 14f) {
    event.teams.take(2).forEachIndexed { i, team ->
        TeamLine(team, event.id + i, fonts, look, size) {
            team.score?.let { Text(it, style = fonts.number.copy(fontSize = (size + 6).sp, lineHeight = (size + 8).sp), color = look.ink, maxLines = 1) }
        }
    }
    ScorerGrid(event, fonts, look)
}

/** The scorers and cards under a football card in two columns, each side under its own; a line just in glows for four seconds. */
@Composable
private fun ScorerGrid(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook) {
    val lines = scorerLines(event)
    if (lines.isEmpty()) return
    val keys = lines.filter { it.goal }.map { "${it.side}|${it.text}" }.toSet()
    var known by remember(event.id) { mutableStateOf<Set<String>?>(null) }
    val fresh = known?.let { k -> keys - k }.orEmpty()
    val glow = remember(event.id) { Animatable(0f) }
    LaunchedEffect(keys) {
        if (known != null && fresh.isNotEmpty()) { glow.snapTo(1f); glow.animateTo(0f, tween(4_000)) }
        known = keys
    }
    val style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        for (side in 0..1) {
            Column(Modifier.weight(1f), horizontalAlignment = if (side == 0) Alignment.Start else Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                lines.filter { it.side == side }.forEach { l: ScorerLine ->
                    val lit = "${l.side}|${l.text}" in fresh
                    Row(
                        Modifier.clip(RoundedCornerShape(6.dp)).background(look.accent.copy(alpha = if (lit) 0.28f * glow.value else 0f)).padding(horizontal = 2.dp),
                        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        if (side == 0) IncidentMark(l.goal, l.red, look)
                        Text(l.text, style = style, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = if (side == 0) TextAlign.Start else TextAlign.End)
                        if (side == 1) IncidentMark(l.goal, l.red, look)
                    }
                }
            }
        }
    }
}

@Composable
private fun CricketSides(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook) {
    event.teams.take(2).forEachIndexed { i, team ->
        TeamLine(team, event.id + i, fonts, look) {
            val innings = team.score?.split(" & ").orEmpty()
            val now = cricketScore(innings.lastOrNull())
            if (now != null) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(now.first, style = fonts.title.copy(fontSize = 14.sp), color = if (team.batting || !event.isLive) look.ink else look.ink.copy(alpha = 0.8f), maxLines = 1)
                    val small = listOfNotNull(now.second, innings.dropLast(1).lastOrNull()?.let { "1st inns $it" }).joinToString(" · ")
                    if (small.isNotEmpty()) Text(small, style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = look.dim, maxLines = 1)
                }
            }
        }
    }
    val status = listOfNotNull(event.statusText, event.batting).joinToString(" · ")
    if (status.isNotBlank()) Text(status, style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp, fontWeight = FontWeight(600)), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

// ---- rows -------------------------------------------------------------------------------------------------------------------

/** A story (board `.news`): its picture, the headline, and "5 OUTLETS · BBC · Guardian · Al Jazeera · 40 min". Opens the article. */
@Composable
fun SpotlightNewsRow(event: LiveEvent, nowMs: Long, fonts: OrbitFonts, look: OrbitLook, onClick: (() -> Unit)?, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier.fillMaxWidth().cosmoFocusRing(interaction, 12.dp).clip(RoundedCornerShape(12.dp))
            .then(if (onClick != null) Modifier.clickable(interactionSource = interaction, indication = androidx.compose.foundation.LocalIndication.current, role = Role.Button, onClick = onClick) else Modifier)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 64.dp, height = 48.dp).clip(RoundedCornerShape(10.dp)).background(look.ink.copy(alpha = 0.10f))) {
            if (!event.imageUrl.isNullOrBlank()) AsyncImage(event.imageUrl, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(event.title, style = fonts.title.copy(fontSize = 13.5.sp, lineHeight = 17.sp), color = look.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                outletsTag(event)?.let { tag ->
                    Box(Modifier.clip(RoundedCornerShape(6.dp)).background(look.accent.copy(alpha = 0.16f)).padding(horizontal = 6.dp, vertical = 1.dp)) {
                        Text(tag, style = fonts.label.copy(fontSize = 10.sp, letterSpacing = 0.8.sp), color = look.accent, maxLines = 1)
                    }
                    Spacer(Modifier.width(6.dp))
                }
                Text(outletsLine(event, nowMs), style = fonts.body.copy(fontSize = 11.5.sp, lineHeight = 14.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * A coming match or event: the match with its two crests, or the event's day tile; its line ("Champions League · Emirates Stadium ·
 * Today 20:00 · in 2 h", "Spain · Sun 29 Nov · in 6 days"); Remind for a match (solid once set), an event opens its page.
 */
@Composable
fun SpotlightComingRow(event: LiveEvent, nowMs: Long, reminded: Boolean, onRemind: (() -> Unit)?, onClick: (() -> Unit)?, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    WatchRowFrame(look, modifier, height = 60.dp, onClick = onClick) {
        val a = event.teams.getOrNull(0); val b = event.teams.getOrNull(1)
        Box(Modifier.size(46.dp).clip(RoundedCornerShape(14.dp)).background(look.chip), contentAlignment = Alignment.Center) {
            if (a != null && b != null) {
                SpotlightCrest(a, event.id, 20.dp, Modifier.align(Alignment.TopStart).padding(start = 5.dp, top = 5.dp))
                SpotlightCrest(b, event.id + "b", 20.dp, Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 5.dp))
            } else {
                val day = event.startMs?.let { com.cosmotune.ui.live.dayText(it).split(' ') }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(day?.getOrNull(1) ?: "?", style = fonts.number.copy(fontSize = 18.sp, lineHeight = 18.sp), color = look.ink)
                    Text((day?.getOrNull(2) ?: "").uppercase(), style = fonts.label.copy(fontSize = 9.sp), color = look.dim)
                }
            }
        }
        Column(Modifier.weight(1f)) {
            Text(if (a != null && b != null) "${a.name} v ${b.name}" else event.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(comingLine(event, nowMs), style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (onRemind != null) {
            WatchCardChip(if (reminded) "Reminder on" else "Remind", look.ink, fonts, onRemind, icon = if (reminded) CosmoIcons.BellFill else CosmoIcons.Bell,
                solid = reminded, onSolid = look.ground, iconGap = 6.dp)
        }
    }
}

/** A live show (a DD or official YouTube special) as a row: LIVE in red, its title, its channel; Watch when it can be played. */
@Composable
fun SpotlightShowRow(event: LiveEvent, fonts: OrbitFonts, look: OrbitLook, onWatch: (() -> Unit)?, modifier: Modifier = Modifier) {
    WatchRowFrame(look, modifier, height = 60.dp, onClick = onWatch) {
        WatchLogoTile("LIVE", Color(0xFFC0302A), fonts, logoUrl = event.imageUrl)
        Column(Modifier.weight(1f)) {
            Text(event.title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            WatchLiveLine(com.cosmotune.ui.live.watchNames(event).ifBlank { null }, fonts, look)
        }
        if (onWatch != null) WatchCardChip("Watch", look.ink, fonts, onWatch, icon = CosmoIcons.Play, iconGap = 6.dp)
    }
}

// ---- a match's detail -------------------------------------------------------------------------------------------------------

/** The head of a match's detail (board, third sheet): competition and venue with the pill, then each side bigger. */
@Composable
fun SpotlightDetailHead(event: LiveEvent, nowMs: Long, fonts: OrbitFonts, look: OrbitLook) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(listOfNotNull(if (event.kind == LiveEventKind.CRICKET) event.format else null, event.competition, event.venue).joinToString(" · "),
                style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            (SpotlightClock.pill(event, nowMs) ?: com.cosmotune.ui.live.startText(event.startMs, nowMs).takeIf { event.status == LiveEventStatus.UPCOMING })?.let {
                Spacer(Modifier.width(8.dp)); SpotlightPill(it, SpotlightClock.inPlay(event), fonts, look)
            }
        }
        if (event.kind == LiveEventKind.CRICKET) CricketSides(event, fonts, look) else FootballSides(event, fonts, look, size = 16f)
    }
}

/** The timeline (board `.timeline`): minute, then the goal with its assist, the card, the change; HALF-TIME and FULL TIME as dividers. */
@Composable
fun SpotlightTimeline(entries: List<TimelineEntry>, teams: List<LiveTeam>, fonts: OrbitFonts, look: OrbitLook) {
    val style = fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        entries.forEach { e ->
            if (e.kind == TimelineKind.HALF_TIME || e.kind == TimelineKind.FULL_TIME) {
                Box(Modifier.fillMaxWidth().padding(top = 2.dp).height(1.dp).background(look.ink.copy(alpha = 0.12f)))
                Text("${if (e.kind == TimelineKind.HALF_TIME) "HALF-TIME" else "FULL TIME"} ${e.text.orEmpty()}".trim(), style = fonts.label.copy(fontSize = 10.5.sp),
                    color = look.dim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                return@forEach
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(e.minute, style = fonts.label.copy(fontSize = 11.sp, letterSpacing = 0.3.sp), color = look.dim, modifier = Modifier.width(44.dp), textAlign = TextAlign.End, maxLines = 1)
                Spacer(Modifier.width(10.dp))
                when (e.kind) {
                    TimelineKind.YELLOW, TimelineKind.RED -> IncidentMark(goal = false, red = e.kind == TimelineKind.RED, look = look)
                    TimelineKind.SUB -> Text("⇄", style = style, color = look.dim)
                    else -> IncidentMark(goal = true, red = false, look = look)
                }
                Spacer(Modifier.width(8.dp))
                val side = e.side?.let { teams.getOrNull(it)?.name }
                val words = when (e.kind) {
                    TimelineKind.GOAL -> listOfNotNull(e.player?.let(::surname), e.other?.let { "assist ${surname(it)}" }, side).joinToString(" · ")
                    TimelineKind.PENALTY_GOAL -> listOfNotNull(e.player?.let { "${surname(it)} (penalty)" }, side).joinToString(" · ")
                    TimelineKind.OWN_GOAL -> listOfNotNull(e.player?.let { "${surname(it)} (own goal)" }, side).joinToString(" · ")
                    TimelineKind.MISSED_PENALTY -> listOfNotNull(e.player?.let { "${surname(it)} misses a penalty" }, side).joinToString(" · ")
                    TimelineKind.SUB -> listOfNotNull(e.player?.let { "${surname(it)} on" }, e.other?.let { "${surname(it)} off" }, side).joinToString(" · ")
                    else -> listOfNotNull(e.player?.let(::surname), side).joinToString(" · ")
                }
                Text(words, style = style.copy(fontWeight = if (e.kind == TimelineKind.GOAL || e.kind == TimelineKind.PENALTY_GOAL || e.kind == TimelineKind.OWN_GOAL) FontWeight(700) else FontWeight(500)),
                    color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
    }
}

/** The stats as paired bars (board `.stat`): the label over, each side's figure at its end, the home side's share of the bar in ink. */
@Composable
fun SpotlightStatBars(stats: List<StatPair>, fonts: OrbitFonts, look: OrbitLook) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        stats.forEach { s ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(s.label, style = fonts.body.copy(fontSize = 11.sp, lineHeight = 13.sp), color = look.dim, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.home, style = fonts.body.copy(fontSize = 12.sp), color = look.ink, modifier = Modifier.width(40.dp), maxLines = 1)
                    Row(Modifier.weight(1f).height(6.dp).clip(CircleShape).background(look.ink.copy(alpha = 0.12f))) {
                        val share = s.homeShare.coerceIn(0f, 1f)
                        if (share > 0f) Box(Modifier.weight(share).fillMaxHeight().background(look.ink))
                        if (share < 1f) Spacer(Modifier.weight(1f - share))
                    }
                    Text(s.away, style = fonts.body.copy(fontSize = 12.sp), color = look.ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.End, maxLines = 1)
                }
            }
        }
    }
}

/** The line-ups: each side's formation, its starters with their numbers, and those who came on. */
@Composable
fun SpotlightLineups(lineups: List<Lineup>, fonts: OrbitFonts, look: OrbitLook) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        lineups.forEach { l ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OrbitLabel(listOfNotNull(l.team, l.formation).joinToString(" · "), fonts, look.dim)
                Spacer(Modifier.height(4.dp))
                l.starters.forEach { PlayerLine(it, fonts, look) }
                val on = l.bench.filter { it.subbedIn }
                if (on.isNotEmpty()) {
                    Text("Came on", style = fonts.body.copy(fontSize = 11.5.sp), color = look.dim, modifier = Modifier.padding(top = 6.dp))
                    on.forEach { PlayerLine(it, fonts, look) }
                }
            }
        }
    }
}

@Composable
private fun PlayerLine(p: LineupPlayer, fonts: OrbitFonts, look: OrbitLook) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(p.number.orEmpty(), style = fonts.label.copy(fontSize = 11.sp), color = look.dim, modifier = Modifier.width(28.dp), maxLines = 1)
        Text(p.name, style = fonts.body.copy(fontSize = 13.sp), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        val mark = listOfNotNull(p.position, "off".takeIf { p.subbedOut }).joinToString(" · ")
        Text(mark, style = fonts.body.copy(fontSize = 11.5.sp), color = look.dim, maxLines = 1)
    }
}

/** A cricket scorecard: each innings (latest first) with its batting, bowling and partnerships, then the last balls and the notes. */
@Composable
fun SpotlightScorecard(detail: CricketDetail, fonts: OrbitFonts, look: OrbitLook) {
    val small = fonts.body.copy(fontSize = 12.sp, lineHeight = 16.sp)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (detail.recentBalls.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OrbitLabel("Last balls", fonts, look.dim)
                Spacer(Modifier.height(4.dp))
                detail.recentBalls.take(8).forEach { Text(it, style = small, color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        detail.innings.sortedByDescending { it.number }.forEach { inn ->
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OrbitLabel(listOfNotNull("Innings ${inn.number}", inn.team.ifBlank { null }, inn.total).joinToString(" · "), fonts, look.dim)
                if (inn.batting.isNotEmpty()) {
                    Spacer(Modifier.height(4.dp))
                    TableRow(listOf("Batting", "R", "B", "4s", "6s"), fonts, look, head = true)
                    inn.batting.forEach { b ->
                        TableRow(listOf(b.name + if (b.notOut) "*" else "", b.runs, b.balls, b.fours, b.sixes), fonts, look, sub = b.dismissal.takeUnless { b.notOut || it.isBlank() })
                    }
                    inn.extras?.let { Text("Extras $it", style = small, color = look.dim) }
                }
                if (inn.bowling.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    TableRow(listOf("Bowling", "O", "M", "R", "W"), fonts, look, head = true)
                    inn.bowling.forEach { b -> TableRow(listOf(b.name, b.overs, b.maidens, b.runs, b.wickets), fonts, look) }
                }
                if (inn.partnerships.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text("Partnerships", style = small.copy(fontWeight = FontWeight(700)), color = look.ink)
                    inn.partnerships.forEach { p ->
                        Text("${p.wicket} · ${p.runs} (${p.overs} ov) · ${p.first}, ${p.second}", style = small, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        if (detail.notes.isNotEmpty()) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OrbitLabel("Notes", fonts, look.dim)
                Spacer(Modifier.height(4.dp))
                detail.notes.takeLast(5).forEach { Text(it, style = small, color = look.dim) }
            }
        }
    }
}

@Composable
private fun TableRow(cells: List<String>, fonts: OrbitFonts, look: OrbitLook, head: Boolean = false, sub: String? = null) {
    val style = if (head) fonts.label.copy(fontSize = 10.sp) else fonts.body.copy(fontSize = 12.5.sp, lineHeight = 16.sp)
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(cells.first(), style = style, color = if (head) look.dim else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            cells.drop(1).forEach { c -> Text(c, style = style, color = if (head) look.dim else look.ink, maxLines = 1, textAlign = TextAlign.End, modifier = Modifier.width(34.dp)) }
        }
        if (sub != null) Text(sub, style = fonts.body.copy(fontSize = 11.sp, lineHeight = 13.sp), color = look.dim, maxLines = 1)
    }
}

/** "Updated 12 s ago" and, when a live match's data is old, "Scores may be behind". */
@Composable
fun SpotlightFreshness(updated: String?, behind: Boolean, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier) {
    Column(modifier) {
        if (updated != null) Text(updated, style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1)
        if (behind) Text("Scores may be behind", style = fonts.body.copy(fontSize = 12.sp, lineHeight = 15.sp), color = look.dim, maxLines = 1)
    }
}
