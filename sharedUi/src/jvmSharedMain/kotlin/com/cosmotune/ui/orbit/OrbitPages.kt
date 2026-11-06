package com.cosmotune.ui.orbit

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.BlockMetrics
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.DownloadedBadge
import com.cosmotune.music.ui.components.ExplicitSongTitle
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.pages.rememberMotionAllowed
import kotlin.math.abs

/*
 * The pieces the album, playlist and artist pages of both hosts are built from (Canvas repo, design/cosmotune/pages-orbit): the
 * page's one colour card, with the cover as a big disc and Play in its bite; the song row of a list; the section heading. Public,
 * because the desktop host draws its pages from them too.
 */

/** The two colours of a page's card: its deep [fill] and the pale [ink] that reads on it. */
class OrbitCardColors(val fill: Color, val ink: Color)

/** A round button on a page's card (shuffle, save, download, more): the card's ink at 12 %, or solid ink when [on]. */
@Composable
fun OrbitCardButton(icon: ImageVector, label: String, colors: OrbitCardColors, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) =
    OrbitRoundButton(icon, label, colors.ink, onClick, modifier, on = on, onInk = colors.fill)

/** A round button on the page itself (find in the list): the page's ink at 12 %, solid ink when [on]. */
@Composable
fun OrbitPageButton(icon: ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false) {
    val look = orbitLook()
    OrbitRoundButton(icon, label, look.ink, onClick, modifier, on = on, onInk = look.ground)
}

/** A pill on a page's card (Following, Radio, Shuffle): solid ink with the card's colour for its words when [solid], else tonal. */
@Composable
fun OrbitCardChip(text: String, colors: OrbitCardColors, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, solid: Boolean = false) {
    val fonts = orbitFonts()
    val content = if (solid) colors.fill else colors.ink
    Row(
        modifier.height(40.dp).clip(CircleShape).background(if (solid) colors.ink else colors.ink.copy(alpha = 0.12f)).clickable(onClick = onClick)
            .padding(start = if (icon != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = fonts.chip, color = content, maxLines = 1)
    }
}

/**
 * An album's or a playlist's card (board section 1): one flat card in the cover's colour with the cover as a big disc, the mono
 * [label] ("ALBUM · 12 SONGS · 48 MIN"), the [title], who made it ([credit]; a tap is [onCredit]), [below] (the quality the
 * catalogue reports) and the round [buttons]; Play stands in the bite. From 600 dp the disc stands at the start and the words beside
 * it; narrower, the disc is on top. When [motion] is given the album's animated cover loops silently inside the disc, under the
 * same rules as the player's.
 */
@Composable
fun OrbitReleaseCard(
    cover: String?,
    label: String,
    title: String,
    credit: String?,
    onCredit: (() -> Unit)?,
    playing: Boolean,
    onPlay: (() -> Unit)?,
    modifier: Modifier = Modifier,
    motion: CanvasArtwork? = null,
    below: @Composable ColumnScope.(OrbitCardColors) -> Unit = {},
    buttons: @Composable RowScope.(OrbitCardColors) -> Unit,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val (fill, ink) = coverFill(cover, title)
    val colors = OrbitCardColors(fill, ink)
    val allowed = motion != null && rememberMotionAllowed()
    BoxWithConstraints(modifier) {
        val wide = maxWidth >= 600.dp
        val cardWidth = maxWidth
        OrbitBiteCard(fill, Modifier.fillMaxWidth(), bite = {
            if (onPlay != null) OrbitPlayButton(playing, onPlay, look, Modifier.testTag("orbit:card:play"))
        }) {
            val words: @Composable ColumnScope.() -> Unit = {
                Text(label.uppercase(), style = fonts.label, color = ink.copy(alpha = 0.75f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    title, style = if (wide) fonts.greeting.copy(fontSize = 40.sp, lineHeight = 42.sp) else fonts.heroTitle.copy(fontSize = 28.sp, lineHeight = 30.sp),
                    color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 12.dp).semantics { heading() },
                )
                if (!credit.isNullOrBlank()) {
                    Text(
                        credit, style = fonts.body.copy(fontSize = 15.sp, fontWeight = FontWeight(600)), color = ink.copy(alpha = 0.85f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp).clip(RoundedCornerShape(10.dp))
                            .then(if (onCredit != null) Modifier.clickable(onClick = onCredit) else Modifier).padding(vertical = 6.dp),
                    )
                }
                below(colors)
                Row(Modifier.padding(top = 18.dp, end = if (wide) 0.dp else 76.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    buttons(colors)
                }
            }
            if (wide) {
                Row(Modifier.fillMaxWidth().heightIn(min = 250.dp).padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    OrbitMotionDisc(cover, 202.dp, motion, allowed)
                    Column(Modifier.weight(1f).padding(start = 32.dp, end = 72.dp), content = words)
                }
            } else {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    val disc = (cardWidth - 96.dp).coerceIn(160.dp, 232.dp)
                    OrbitMotionDisc(cover, disc, motion, allowed, Modifier.align(Alignment.CenterHorizontally))
                    Spacer(Modifier.height(20.dp))
                    words()
                }
            }
        }
    }
}

/** A section of a page: the title in wide type, its strap line, and [action] at its end ("Show all"). */
@Composable
fun OrbitPageSection(title: String, modifier: Modifier = Modifier, subtitle: String = "", action: String? = null, onAction: (() -> Unit)? = null) {
    val fonts = orbitFonts()
    val look = orbitLook()
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        OrbitSectionHeader(title, subtitle, fonts, look, Modifier.weight(1f).semantics { heading() })
        if (action != null && onAction != null) {
            Text(action, style = fonts.chip, color = look.accent, modifier = Modifier.clip(CircleShape).clickable(onClick = onAction).padding(horizontal = 12.dp, vertical = 8.dp))
        }
    }
}

