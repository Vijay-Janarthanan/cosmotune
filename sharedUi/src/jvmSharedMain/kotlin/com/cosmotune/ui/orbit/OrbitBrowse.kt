package com.cosmotune.ui.orbit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
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
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.cosmotune.design.BlockMetrics
import com.cosmotune.music.data.canvas.AppleArtistArtRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.isMatchMissing
import com.cosmotune.music.data.model.isMatchPending
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.components.DownloadedBadge
import com.cosmotune.music.ui.components.ExplicitSongTitle
import com.cosmotune.music.ui.components.contextClick
import com.cosmotune.music.ui.components.longPressMenuClickable
import com.cosmotune.music.ui.haptics.Haptic
import com.cosmotune.music.ui.haptics.rememberHaptics
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.rememberRemoteArtworkUrl
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.nowplaying.QualityPill
import com.cosmotune.ui.nowplaying.catalogueQualityBadge
import com.cosmotune.ui.pages.ArtistMotionArtwork
import com.cosmotune.ui.pages.artistMotionPlays
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlin.math.abs

/*
 * The pieces Search, Explore and Library share (Canvas repo, design/cosmotune/pages-orbit/board.html, sections 3 and 4): the big
 * search pill, a chip that can be chosen, the song row (Home's), a record with the play moon on its edge, an artist portrait, the
 * colour tile of a mood or genre, and the artist disc that plays Apple's motion portrait. Written once here, used by every page.
 */

// ---- the search pill ---------------------------------------------------------------------------------------------------------

