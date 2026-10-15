package com.cosmotune.desktop

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.State
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.design.BlockMetrics
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoLoadingBlob
import com.cosmotune.ui.components.CoverFrame
import com.cosmotune.ui.components.cosmoFocusRing
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoMotion
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.tabular
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.Song
import com.music.bitchord.desktop.DesktopArtwork
import com.music.bitchord.desktop.DesktopStrings

/*
 * The desktop's rows in the new look (`v2/screens-v2.css` `.d2-tracks li`): 54 dp, radius 18, 16 dp in from each side, washed in
 * the cover's colour while the song plays, an 8 % state layer on hover. This file holds the song row of History, Downloads and
 * Local Music, and the pieces the collection page's rows share with it (the surface, the hover-revealed actions, the bars).
 */

/** `.d2-tracks li`: the row's height (`BlockMetrics.DESKTOP_ROW_DP`) and its corner radius. */
internal val DESKTOP_ROW_HEIGHT: Dp = BlockMetrics.DESKTOP_ROW_DP.dp
internal val DESKTOP_ROW_RADIUS: Dp = 18.dp

/** `.d2-tracks li { padding: 0 16px }`. */
internal val DESKTOP_ROW_PADDING: Dp = 16.dp

/** The size of an action button of a row: the desktop's pointer does not need the phone's 48 dp. */
internal val DESKTOP_ROW_ACTION: Dp = 32.dp

/** `.d2-tracks li` columns: the number or the bars take 36 dp, the duration 60. */
internal val DESKTOP_NUMBER_COLUMN: Dp = 36.dp
internal val DESKTOP_DURATION_COLUMN: Dp = 60.dp

/**
 * Whether the audio is playing right now (not paused), for the bars of the playing row. The old pages cannot see it (`playback` is a
 * local of the root composable), so the root provides it once, next to the playing song, and the three functions below start their
 * `isPlaying` from it. False where nothing provides it (a render, a test).
 */
internal val LocalCosmoPlaying = compositionLocalOf { false }

/** The state layer on hover, the playing row's wash (`.d2-tracks li.is-playing`: the container at 60 %) and the faint stripe. */
private const val HOVER_LAYER = 0.08f
private const val PLAYING_WASH = 0.6f
private const val STRIPE_LAYER = 0.025f

/**
 * `DesktopSongRow` in the new look. It takes the old row's parameters, in the same order, and two more at the end: [isCurrent] says
 * the row is the song that is playing (it is washed, its title is in the accent and the bars stand where a number would), and
 * [isPlaying] that the bars move (from [LocalCosmoPlaying], which the root fills with the player's own flag). The old row's own function
 * reads the playing song from a local that only its file can see, so its delegate passes [isCurrent] and leaves [isPlaying] at its default.
 *
 * It is 54 dp tall: the number or a 40 dp cover, the title over the artist, the like heart and the download and add buttons (shown
 * while the pointer is over the row or it has the keyboard focus, and always while the song is liked, downloading or downloaded),
 * the duration, and the page's own menu button when [menu] is given.
 */