/**
 * A song of an album or a playlist as Home's row: the round cover, the title (with its explicit mark) over [subtitle], the duration
 * at the end. The playing row ([now]) is peach with the orbit round its disc. A song still being matched shows a spinner for its
 * disc; one with no match is dimmed and does nothing. [selected] (not null while choosing) puts a check in the disc's place;
 * [showQuality] leads the second line with what the catalogue offers; [downloadedTint] marks a downloaded song. [onSwipe] lets a
 * swipe either way queue it. [trailing] replaces the end of the row (the desktop's hover actions and duration).
 */
@Composable
fun OrbitTrackRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    onSwipe: (() -> Unit)? = null,
    cover: String? = song.thumbnailUrl,
    subtitle: String = song.artist,
    selected: Boolean? = null,
    showQuality: Boolean = false,
    downloadedTint: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val fonts = orbitFonts()
    val look = orbitLook()
    val pending = song.isMatchPending
    val missing = song.isMatchMissing
    val quality = if (showQuality) song.catalogueQualityBadge() else null
    val row: @Composable (Modifier) -> Unit = { m ->
        OrbitSongRow(
            title = song.title, subtitle = subtitle, cover = cover, fonts = fonts, look = look, onClick = onClick,
            modifier = m, now = now, playing = playing,
            onLongClick = onLongPress.takeUnless { pending || missing },
            enabled = !missing,
            titleContent = { ExplicitSongTitle(song, style = fonts.title, color = if (now) look.accent else look.ink) },
            subtitleLeading = quality?.let { badge -> { QualityPill(badge, compact = true) } },
            discContent = when {
                selected != null -> { { OrbitCheckMark(selected, look) } }
                pending -> { { CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = look.dim) } }
                else -> null
            },
            trailing = trailing ?: {
                if (downloadedTint != null) DownloadedBadge(song.videoId, if (downloadedTint == Color.Unspecified) look.dim else downloadedTint, Modifier.padding(start = 8.dp))
                song.durationText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
                Unit
            },
        )
    }
    val base = modifier
        .then(if (missing) Modifier.alpha(0.45f) else Modifier)
        .then(if (selected == true) Modifier.semantics { this.selected = true } else Modifier)
    if (onSwipe == null || selected != null || pending || missing) row(base)
    else OrbitSwipeToQueue(onSwipe, look, fonts, base) { row(Modifier) }
}

/**
 * A row that a swipe either way queues ("Play next" when the setting says so) and that then springs back: past the commit line a
 * light tick, on letting go there the queue. What the swipe uncovers is drawn only in the strip the row has left.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun OrbitSwipeToQueue(onSwipe: () -> Unit, look: OrbitLook, fonts: OrbitFonts, modifier: Modifier, content: @Composable () -> Unit) {
    val haptics = rememberHaptics()
    val current by rememberUpdatedState(onSwipe)
    var width by remember { mutableFloatStateOf(0f) }
    val holder = remember { mutableStateOf<SwipeToDismissBoxState?>(null) }
    val state = rememberSwipeToDismissBoxState(
        confirmValueChange = { value ->
            if (value != SwipeToDismissBoxValue.Settled) {
                val offset = runCatching { holder.value?.requireOffset() ?: 0f }.getOrDefault(0f)
                if (abs(offset) >= width * BlockMetrics.SWIPE_COMMIT) {
                    haptics.play(Haptic.Select)
                    current()
                }
            }
            false
        },
        positionalThreshold = { it * BlockMetrics.SWIPE_POSITIONAL_THRESHOLD },
    )
    holder.value = state
    var armed by remember { mutableStateOf(false) }
    LaunchedEffect(state, width) {
        if (width <= 0f) return@LaunchedEffect
        androidx.compose.runtime.snapshotFlow { runCatching { state.requireOffset() }.getOrDefault(0f) }.collect { offset ->
            val travelled = abs(offset)
            if (!armed && travelled >= width * BlockMetrics.SWIPE_COMMIT) { armed = true; haptics.play(Haptic.Tick) }
            else if (armed && travelled < width * BlockMetrics.SWIPE_COMMIT * BlockMetrics.SWIPE_REARM) armed = false
        }
    }
    val playNext by AppUi.host.swipeToPlayNext.collectAsStateWithLifecycle()
    val label = if (playNext) "Play next" else "Queue"
    SwipeToDismissBox(
        state = state,
        modifier = modifier.onSizeChanged { width = it.width.toFloat() },
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().clearAndSetSemantics { }.drawWithContent {
                    val offset = runCatching { state.requireOffset() }.getOrDefault(0f)
                    if (offset == 0f) return@drawWithContent
                    val left = if (offset > 0f) 0f else size.width + offset
                    val right = if (offset > 0f) offset else size.width
                    clipRect(left = left, top = 0f, right = right, bottom = size.height) {
                        drawRoundRect(look.chip, cornerRadius = CornerRadius(22.dp.toPx()))
                        this@drawWithContent.drawContent()
                    }
                },
            ) {
                for (side in listOf(Alignment.CenterStart, Alignment.CenterEnd)) {
                    Row(Modifier.align(side).padding(horizontal = 20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(CosmoIcons.Queue, contentDescription = null, tint = look.ink, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(label.uppercase(), style = fonts.label, color = look.ink)
                    }
                }
            }
        },
    ) { content() }
}

/**
 * The artist's latest release as one row on the page: the cover as a disc, "LATEST RELEASE", the title and the year, and the save
 * toggle at its end when [saved] is known and saving is offered.
 */
