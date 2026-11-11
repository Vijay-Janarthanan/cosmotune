package com.cosmotune.ui.replay

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.ic_logo
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.listening.ListeningActivityPanel
import com.cosmotune.ui.orbit.AlbumTitleArt
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.TitleArtAlbum
import com.cosmotune.design.TitleArt
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitChip
import com.cosmotune.ui.orbit.OrbitChipRow
import com.cosmotune.ui.orbit.OrbitLabel
import com.cosmotune.ui.orbit.OrbitLook
import com.cosmotune.ui.orbit.OrbitPageHeading
import com.cosmotune.ui.orbit.OrbitPageSection
import com.cosmotune.ui.orbit.OrbitPillButton
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRankedRow
import com.cosmotune.ui.orbit.OrbitReplayCard
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.OrbitShelfRow
import com.cosmotune.ui.orbit.OrbitStatCard
import com.cosmotune.ui.orbit.coverFill
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import org.jetbrains.compose.resources.painterResource
import kotlin.math.cos
import kotlin.math.sin

/*
 * Replay (the listening stats) in Orbit, for the phone and the desktop alike: the page (its heading and period chips, the hero
 * colour card with Play in its bite, the headline cards, the ranked charts, the stat tiles) and the full-screen story cards (a flat
 * cover-colour ground, the sentence in the wide face, a disc in its orbit). Each host keeps its own data and strings and hands these
 * plain words; nothing here knows the phone's Context or the desktop's summary.
 */

// ---- the page's data ----------------------------------------------------------------------------------------------------------------

/** One line of a Replay chart: its place, the title, the line under it (who, how long) and the plays at its end. */
data class OrbitReplayRow(
    val key: String,
    val rank: Int,
    val title: String,
    val subtitle: String,
    val cover: String?,
    val plays: Int,
)

/** One of the headline cards (the top song, artist, album) as Library's colour card. */
data class OrbitReplayTile(val key: String, val label: String, val value: String, val detail: String?, val cover: String?)

/** One stat tile: the mono [label] and the [value] in the wide face, [line] under it. */
data class OrbitReplayStat(val label: String, val value: String, val line: String? = null)

// ---- the page ----------------------------------------------------------------------------------------------------------------------

/**
 * The page's heading: the mono [label] (the period's name) over "Replay." in the wide face, then the period [chips] with the
 * [selected] one solid. [trailing] stands at the heading's end (the desktop's share).
 */
@Composable
fun OrbitReplayHeading(
    title: String,
    label: String,
    chips: List<String>,
    selected: Int,
    onSelect: (Int) -> Unit,
    side: Dp,
    modifier: Modifier = Modifier,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(top = 28.dp, bottom = 18.dp)) {
        OrbitPageHeading(title, label, fonts, look, Modifier.padding(horizontal = side).semantics { heading() }, trailing = trailing)
        Spacer(Modifier.height(18.dp))
        OrbitChipRow(side) {
            chips.forEachIndexed { index, chip ->
                OrbitChip(chip, fonts, look, onClick = { onSelect(index) }, selected = index == selected, leading = null)
            }
        }
    }
}

/**
 * The hero: one flat colour card in the colours of [cover] (the top track's), the mono [label] over the [value] as a big narrow
 * number, its [unit] and [detail] under it, the cover as a disc in its orbit (at the top end; at the start on a wide card), Play in the bite
 * ([onPlay]: the phone opens the story, the desktop plays the top song). A tap on the card is [onClick].
 */