@Composable
internal fun CosmoDesktopSongRow(
    song: Song,
    liked: Boolean,
    onClick: (Song) -> Unit,
    onToggleLike: ((Song) -> Unit)?,
    onDownload: ((Song) -> Unit)? = null,
    onAddToPlaylist: ((Song) -> Unit)? = null,
    downloaded: Boolean = false,
    downloadInProgress: Boolean = false,
    number: Int? = null,
    menu: (@Composable (Song) -> Unit)? = null,
    isCurrent: Boolean = false,
    isPlaying: Boolean = LocalCosmoPlaying.current,
) {
    val colors = LocalCosmoColors.current
    val palette = colors.palette
    val type = LocalCosmoType.current
    val chrome = rememberCosmoRowChrome(current = isCurrent)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = DESKTOP_ROW_HEIGHT)
            .cosmoDesktopRow(chrome, colors, striped = false, enabled = true, onClick = { onClick(song) })
            .padding(horizontal = DESKTOP_ROW_PADDING),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (number != null) {
            Box(Modifier.width(DESKTOP_NUMBER_COLUMN), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) CosmoDesktopEqualiser(playing = isPlaying) else DesktopRowNumber(number)
            }
        } else {
            CoverFrame(size = 40.dp, radius = 10.dp, shadow = false) {
                DesktopArtwork(song.thumbnailUrl, Modifier.fillMaxSize(), px = ROW_ART_PX)
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = type.forText(type.desktopRowTitle(), song.title),
                color = Color(if (isCurrent) palette.accentStrong else palette.textPrimary),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (song.artist.isNotBlank()) {
                Text(
                    text = song.artist,
                    style = type.forText(type.desktopRowSubtitle(), song.artist),
                    color = Color(palette.textSecondary),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // A cover leaves no room for the bars in front, so the row that is playing shows them after its words.
        if (isCurrent && number == null) {
            Spacer(Modifier.width(14.dp))
            CosmoDesktopEqualiser(playing = isPlaying)
        }
        DesktopSourceQuality(song)
        Spacer(Modifier.width(8.dp))
        CosmoDesktopRowActions(
            song = song,
            reveal = chrome.reveal,
            liked = liked,
            onToggleLike = onToggleLike,
            onDownload = onDownload,
            downloaded = downloaded,
            downloadInProgress = downloadInProgress,
            onAddToPlaylist = onAddToPlaylist,
            onRemove = null,
            reserveRemove = false,
        )
        Text(
            text = song.durationText ?: "—",
            modifier = Modifier.width(DESKTOP_DURATION_COLUMN),
            style = type.desktopRowDuration(),
            color = Color(palette.textTertiary),
            textAlign = TextAlign.End,
            maxLines = 1,
        )
        menu?.invoke(song)
    }
}

/** The title of a desktop row (`.d2-tracks li b`): Hanken Grotesk 14 sp, weight 650. */
internal fun CosmoType.desktopRowTitle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight(650), fontSize = 14.sp, lineHeight = 14.sp * 1.3f)

/** The line under it (`.d2-tracks li .txt span`): 12.5 sp. */
internal fun CosmoType.desktopRowSubtitle(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 12.5.sp, lineHeight = 12.5.sp * 1.35f)

/** The duration and the other faint numbers of a row: 13 sp with tabular figures, so a column of them lines up. */
internal fun CosmoType.desktopRowDuration(): TextStyle =
    TextStyle(fontFamily = textFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, lineHeight = 13.sp * 1.3f).tabular()

/** A track number (`.d2-tracks li .no`): faint, 14 sp, tabular. */
@Composable
internal fun DesktopRowNumber(number: Int) {
    val type = LocalCosmoType.current
    Text(
        text = "$number",
        style = type.desktopRowDuration().copy(fontSize = 14.sp, lineHeight = 14.sp * 1.3f),
        color = Color(LocalCosmoColors.current.palette.textTertiary),
        maxLines = 1,
    )
}

/** The quality mark the old row showed for a lossless or high-rate source, in the accent. */
@Composable
private fun DesktopSourceQuality(song: Song) {
    val badge = song.sourceQuality?.uppercase()?.takeIf { it in SHOWN_QUALITIES } ?: return
    val type = LocalCosmoType.current
    Text(
        text = badge,
        style = type.desktopRowSubtitle().copy(fontWeight = FontWeight(650), fontSize = 11.sp, letterSpacing = 0.5.sp),
        color = Color(LocalCosmoColors.current.palette.accentStrong),
        maxLines = 1,
        modifier = Modifier.padding(horizontal = 8.dp),
    )
}

private val SHOWN_QUALITIES = setOf("LOSSLESS", "FLAC", "HI-RES", "HIRES", "HIGH", "320")

// ---- the surface of a row ----

/**
 * What a row animates: the state layer under the pointer, the wash of the song that is playing, and how far the hover-revealed
 * actions have come in. The three are read while drawing, so a pointer crossing the row recomposes it twice (in and out) and no more.
 */
@Stable
internal class CosmoRowChrome(
    val interaction: MutableInteractionSource,
    val hover: State<Float>,
    val wash: State<Float>,
    val reveal: State<Float>,
    val focusWithin: MutableState<Boolean>,
)

@Composable
internal fun rememberCosmoRowChrome(current: Boolean): CosmoRowChrome {
    val reduced = LocalReducedMotion.current
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val focusWithin = remember { mutableStateOf(false) }
    val hover = animateFloatAsState(if (hovered) 1f else 0f, CosmoMotion.effects(reduced), label = "row hover")
    val wash = animateFloatAsState(if (current) 1f else 0f, CosmoMotion.effects(reduced), label = "row wash")
    val reveal = animateFloatAsState(if (hovered || focusWithin.value) 1f else 0f, CosmoMotion.effects(reduced), label = "row actions")
    return remember(interaction) { CosmoRowChrome(interaction, hover, wash, reveal, focusWithin) }
}

/**
 * The look of a row: a [DESKTOP_ROW_RADIUS] clip with, behind its content, the faint stripe when [striped], the wash of the playing
 * song and the hover layer; a click that leaves the focus ring of the page's colour while the keyboard is on it. The colours are
 * resolved while drawing, so a palette fade repaints the row without recomposing it.
 */
@Composable
internal fun Modifier.cosmoDesktopRow(
    chrome: CosmoRowChrome,
    colors: CosmoColors,
    striped: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
): Modifier = this
    .cosmoFocusRing(chrome.interaction, DESKTOP_ROW_RADIUS)
    .onFocusChanged { chrome.focusWithin.value = it.hasFocus }
    .clip(RoundedCornerShape(DESKTOP_ROW_RADIUS))
    .drawBehind {
        val radius = CornerRadius(DESKTOP_ROW_RADIUS.toPx())
        if (striped) drawRoundRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = STRIPE_LAYER), cornerRadius = radius)
        val wash = chrome.wash.value
        if (wash > 0.001f) drawRoundRect(colors.live(CoverField.ACCENT_CONTAINER).copy(alpha = PLAYING_WASH * wash), cornerRadius = radius)
        val hover = chrome.hover.value
        if (hover > 0.001f) drawRoundRect(colors.live(CoverField.TEXT_PRIMARY).copy(alpha = HOVER_LAYER * hover), cornerRadius = radius)
    }
    // a row that does nothing (a Spotify track with no match) does not answer the pointer either
    .then(if (enabled) Modifier.hoverable(chrome.interaction) else Modifier)
    .clickable(interactionSource = chrome.interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)