/** The page's search field: a 60 dp pill in ink at 8 %, the magnifier, the text, and a round clear button once something is typed. */
@Composable
fun OrbitSearchPill(
    query: String,
    onQueryChange: (String) -> Unit,
    onSubmit: () -> Unit,
    placeholder: String,
    fonts: OrbitFonts,
    look: OrbitLook,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val text = fonts.body.copy(fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight(500))
    Row(
        modifier.fillMaxWidth().height(60.dp).clip(CircleShape).background(look.chip).clickable { focusRequester.requestFocus() }
            .padding(start = 22.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(CosmoIcons.Search, contentDescription = null, tint = look.ink, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(14.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (query.isEmpty()) Text(placeholder, style = text, color = look.dim, maxLines = 1, overflow = TextOverflow.Ellipsis)
            BasicTextField(
                value = query,
                onValueChange = onQueryChange,
                singleLine = true,
                textStyle = text.copy(color = look.ink),
                cursorBrush = SolidColor(look.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = { onSubmit(); keyboard?.hide(); focusManager.clearFocus() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focusRequester),
            )
        }
        if (query.isNotEmpty()) {
            OrbitRoundButton(CosmoIcons.Close, "Clear", look.ink, onClick = { onQueryChange("") }, size = 40.dp)
        } else Spacer(Modifier.width(12.dp))
    }
}

// ---- chips and labels ------------------------------------------------------------------------------------------------------

/** A chip that can be the chosen one: ink at 8 %, or solid ink with the ground's colour for its text when [selected]. */
@Composable
fun OrbitSelectChip(
    title: String,
    selected: Boolean,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    painter: Painter? = null,
    haptic: Boolean = true,
) {
    val haptics = rememberHaptics()
    val ink = if (selected) look.ground else look.ink
    Row(
        modifier.height(40.dp).clip(CircleShape).background(if (selected) look.ink else look.chip)
            .clickable { if (haptic) haptics.play(Haptic.Select); onClick() }
            .padding(start = if (icon != null || painter != null) 12.dp else 16.dp, end = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, contentDescription = null, tint = if (selected) ink else look.accent, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        if (painter != null) { Icon(painter, contentDescription = null, tint = Color.Unspecified, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)) }
        Text(title, style = fonts.chip, color = ink, maxLines = 1)
    }
}

/** A row of chips that scrolls sideways, the page's gutter inside the scroll so the chips run to the edge. */
@Composable
fun OrbitChipRow(side: Dp, modifier: Modifier = Modifier, content: @Composable RowScope.() -> Unit) {
    Row(
        modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = side),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** A page's heading, as Home's greeting: the mono [label] (none when empty) over the [title] in the wide face with its peach full stop, [trailing] at the end. */
@Composable
fun OrbitPageHeading(title: String, label: String, fonts: OrbitFonts, look: OrbitLook, modifier: Modifier = Modifier, trailing: (@Composable RowScope.() -> Unit)? = null) {
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            if (label.isNotEmpty()) {
                OrbitLabel(label, fonts, look.dim)
                Spacer(Modifier.height(10.dp))
            }
            Text(
                androidx.compose.ui.text.buildAnnotatedString {
                    append(title)
                    pushStyle(androidx.compose.ui.text.SpanStyle(color = look.accent)); append("."); pop()
                },
                style = fonts.greeting, color = look.ink, maxLines = 1,
            )
        }
        if (trailing != null) trailing()
    }
}

/** The mono, tracked, upper-case label ("TOP RESULT · ARTIST"). */
@Composable
fun OrbitLabel(text: String, fonts: OrbitFonts, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = fonts.label, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = modifier)
}

// ---- rows ----------------------------------------------------------------------------------------------------------------------

/**
 * A [Song] as [OrbitSongRow] (Home's row): its cover, the title with the explicit mark, [subtitle] (the artist; led by the source's
 * offered tier with [showSourceQuality]), and the length (after a downloaded mark when [downloadedTint] is set; [trailing] replaces
 * both). A tap plays, a hold or a right click opens the song's menu, and with [onSwipe] a swipe either way queues it (or plays it
 * next) and the row springs back ([OrbitSwipeToQueue]). A song still being matched shows a spinner for its disc; one with no match is
 * dimmed and does nothing. [selected] (not null while choosing) puts a check in the disc's place.
 */
@Composable
internal fun OrbitSongRowFor(
    song: Song,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    onSwipe: (() -> Unit)? = null,
    now: Boolean = false,
    playing: Boolean = false,
    showSourceQuality: Boolean = false,
    cover: String? = rememberRemoteArtworkUrl(song) ?: song.thumbnailUrl,
    subtitle: String = song.artist,
    selected: Boolean? = null,
    downloadedTint: Color? = null,
    trailing: (@Composable RowScope.() -> Unit)? = null,
) {
    val pending = song.isMatchPending
    val missing = song.isMatchMissing
    val quality = if (showSourceQuality) song.catalogueQualityBadge() else null
    val row: @Composable (Modifier) -> Unit = { m ->
        OrbitSongRow(
            title = song.title, subtitle = subtitle, cover = cover, fonts = fonts, look = look, onClick = onClick,
            modifier = m.fillMaxWidth(), now = now, playing = playing,
            onLongClick = onLongClick.takeUnless { pending || missing },
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

// ---- records and portraits -----------------------------------------------------------------------------------------------

/** A grid of records: as many 150 dp cells as fit [width] with [side] gutters and 18 dp between (at least two), and their width. */
internal fun orbitRecordGrid(width: Dp, side: Dp): Pair<Int, Dp> {
    val gap = 18.dp
    val room = width - side * 2
    val columns = ((room + gap) / (150.dp + gap)).toInt().coerceAtLeast(2)
    return columns to (room - gap * (columns - 1)) / columns
}

/** An artist as a plain portrait: a round photo [size] wide and the name under it, centred. No play moon: a tap opens them. */
@Composable
internal fun OrbitPortrait(
    title: String,
    cover: String?,
    size: Dp,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Column(
        modifier.width(size).clip(RoundedCornerShape(16.dp)).longPressMenuClickable(onClick = onClick, onLongClick = onLongClick).contextClick(onLongClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Disc(cover, size)
        Text(title, style = fonts.body.copy(fontWeight = FontWeight(600)), color = look.ink, maxLines = 1, overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
    }
}

// ---- mood and genre tiles --------------------------------------------------------------------------------------------------

/** The two shapes of the colour tile: a playlist's tall card, and a mood's or genre's short tile. */
internal enum class OrbitTile(val height: Dp, val radius: Dp, val disc: Dp, val discX: Dp, val discY: Dp, val titleTop: Dp, val titleLines: Int, val bareWithoutCover: Boolean) {
    CARD(224.dp, 28.dp, 124.dp, 30.dp, 30.dp, 18.dp, 3, false),
    MOOD(110.dp, 26.dp, 76.dp, 20.dp, 22.dp, 14.dp, 2, true),
}

/**
 * A title (and the [subtitle] under it) on a tile filled with the colour of its cover (or the deep form of YouTube's [stripe] colour
 * when there is one), the cover as a disc peeking out of the bottom-end corner. [kind] sets the size: [OrbitTile.CARD] for a
 * playlist, [OrbitTile.MOOD] for a mood or a genre (two title lines, an inked circle in place of a missing cover).
 */
@Composable
internal fun OrbitColourTile(
    title: String,
    subtitle: String,
    cover: String?,
    kind: OrbitTile,
    fonts: OrbitFonts,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
    stripe: Long? = null,
) {
    val (fill, ink) = if (stripe != null) vivid(stripe.toInt()).let { it.fill to it.ink } else coverFill(cover, title)
    Box(modifier.height(kind.height).clip(RoundedCornerShape(kind.radius)).background(fill).longPressMenuClickable(onClick = onClick, onLongClick = onLongClick).contextClick(onLongClick)) {
        // Above the disc: the words never stand on the cover.
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = kind.titleTop)) {
            Text(title, style = fonts.section.copy(fontSize = 17.sp, lineHeight = 20.sp), color = ink, maxLines = kind.titleLines, overflow = TextOverflow.Ellipsis)
            if (subtitle.isNotBlank()) Text(subtitle, style = fonts.body.copy(fontSize = 12.sp), color = ink.copy(alpha = 0.72f), maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
        }
        val disc = Modifier.align(Alignment.BottomEnd).offset(x = kind.discX, y = kind.discY)
        if (cover != null || !kind.bareWithoutCover) Disc(cover, kind.disc, disc)
        else Box(disc.size(kind.disc).clip(CircleShape).background(ink.copy(alpha = 0.16f)))
    }
}

/** A mood or a genre as a colour tile ([OrbitColourTile], [OrbitTile.MOOD]). */
@Composable
internal fun OrbitMoodTile(
    title: String,
    cover: String?,
    stripe: Long?,
    fonts: OrbitFonts,
    look: OrbitLook,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) = OrbitColourTile(title, "", cover, OrbitTile.MOOD, fonts, onClick, modifier, stripe = stripe)

// ---- the artist's disc -----------------------------------------------------------------------------------------------------

/**
 * An artist's round photo that plays Apple's motion portrait when Apple has one for [name] and it may play (the animated
 * artwork switch, reduced motion, mobile data: [artistMotionPlays], the artist page's own rule); otherwise the photo. Silent.
 */
@Composable
internal fun OrbitArtistDisc(name: String, url: String?, size: Dp, modifier: Modifier = Modifier) {
    var art by remember(name) { mutableStateOf(AppleArtistArtRepository.cached(name)) }
    LaunchedEffect(name) { if (name.isNotBlank()) art = AppleArtistArtRepository.artFor(name) ?: art }
    val settings = PlayerPlatform.host.settings
    val animated by settings.animatedCanvas.collectAsStateWithLifecycle()
    val metered by settings.meteredConnection.collectAsStateWithLifecycle()
    val overCellular by settings.canvasOverCellular.collectAsStateWithLifecycle()
    val motion = art?.takeIf { it.videoUrl != null && artistMotionPlays(animated, LocalReducedMotion.current, metered, overCellular) }
    if (motion == null) {
        Disc(url, size, modifier)
    } else {
        // The YouTube photo stays under the loop (Apple's hero is a wide banner, not a portrait) until its frames arrive.
        ArtistMotionArtwork(motion.copy(heroUrl = ""), url, modifier.size(size).clip(CircleShape))
    }
}
