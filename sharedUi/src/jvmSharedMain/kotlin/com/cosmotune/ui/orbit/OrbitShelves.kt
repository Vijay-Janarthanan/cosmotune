package com.cosmotune.ui.orbit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.ui.components.LocalShelfRowChrome
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.rememberShelfFlingBehavior
import com.cosmotune.ui.components.CosmoIcons

/*
 * The shelves of the Orbit kit: Home's song row, ranked row, records, portraits and colour cards, made into pieces so Home and the
 * album, playlist and artist pages draw them alike; and the video card, whose thumbnail stays rectangular because video is.
 */

// ---- rows -----------------------------------------------------------------------------------------------------------------------

/**
 * Home's song row: 68 dp, radius 22, the ink at 5 %, the cover as a 44 dp disc at the start. The playing row ([now]) is washed
 * with peach from the start, its title is peach and its disc wears the orbit (the moon circles while [playing]).
 *
 * [leading] replaces the disc (a selection mark, a spinner); [titleContent] the title line (an explicit mark); [trailing] the
 * duration ([trailingText]) at the end. Holding the row, or a right click on the desktop, is [onLongPress].
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitSongRow(
    title: String,
    subtitle: String,
    cover: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    trailingText: String? = null,
    enabled: Boolean = true,
    titleContent: (@Composable () -> Unit)? = null,
    subtitleLeading: (@Composable () -> Unit)? = null,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.height(68.dp).clip(RoundedCornerShape(22.dp))
            .background(if (now) Brush.horizontalGradient(listOf(look.accent.copy(alpha = 0.28f), look.row)) else Brush.linearGradient(listOf(look.row, look.row)))
            .combinedClickable(enabled = enabled, onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress.takeIf { enabled })
            .padding(start = 10.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            if (leading != null) leading()
            else {
                Disc(cover, 44.dp)
                if (now) OrbitRing(52.dp, look.accent, progress = null, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (titleContent != null) titleContent()
            else Text(title, style = fonts.title, color = if (now) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitleLeading != null) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    subtitleLeading()
                    if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else trailingText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
    }
}

/**
 * A row of a ranking (a chart, an artist's top songs): the place as a big narrow number, the first in peach, then a 48 dp disc and
 * the two lines. No fill of its own; the playing row ([now]) has its title in peach and the orbit round its disc.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitRankedRow(
    rank: Int,
    title: String,
    subtitle: String,
    cover: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    now: Boolean = false,
    playing: Boolean = false,
    onLongPress: (() -> Unit)? = null,
    trailingText: String? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    Row(
        modifier.fillMaxWidth().height(66.dp).clip(RoundedCornerShape(18.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongPress)
            .contextClick(onLongPress)
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$rank", style = fonts.number.copy(fontSize = 40.sp, lineHeight = 40.sp), color = if (rank == 1) look.accent else look.ink.copy(alpha = 0.3f), modifier = Modifier.width(52.dp))
        Box(Modifier.size(56.dp), contentAlignment = Alignment.Center) {
            Disc(cover, 48.dp)
            if (now) OrbitRing(56.dp, look.accent, progress = null, spinning = playing, stroke = 1.5.dp, moon = 3.dp)
        }
        Column(Modifier.weight(1f).padding(start = 6.dp)) {
            Text(title, style = fonts.title, color = if (now) look.accent else look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) trailing()
        else trailingText?.let { Text(it, style = fonts.label, color = look.dim, modifier = Modifier.padding(start = 10.dp)) }
    }
}

// ---- shelves --------------------------------------------------------------------------------------------------------------------

/** A shelf's horizontal row, in the platform's shelf chrome (the desktop's arrows, drag and wheel), settling on a card's edge. */
@Composable
internal fun OrbitShelfRow(side: Dp, gap: Dp, content: androidx.compose.foundation.lazy.LazyListScope.() -> Unit) {
    val state = rememberLazyListState()
    LocalShelfRowChrome.current.Wrap(state) {
        LazyRow(
            state = state,
            contentPadding = PaddingValues(horizontal = side),
            horizontalArrangement = Arrangement.spacedBy(gap),
            flingBehavior = rememberShelfFlingBehavior(state),
            content = content,
        )
    }
}

internal fun shelfItemKey(item: ShelfItem, index: Int): String = (item.browseId ?: item.videoId ?: item.title) + "#" + index

/** An album or playlist as a record: the cover as a disc, play as a moon on its edge ([onPlay]), the title and a line under it. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitRecord(
    title: String,
    subtitle: String,
    cover: String?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onPlay: () -> Unit = onClick,
    onLongPress: (() -> Unit)? = null,
    size: Dp = 152.dp,
) {
    Column(modifier.width(size).combinedClickable(onClick = onClick, onLongClick = onLongPress).contextClick(onLongPress)) {
        Box(Modifier.size(size)) {
            Disc(cover, size)
            // Play is a small moon on the disc's edge, so the cover itself stays clear.
            DiscPlayMoon(look, onPlay)
        }
        Text(title, style = fonts.title, color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 12.dp))
        if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body.copy(fontSize = 12.5.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Albums as a row of records (Home's disc row). */
@Composable
internal fun OrbitRecordRow(
    items: List<ShelfItem>,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)? = null,
    onPlay: (ShelfItem) -> Unit = onClick,
    size: Dp = 152.dp,
) {
    OrbitShelfRow(side + 4.dp, 18.dp) {
        itemsIndexed(items, key = { i, it -> shelfItemKey(it, i) }) { _, item ->
            OrbitRecord(item.title, item.subtitle, item.thumbnailUrl, fonts, look, onClick = { onClick(item) }, onPlay = { onPlay(item) },
                onLongPress = onLongPress?.let { { it(item) } }, size = size)
        }
    }
}