// ---- the actions of a row ----

/**
 * The like, download, add and remove buttons of a row, 32 dp each and PLAIN, in that order, before the duration. A button that
 * has nothing to say stays out of the way until the pointer is over the row or the keyboard is in it ([reveal]); the heart of a
 * liked song and the download of a song that is downloading or downloaded are always shown. Every button that can exist on the page
 * keeps its place whether it shows or not, so the duration column lines up from row to row: [reserveRemove] keeps the remove
 * button's place on a row that cannot be removed.
 *
 * A button is 32 dp in the row's layout: the 48 dp touch target is for fingers, and the desktop's pointer is not one.
 */
@Composable
internal fun CosmoDesktopRowActions(
    song: Song,
    reveal: State<Float>,
    liked: Boolean,
    onToggleLike: ((Song) -> Unit)?,
    onDownload: ((Song) -> Unit)?,
    downloaded: Boolean,
    downloadInProgress: Boolean,
    onAddToPlaylist: ((Song) -> Unit)?,
    onRemove: ((Song) -> Unit)?,
    reserveRemove: Boolean,
) {
    CompositionLocalProvider(LocalMinimumInteractiveComponentSize provides DESKTOP_ROW_ACTION) {
        Row(horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            onToggleLike?.let { toggle ->
                CosmoIconButton(
                    icon = if (liked) CosmoIcons.HeartFill else CosmoIcons.Heart,
                    contentDescription = DesktopStrings["like", "Like"],
                    onClick = { toggle(song) },
                    modifier = Modifier.revealedBy(reveal, always = liked),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                    on = liked,
                    accentWhenOn = true,
                )
            }
            onDownload?.let { save ->
                val shown = downloaded || downloadInProgress
                if (downloadInProgress) {
                    DownloadingMark(
                        label = DesktopStrings["download", "Download"],
                        onClick = { save(song) },
                        modifier = Modifier.revealedBy(reveal, always = true),
                    )
                } else {
                    CosmoIconButton(
                        icon = if (downloaded) CosmoIcons.Downloaded else CosmoIcons.Download,
                        contentDescription = if (downloaded) DesktopStrings["downloaded", "Downloaded"] else DesktopStrings["download", "Download"],
                        onClick = { if (!downloaded) save(song) },
                        modifier = Modifier.revealedBy(reveal, always = shown),
                        style = CosmoIconButtonStyle.PLAIN,
                        size = DESKTOP_ROW_ACTION,
                        on = if (downloaded) true else null,
                        accentWhenOn = true,
                    )
                }
            }
            onAddToPlaylist?.let { add ->
                CosmoIconButton(
                    icon = CosmoIcons.Plus,
                    contentDescription = DesktopStrings["add_to_playlist", "Add to playlist"],
                    onClick = { add(song) },
                    modifier = Modifier.revealedBy(reveal, always = false),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                )
            }
            if (onRemove != null) {
                CosmoIconButton(
                    icon = CosmoIcons.Close,
                    contentDescription = DesktopStrings["d_remove_from_playlist", "Remove from playlist"],
                    onClick = { onRemove(song) },
                    modifier = Modifier.revealedBy(reveal, always = false),
                    style = CosmoIconButtonStyle.PLAIN,
                    size = DESKTOP_ROW_ACTION,
                )
            } else if (reserveRemove) {
                Spacer(Modifier.size(DESKTOP_ROW_ACTION))
            }
        }
    }
}