@Composable
fun OrbitReplayHero(
    label: String,
    value: String,
    unit: String?,
    detail: String?,
    cover: String?,
    playLabel: String,
    onPlay: () -> Unit,
    onClick: () -> Unit,
    side: Dp,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(cover, "replay:hero")
    BoxWithConstraints(modifier.fillMaxWidth().padding(horizontal = side)) {
        // Wide, the disc stands at the start and the words beside it (as an album's card); narrow, the disc is at the top end,
        // above the bite. Either way nothing of the disc reaches the bite.
        val wide = maxWidth >= 560.dp
        val words: @Composable RowScope.() -> Unit = {
            Column(Modifier.weight(1f).fillMaxHeight()) {
                OrbitLabel(label, fonts, ink.copy(alpha = 0.75f))
                Text(
                    value, style = fonts.number.copy(fontSize = if (wide) 96.sp else 66.sp, lineHeight = if (wide) 90.sp else 62.sp),
                    color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp),
                )
                if (!unit.isNullOrBlank()) Text(unit, style = fonts.section, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (!detail.isNullOrBlank()) {
                    Text(detail, style = fonts.body, color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.weight(1f))
                Text(playLabel, style = fonts.chip, color = ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(end = if (wide) 72.dp else 64.dp))
            }
        }
        OrbitBiteCard(fill, Modifier.fillMaxWidth().height(if (wide) 252.dp else 250.dp), onClick = onClick, bite = { OrbitPlayButton(false, onPlay, look) }) {
            Row(Modifier.fillMaxSize().padding(22.dp), verticalAlignment = Alignment.CenterVertically) {
                if (wide) {
                    OrbitStoryDisc(cover, 196.dp, ink, ring = { 0f })
                    Spacer(Modifier.width(28.dp))
                    words()
                } else {
                    words()
                    OrbitStoryDisc(cover, 132.dp, ink, ring = { 0f }, modifier = Modifier.align(Alignment.Top))
                }
            }
        }
    }
}

/** The headline cards as a shelf of Library's Replay cards; a tap on one is [onClick] with its index. */
@Composable
fun OrbitReplayTiles(tiles: List<OrbitReplayTile>, side: Dp, onClick: (Int) -> Unit) {
    if (tiles.isEmpty()) return
    Box(Modifier.padding(top = 14.dp, bottom = 6.dp)) {
        OrbitShelfRow(side, 12.dp) {
            itemsIndexed(tiles, key = { _, tile -> tile.key }) { index, tile ->
                OrbitReplayCard(tile.label, tile.value, tile.detail, tile.cover, onClick = { onClick(index) })
            }
        }
    }
}

/**
 * A chart as three kinds of item (its title, its rows, the gap after it), so a host can work out where a chart starts and land
 * on it. [icon] stands in for a row with no cover (genres).
 */
fun LazyListScope.orbitReplayChart(
    key: String,
    title: String,
    rows: List<OrbitReplayRow>,
    side: Dp,
    onClick: ((Int) -> Unit)?,
    icon: ImageVector? = null,
) {
    if (rows.isEmpty()) return
    item("$key-title") { OrbitPageSection(title, Modifier.padding(horizontal = side).padding(top = 22.dp, bottom = 8.dp)) }
    items(rows, key = { "$key-${it.key}" }) { row ->
        OrbitReplayChartRow(row, Modifier.padding(horizontal = side - 6.dp), icon, onClick?.let { { it(row.rank - 1) } })
    }
    item("$key-gap") { Spacer(Modifier.height(10.dp)) }
}

/** One row of a chart: the kit's ranked row (the place in narrow Archivo, the first in peach), the plays at its end. */
@Composable
fun OrbitReplayChartRow(row: OrbitReplayRow, modifier: Modifier = Modifier, icon: ImageVector? = null, onClick: (() -> Unit)?) {
    OrbitRankedRow(
        row.rank, row.title, row.subtitle, row.cover, orbitFonts(), orbitLook(), onClick, modifier,
        trailingText = row.plays.takeIf { it > 0 }?.toString(), icon = icon.takeIf { row.cover == null },
    )
}

/**
 * The listening's shape: the section [title], the [stats] as quiet stat tiles (all in one row when they fit, short figures three
 * across on a phone; else two across, a row's last tile taking what is left), the [notes] under them, and the daily activity on
 * its own quiet card.
 */
@Composable
fun OrbitReplayStats(
    title: String,
    stats: List<OrbitReplayStat>,
    notes: List<String>,
    dailyMs: Map<String, Long>,
    hourly: List<Long>,
    side: Dp,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = side)) {
        OrbitPageSection(title, Modifier.padding(top = 22.dp, bottom = 12.dp))
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            // A tile's figure is one line of the wide face, so a phone's third of the width holds three figures and no more.
            val short = stats.all { it.value.length <= 3 }
            val columns = when {
                maxWidth >= 600.dp -> stats.size.coerceIn(1, 4)
                short -> stats.size.coerceIn(1, 3)
                else -> 2
            }
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                stats.chunked(columns).forEach { line ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        line.forEach { stat ->
                            OrbitStatCard(stat.label, stat.value, stat.line, fonts, look, Modifier.weight(1f).fillMaxHeight())
                        }
                    }
                }
            }
        }
        notes.forEach { note ->
            Text(note, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 12.dp, start = 4.dp, end = 4.dp))
        }
        Column(Modifier.padding(top = 16.dp).fillMaxWidth().clip(RoundedCornerShape(30.dp)).background(look.chip).padding(20.dp)) {
            // The panel's heat runs in the page's ink rather than the theme's accent: peach is only for what plays.
            MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy(primary = look.ink), typography = MaterialTheme.typography, shapes = MaterialTheme.shapes) {
                ListeningActivityPanel(dailyMs, hourly, textColor = look.ink)
            }
        }
    }
}