/** Artists as plain round portraits with the name under them: no play moon, a tap opens them. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitPortraitRow(
    items: List<ShelfItem>,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)? = null,
    size: Dp = 84.dp,
) {
    OrbitShelfRow(side + 6.dp, 16.dp) {
        itemsIndexed(items, key = { i, it -> shelfItemKey(it, i) }) { _, item ->
            Column(
                Modifier.width(size + 4.dp).clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = onLongPress?.let { { it(item) } }),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Disc(item.thumbnailUrl, size)
                Text(item.title, style = fonts.body.copy(fontWeight = FontWeight(600)), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
            }
        }
    }
}

/** Playlists: tall cards filled with their cover's colour, the title at the top and the cover as a disc rising out of the corner. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitColourCardRow(items: List<ShelfItem>, fonts: OrbitFonts, side: Dp, onClick: (ShelfItem) -> Unit, onLongPress: ((ShelfItem) -> Unit)? = null) {
    OrbitShelfRow(side, 12.dp) {
        itemsIndexed(items, key = { i, it -> shelfItemKey(it, i) }) { _, item ->
            val (fill, ink) = coverFill(item.thumbnailUrl, item.title)
            Box(
                Modifier.width(172.dp).height(224.dp).clip(RoundedCornerShape(28.dp)).background(fill)
                    .combinedClickable(onClick = { onClick(item) }, onLongClick = onLongPress?.let { { it(item) } }),
            ) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp)) {
                    Text(item.title, style = fonts.section.copy(fontSize = 17.sp, lineHeight = 20.sp), color = ink, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (item.subtitle.isNotBlank()) Text(item.subtitle, style = fonts.body.copy(fontSize = 12.sp), color = ink.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
                }
                Disc(item.thumbnailUrl, 124.dp, Modifier.align(Alignment.BottomEnd).offset(x = 30.dp, y = 30.dp))
            }
        }
    }
}

// ---- video ----------------------------------------------------------------------------------------------------------------------

/** The red of "LIVE": the only colour on a video card besides the play moon's peach. */
internal fun liveRed(look: OrbitLook): Color = if (look.dark) Color(0xFFFF8A7A) else Color(0xFFB8402F)

/**
 * A video: the thumbnail as a rounded 16:9 rectangle with nothing on it, the play moon hanging off its bottom-end corner, and under
 * it the title and the meta line; a live performance's line starts with a red "● LIVE".
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun OrbitVideoCard(
    title: String,
    meta: String,
    thumbnail: String?,
    live: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongPress: (() -> Unit)? = null,
    width: Dp = 300.dp,
) {
    Column(modifier.width(width).combinedClickable(onClick = onClick, onLongClick = onLongPress).contextClick(onLongPress)) {
        // The moon hangs 12 dp past the picture's corner, so the frame leaves that room at its end and foot.
        Box(Modifier.width(width).padding(end = 12.dp, bottom = 12.dp)) {
            Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(24.dp)).background(look.chip)) {
                AsyncImage(thumbnail, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            }
            Box(Modifier.align(Alignment.BottomEnd).offset(x = 12.dp, y = 12.dp).size(44.dp)) { DiscPlayMoon(look, onClick) }
        }
        Text(title, style = fonts.title.copy(fontSize = 14.sp), color = look.ink, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 2.dp))
        val line = buildAnnotatedString {
            if (live) {
                withStyle(SpanStyle(color = liveRed(look), fontFamily = fonts.label.fontFamily, fontWeight = FontWeight.Bold, letterSpacing = 1.sp, fontSize = 10.5.sp)) { append("● LIVE") }
                if (meta.isNotBlank()) append(" · ")
            }
            append(meta)
        }
        if (line.isNotEmpty()) Text(line, style = fonts.body.copy(fontSize = 12.sp), color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** A row of [OrbitVideoCard]s: music videos, or live performances when [live]. */
@Composable
internal fun OrbitVideoRow(
    items: List<ShelfItem>,
    live: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    side: Dp,
    onClick: (ShelfItem) -> Unit,
    onLongPress: ((ShelfItem) -> Unit)? = null,
    width: Dp = 300.dp,
) {
    OrbitShelfRow(side, 18.dp) {
        itemsIndexed(items, key = { i, it -> shelfItemKey(it, i) }) { _, item ->
            val meta = listOfNotNull(item.song?.durationText, item.subtitle.takeIf { it.isNotBlank() }).joinToString(" · ")
            OrbitVideoCard(item.title, meta, item.thumbnailUrl, live, fonts, look, onClick = { onClick(item) },
                onLongPress = onLongPress?.let { { it(item) } }, width = width)
        }
    }
}

/** "Show all 24" / "Show less": the peach text button under a list that shows its first rows. */
@Composable
internal fun OrbitShowAll(all: Boolean, count: Int, fonts: OrbitFonts, look: OrbitLook, onToggle: () -> Unit, modifier: Modifier = Modifier) {
    Text(
        if (all) "Show less" else "Show all $count", style = fonts.chip, color = look.accent,
        modifier = modifier.clip(CircleShape).clickable(onClick = onToggle).padding(horizontal = 12.dp, vertical = 8.dp),
    )
}

/** A small round mark in the place of a row's disc: the check of a selected row. */
@Composable
internal fun OrbitCheckMark(selected: Boolean, look: OrbitLook) {
    Box(
        Modifier.size(28.dp).clip(CircleShape).background(if (selected) look.ink else look.chip),
        contentAlignment = Alignment.Center,
    ) { if (selected) Icon(CosmoIcons.Check, contentDescription = "Selected", tint = look.ground, modifier = Modifier.size(16.dp)) }
}