@Composable
fun OrbitLatestRelease(item: ShelfItem, year: Int?, saved: Boolean?, onClick: () -> Unit, onLongPress: (() -> Unit)?, onToggleSaved: (() -> Unit)?, modifier: Modifier = Modifier) {
    val fonts = orbitFonts()
    val look = orbitLook()
    OrbitSongRow(
        title = item.title, subtitle = listOfNotNull("Latest release", year?.toString()).joinToString(" · "), cover = item.thumbnailUrl,
        fonts = fonts, look = look, onClick = onClick, modifier = modifier.fillMaxWidth(), onLongClick = onLongPress,
        trailing = {
            if (saved != null && onToggleSaved != null) {
                OrbitRoundButton(if (saved) CosmoIcons.HeartFill else CosmoIcons.Heart, if (saved) "Saved" else "Save", look.ink, onToggleSaved, on = saved, onInk = look.ground)
            }
        },
    )
}

/** About: the artist's own words in a quiet card, cut to [lines] lines with "Read more", and their numbers under it in narrow type. */
@Composable
fun OrbitAboutCard(text: String?, stats: List<Pair<String, String>>, modifier: Modifier = Modifier, lines: Int = 5) {
    val fonts = orbitFonts()
    val look = orbitLook()
    var expanded by remember(text) { mutableStateOf(false) }
    var clipped by remember(text) { mutableStateOf(false) }
    Column(modifier.clip(RoundedCornerShape(28.dp)).background(look.chip).padding(20.dp)) {
        if (!text.isNullOrBlank()) {
            Text(
                text, style = fonts.body.copy(fontSize = 14.sp, lineHeight = 21.sp), color = look.ink.copy(alpha = 0.86f),
                maxLines = if (expanded) Int.MAX_VALUE else lines, overflow = TextOverflow.Ellipsis,
                onTextLayout = { if (!expanded) clipped = it.hasVisualOverflow },
            )
            if (clipped || expanded) {
                Text(
                    if (expanded) "Show less" else "Read more", style = fonts.chip.copy(fontSize = 13.sp), color = look.ink,
                    modifier = Modifier.padding(top = 6.dp).clip(CircleShape).clickable { expanded = !expanded }.padding(vertical = 6.dp),
                )
            }
        }
        if (stats.isNotEmpty()) {
            Row(Modifier.padding(top = if (text.isNullOrBlank()) 0.dp else 16.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                stats.forEach { (value, what) ->
                    Column {
                        Text(value, style = fonts.number.copy(fontSize = 24.sp, lineHeight = 26.sp), color = look.ink, maxLines = 1)
                        Text(what.uppercase(), style = fonts.label, color = look.dim, maxLines = 1, modifier = Modifier.padding(top = 4.dp).widthIn(max = 140.dp))
                    }
                }
            }
        }
    }
}

/** "2.4M monthly listeners" as the About card shows it: the number, and the words under it. */
fun orbitStat(text: String?): Pair<String, String>? {
    val t = text?.trim()?.takeIf { it.isNotBlank() } ?: return null
    val number = t.substringBefore(' ')
    val words = t.substringAfter(' ', "").ifBlank { return t to "" }
    return number to words
}

/** The side margin of a page at [width]: Home's, 28 dp from 840 dp, 16 below. */
fun orbitPageSide(width: Dp): Dp = if (width >= 840.dp) 28.dp else 16.dp