/** A quiet line of the page (genres still to come). */
@Composable
fun OrbitReplayNote(text: String, side: Dp, modifier: Modifier = Modifier) {
    Text(text, style = orbitFonts().body, color = orbitLook().dim, modifier = modifier.padding(horizontal = side + 4.dp, vertical = 6.dp))
}

/** The page's last word: Share as the quiet pill, the page's width. */
@Composable
fun OrbitReplayShareButton(label: String, onClick: () -> Unit, side: Dp, modifier: Modifier = Modifier) {
    OrbitPillButton(label, onClick, orbitFonts(), orbitLook(), modifier.padding(horizontal = side).padding(top = 24.dp, bottom = 28.dp).fillMaxWidth(), icon = CosmoIcons.Share, height = 52.dp)
}

/**
 * The share sheet's body, for the Orbit sheet to hold: the mono [overline], the [title] in the wide face and the [description],
 * the [preview] (the picture as it will be sent, nothing over it) in a 9:16 frame, then Save (quiet; a check once [saved]) and
 * Share (the one peach action), both off until [ready].
 */
@Composable
fun OrbitReplayShareBody(
    overline: String,
    title: String,
    description: String,
    saveLabel: String,
    saved: Boolean,
    onSave: () -> Unit,
    shareLabel: String,
    onShare: () -> Unit,
    ready: Boolean,
    modifier: Modifier = Modifier,
    preview: @Composable () -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = 14.dp).padding(top = 4.dp, bottom = 8.dp)) {
        OrbitLabel(overline, fonts, look.dim)
        Text(title, style = fonts.section, color = look.ink, modifier = Modifier.padding(top = 8.dp).semantics { heading() })
        Text(description, style = fonts.body, color = look.dim, modifier = Modifier.padding(top = 4.dp))
        Box(
            Modifier.padding(top = 18.dp).fillMaxWidth(0.46f).align(Alignment.CenterHorizontally).aspectRatio(9f / 16f)
                .clip(RoundedCornerShape(24.dp)).background(look.chip),
            contentAlignment = Alignment.Center,
        ) { preview() }
        Row(Modifier.padding(top = 22.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OrbitPillButton(
                saveLabel, onSave, fonts, look, Modifier.weight(1f), enabled = ready && !saved,
                icon = if (saved) CosmoIcons.Check else CosmoIcons.Download, height = 52.dp,
            )
            OrbitPillButton(shareLabel, onShare, fonts, look, Modifier.weight(1f), primary = true, enabled = ready, icon = CosmoIcons.Share, height = 52.dp)
        }
    }
}