/** The opacity of a revealed action, read while drawing: fully there when [always], otherwise as far in as the row's [reveal]. */
private fun Modifier.revealedBy(reveal: State<Float>, always: Boolean): Modifier =
    graphicsLayer { alpha = if (always) 1f else reveal.value }

/** The spinner of a download under way, in a button's place: clicking it asks again, as the old row did. */
@Composable
private fun DownloadingMark(label: String, onClick: () -> Unit, modifier: Modifier) {
    Box(
        modifier
            .size(DESKTOP_ROW_ACTION)
            .clip(RoundedCornerShape(10.dp))
            .semantics { contentDescription = label }
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        CosmoLoadingBlob(Modifier.size(16.dp))
    }
}

// ---- the bars ----

/**
 * The equaliser (`.eq`): three 3 dp bars, 2 dp apart, 14 dp tall, in the accent, at their rest heights; while [playing] they bounce,
 * each on its own beat. Under reduced motion they stand still. The heights are read while drawing and a row that is not playing has
 * no animation running at all. (`CosmoEqualiser` of the list rows is internal to `sharedUi`, so this is the desktop's copy.)
 */
@Composable
internal fun CosmoDesktopEqualiser(playing: Boolean, modifier: Modifier = Modifier) {
    val colors = LocalCosmoColors.current
    val reduced = LocalReducedMotion.current
    val label = DesktopStrings["now_playing", "Now playing"]
    val box = modifier
        .size(BARS_WIDTH, BlockMetrics.EQ_HEIGHT_DP.dp)
        .semantics { contentDescription = label }
    if (playing && !reduced) {
        val transition = rememberInfiniteTransition(label = "equaliser")
        val phases = List(3) { index ->
            transition.animateFloat(
                initialValue = 0f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(tween(BEAT_MS + index * BEAT_STEP_MS, easing = FastOutSlowInEasing), RepeatMode.Reverse),
                label = "equaliser bar $index",
            )
        }
        BarsBox(box, colors) { index ->
            val rest = BlockMetrics.equaliserRest(index)
            rest + (BlockMetrics.equaliserPeak(index) - rest) * phases[index].value
        }
    } else {
        BarsBox(box, colors) { index -> BlockMetrics.equaliserRest(index) }
    }
}

@Composable
private fun BarsBox(modifier: Modifier, colors: CosmoColors, height: (Int) -> Float) {
    Box(
        modifier.drawBehind {
            val ink = colors.live(CoverField.ACCENT)
            val bar = BlockMetrics.EQ_BAR_DP.dp.toPx()
            val gap = BlockMetrics.EQ_GAP_DP.dp.toPx()
            val radius = CornerRadius(BlockMetrics.EQ_RADIUS_DP.dp.toPx())
            for (index in 0..2) {
                val h = height(index) * size.height
                drawRoundRect(ink, Offset(index * (bar + gap), size.height - h), Size(bar, h), radius)
            }
        },
    )
}

private val BARS_WIDTH = (3 * BlockMetrics.EQ_BAR_DP + 2 * BlockMetrics.EQ_GAP_DP).dp
private const val BEAT_MS = 480
private const val BEAT_STEP_MS = 90