/** A line in place of the share sheet's picture (it could not be drawn), in the body face. */
@Composable
fun OrbitReplayShareNote(text: String) {
    Text(text, style = orbitFonts().body, color = orbitLook().dim, textAlign = TextAlign.Center, modifier = Modifier.padding(16.dp))
}

/** Nothing to show yet: the clock on a quiet disc, the [title] in the wide face and the [body] under it. */
@Composable
fun OrbitReplayEmpty(title: String, body: String, side: Dp, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Column(modifier.fillMaxWidth().padding(horizontal = side + 20.dp, vertical = 48.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(88.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(66.dp).clip(CircleShape).background(look.chip), contentAlignment = Alignment.Center) {
                Icon(CosmoIcons.Clock, contentDescription = null, tint = look.dim, modifier = Modifier.size(28.dp))
            }
            OrbitRing(88.dp, look.ink, progress = { 0f }, spinning = false, stroke = 1.5.dp, moon = 4.dp)
        }
        Text(title, style = fonts.section, color = look.ink, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 18.dp))
        Text(body, style = fonts.body, color = look.dim, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- the stories --------------------------------------------------------------------------------------------------------------------

/** One story card's colours: the flat [fill] of its cover and the pale [ink] that reads on it (deep in both themes). */
class OrbitStoryColors(val fill: Color, val ink: Color) {
    val dim: Color get() = ink.copy(alpha = 0.66f)

    /** The page look turned to the card, so the kit's rows draw in the card's ink. */
    val look: OrbitLook get() = OrbitLook(dark = true, ink = ink, dim = ink.copy(alpha = 0.72f), chip = ink.copy(alpha = 0.14f), row = ink.copy(alpha = 0.08f), accent = ink, ground = fill)
}

/** The colours of the card about [cover]: the stand-in for [key] first, then the cover's own colour fading in. */
@Composable
fun orbitStoryColors(cover: String?, key: String): OrbitStoryColors {
    val (fill, ink) = coverFill(cover, "replay:story:$key")
    return OrbitStoryColors(fill, ink)
}

/** How far the story's furniture ([OrbitStoryChrome]) reaches down a card. */
val ORBIT_STORY_CHROME = 92.dp

/**
 * What stays put while the cards slide under it: the run of segments (the one showing filled to [progress]), the mono [label]
 * with the mark and the name, Share (this card) and the cross.
 */
@Composable
fun OrbitStoryChrome(
    label: String,
    count: Int,
    current: Int,
    progress: Float,
    colors: OrbitStoryColors,
    shareLabel: String,
    onShare: () -> Unit,
    closeLabel: String,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val fonts = orbitFonts()
    Column(modifier.fillMaxWidth().padding(horizontal = 18.dp).padding(top = 12.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            repeat(count) { index ->
                val fraction = when {
                    index < current -> 1f
                    index > current -> 0f
                    else -> progress.coerceIn(0f, 1f)
                }
                Box(Modifier.weight(1f).height(3.dp).clip(CircleShape).background(colors.ink.copy(alpha = 0.26f))) {
                    Box(Modifier.fillMaxWidth(fraction).height(3.dp).clip(CircleShape).background(colors.ink))
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(painterResource(Res.drawable.ic_logo), contentDescription = null, tint = colors.ink, modifier = Modifier.size(width = 24.dp, height = 16.dp))
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text("Cosmotune", style = fonts.title.copy(fontSize = 14.sp, lineHeight = 16.sp), color = colors.ink, maxLines = 1)
                OrbitLabel(label, fonts, colors.dim)
            }
            OrbitRoundButton(CosmoIcons.Share, shareLabel, colors.ink, onShare, onInk = colors.fill)
            Spacer(Modifier.width(8.dp))
            OrbitRoundButton(CosmoIcons.Close, closeLabel, colors.ink, onClose, onInk = colors.fill)
        }
    }
}

/**
 * One story card: a flat [colors] ground, the [headline] (the runs that are true in full ink, the rest dim) in the wide face
 * under the chrome's room, then [body].
 */
@Composable
fun OrbitStoryCard(
    colors: OrbitStoryColors,
    headline: List<Pair<String, Boolean>>,
    modifier: Modifier = Modifier,
    body: @Composable ColumnScope.() -> Unit,
) {
    Column(modifier.fillMaxSize().background(colors.fill).padding(horizontal = 22.dp).padding(top = ORBIT_STORY_CHROME + 8.dp, bottom = 22.dp)) {
        OrbitStoryHeadline(headline, colors)
        Spacer(Modifier.height(18.dp))
        Column(Modifier.weight(1f).fillMaxWidth(), content = body)
    }
}

/** The sentence at the top of a card: one line of the wide face, the emphasised runs in full ink. */
@Composable
fun OrbitStoryHeadline(runs: List<Pair<String, Boolean>>, colors: OrbitStoryColors, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString {
            runs.forEach { (text, bold) -> withStyle(SpanStyle(color = if (bold) colors.ink else colors.ink.copy(alpha = 0.6f))) { append(text) } }
        },
        style = orbitFonts().heroTitle.copy(fontSize = 25.sp, lineHeight = 29.sp),
        modifier = modifier.fillMaxWidth().semantics { heading() },
    )
}

/**
 * A disc in its orbit, [size] across with the ring: the cover (a quiet disc with [icon] when there is none), the faint track and
 * the moon at [ring] (a story's clock; 0 for a still moon at the top).
 */
@Composable
fun OrbitStoryDisc(cover: String?, size: Dp, ink: Color, ring: () -> Float?, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        val disc = size - 22.dp
        if (cover != null) Disc(cover, disc)
        else Box(Modifier.size(disc).clip(CircleShape).background(ink.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            if (icon != null) Icon(icon, contentDescription = null, tint = ink, modifier = Modifier.size(disc * 0.36f))
        }
        OrbitRing(size, ink, progress = ring, spinning = false, stroke = 2.dp, moon = 5.dp)
    }
}

/**
 * The opening cards' system: the first of [covers] as a big disc in its orbit and the rest (songs' covers, artists' faces) as
 * moons on a wider orbit round it, clear of the disc. [footnote] under it.
 */
@Composable
fun ColumnScope.OrbitStoryOrbit(covers: List<String>, colors: OrbitStoryColors, ring: () -> Float?, footnote: String?) {
    Spacer(Modifier.weight(1f))
    BoxWithConstraints(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        val box = minOf(maxWidth, 300.dp)
        Box(Modifier.size(box), contentAlignment = Alignment.Center) {
            OrbitStoryDisc(covers.firstOrNull(), box * 0.62f, colors.ink, ring)
            val outer = box * 0.5f - 30.dp
            OrbitRing(outer * 2, colors.ink, progress = null, spinning = false, stroke = 1.dp, moon = 0.dp)
            val moons = covers.drop(1).take(MOON_ANGLES.size)
            moons.forEachIndexed { index, url ->
                val a = Math.toRadians(MOON_ANGLES[index])
                val moon = if (index % 2 == 0) 56.dp else 46.dp
                Disc(url, moon, Modifier.offset(x = outer * cos(a).toFloat(), y = outer * sin(a).toFloat()))
            }
        }
    }
    Spacer(Modifier.weight(1f))
    if (!footnote.isNullOrBlank()) Text(footnote, style = orbitFonts().body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = colors.dim)
}

/** Where the moons sit on the opening cards' orbit, in degrees from three o'clock, clockwise. */
private val MOON_ANGLES = listOf(-38.0, 152.0, 62.0, -140.0, 108.0)

/**
 * A number one with its runners-up: the [lead] as a disc in its orbit beside its title, who and [leadLine], then [rest] as the
 * kit's ranked rows in the card's ink (inert: a tap turns the page). With [titleArt] (the albums' card) the lead's title is the
 * album's title art, in the plain title's three lines.
 */
@Composable
fun ColumnScope.OrbitStoryLeader(lead: OrbitReplayRow, leadLine: String, rest: List<OrbitReplayRow>, colors: OrbitStoryColors, ring: () -> Float?, titleArt: Boolean = false) {
    val fonts = orbitFonts()
    Row(verticalAlignment = Alignment.CenterVertically) {
        OrbitStoryDisc(lead.cover, 124.dp, colors.ink, ring)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            if (titleArt) {
                AlbumTitleArt(
                    TitleArtAlbum(lead.title, lead.cover, artist = lead.subtitle), ink = colors.ink, ground = colors.fill, modifier = Modifier.fillMaxWidth(),
                    place = TitleArt.Place.FEATURE, maxSize = 30.sp, minSize = 18.sp, maxLines = 3, maxHeight = 75.dp,
                )
            } else {
                Text(lead.title, style = fonts.section.copy(fontSize = 22.sp, lineHeight = 25.sp), color = colors.ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
            if (lead.subtitle.isNotBlank()) Text(lead.subtitle, style = fonts.body, color = colors.dim, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
            OrbitLabel(leadLine, fonts, colors.dim, Modifier.padding(top = 8.dp))
        }
    }
    Spacer(Modifier.weight(1f))
    rest.forEach { row ->
        OrbitRankedRow(row.rank, row.title, row.subtitle, row.cover, fonts, colors.look, onClick = null, modifier = Modifier.offset(x = (-8).dp))
    }
}

/**
 * The genres' card: the top one as a word in the wide face beside a quiet disc in its orbit, [line] under it, then [rest] as
 * ranked rows with the waveform for a disc.
 */
@Composable
fun ColumnScope.OrbitStoryWord(word: String, line: String, rest: List<OrbitReplayRow>, colors: OrbitStoryColors, ring: () -> Float?) {
    val fonts = orbitFonts()
    Spacer(Modifier.weight(1f))
    OrbitStoryDisc(null, 104.dp, colors.ink, ring, icon = CosmoIcons.Waveform)
    Text(word, style = fonts.greeting.copy(fontSize = 46.sp, lineHeight = 48.sp), color = colors.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 16.dp))
    OrbitLabel(line, fonts, colors.dim, Modifier.padding(top = 8.dp))
    Spacer(Modifier.height(18.dp))
    rest.forEach { row ->
        OrbitRankedRow(row.rank, row.title, row.subtitle, null, fonts, colors.look, onClick = null, icon = CosmoIcons.Waveform, modifier = Modifier.offset(x = (-8).dp))
    }
}

/** The habits' card: a disc in its orbit, then each of [facts] (value to label) with the value in the wide face. */
@Composable
fun ColumnScope.OrbitStoryFacts(cover: String?, facts: List<Pair<String, String>>, colors: OrbitStoryColors, ring: () -> Float?) {
    val fonts = orbitFonts()
    OrbitStoryDisc(cover, 112.dp, colors.ink, ring)
    Spacer(Modifier.weight(1f))
    facts.forEach { (value, label) ->
        Column(Modifier.padding(bottom = 20.dp)) {
            Text(value, style = fonts.heroTitle.copy(fontSize = 30.sp, lineHeight = 32.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(label, style = fonts.body, color = colors.dim, modifier = Modifier.padding(top = 2.dp))
        }
    }
}

/** The last card: a disc in its orbit, the [lines] (label to value) of the whole Replay, and [footnote]. */
@Composable
fun ColumnScope.OrbitStoryRecap(cover: String?, lines: List<Pair<String, String>>, footnote: String, colors: OrbitStoryColors, ring: () -> Float?) {
    val fonts = orbitFonts()
    OrbitStoryDisc(cover, 112.dp, colors.ink, ring)
    Spacer(Modifier.height(14.dp))
    lines.forEach { (label, value) ->
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OrbitLabel(label, fonts, colors.dim, Modifier.width(112.dp))
            Text(value, style = fonts.title.copy(fontSize = 16.sp), color = colors.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        }
    }
    Spacer(Modifier.weight(1f))
    Text(footnote, style = fonts.body.copy(fontSize = 15.sp, lineHeight = 21.sp), color = colors.dim)
}
