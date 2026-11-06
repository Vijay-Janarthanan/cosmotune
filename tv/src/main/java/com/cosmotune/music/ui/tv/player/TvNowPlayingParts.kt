/*
 * Source: FORK.md companion source record TV-0024
 * Adaptations recorded in tv/UPSTREAM_IMPORT.json; authors credited in NOTICE.md.
 */
package com.cosmotune.music.ui.tv.player

import android.view.KeyEvent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.util.lerp
import androidx.media3.common.Player
import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.data.canvas.CanvasArtwork
import com.cosmotune.music.data.canvas.CanvasRepository
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.AppSettings
import com.cosmotune.music.playback.PlaybackPosition
import com.cosmotune.music.sharedui.resources.Res
import com.cosmotune.music.sharedui.resources.ic_dolby_atmos
import com.cosmotune.music.ui.icons.CosmotuneIcons
import com.cosmotune.music.ui.player.CanvasArtworkPlayer
import com.cosmotune.music.ui.player.SyncedLyricsPanel
import com.cosmotune.music.ui.tv.components.TvActivityIndicator
import com.cosmotune.music.ui.tv.components.TvArtwork
import com.cosmotune.music.ui.tv.components.TvButton
import com.cosmotune.music.ui.tv.components.TvLockup
import com.cosmotune.music.ui.tv.components.TvRow
import androidx.compose.foundation.shape.RoundedCornerShape
import com.cosmotune.music.ui.tv.components.drawFocusRing
import com.cosmotune.music.ui.tv.components.tvFocusRing
import com.cosmotune.music.ui.tv.components.tvInitialFocus
import com.cosmotune.music.ui.tv.theme.TvGlass
import com.cosmotune.music.ui.tv.theme.TvThemeColors
import com.cosmotune.music.ui.tv.theme.TvType
import com.cosmotune.music.ui.tv.theme.tvOrbitLook
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.orbit.OrbitBiteCard
import com.cosmotune.ui.orbit.OrbitPlayButton
import com.cosmotune.ui.orbit.OrbitRing
import com.cosmotune.ui.orbit.OrbitRoundButton
import com.cosmotune.ui.orbit.coverFill
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.painterResource
import java.util.Locale

/*
 * Now Playing on the TV in Orbit (board batch-be section E, made from the approved Now Playing board): the page wears the cover,
 * the song is a disc with the orbit round it, and the transport is the song's colour card at the foot with play in its bite.
 */

/** How far the transport card and its gap reach up from the foot of the screen; the stage keeps clear of it. */
private val TransportReach = 236.dp

// ─────────────────────────────────────────────────────────────────────────────
// Backdrop
// ─────────────────────────────────────────────────────────────────────────────

/** The ground, washed from the top in the cover's own colour (Orbit rule 10): flat, no blur. */
@Composable
fun TvPlayerBackground(
    artworkUrl: String?,
    modifier: Modifier = Modifier,
) {
    val ground = TvThemeColors.current.ground
    val tints = TvThemeColors.current.tintsCanvas
    val (fill, _) = coverFill(artworkUrl.takeIf { tints }, "player")
    val wash by animateColorAsState(if (tints && artworkUrl != null) fill else Color.Transparent, tween(700), label = "playerWash")
    Box(
        modifier = modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(ground)
                if (wash.alpha > 0f) {
                    drawRect(Brush.verticalGradient(0f to wash.copy(alpha = 0.85f * wash.alpha), 0.45f to wash.copy(alpha = 0.3f * wash.alpha), 0.9f to Color.Transparent))
                }
            },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Stage: the disc and the lyrics
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The song as a disc with the orbit round it (rules 1 and 2), and the lyrics
 * beside it when the song has them. Without lyrics the disc sits centred; it
 * glides to the left as lyrics arrive, and it shrinks and rises out of the
 * transport card's way while the card is up, so nothing sits on the cover. The
 * moves are layer transforms, so they cost no layout.
 */
@Composable
internal fun TvNowPlayingStage(
    song: Song,
    isPlaying: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    lyrics: List<LyricLine>,
    showLyrics: Boolean,
    lyricsLoading: Boolean,
    controlsVisible: Boolean,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val up by animateFloatAsState(if (controlsVisible) 1f else 0f, tween(360), label = "cardUp")
    val canvasEnabled by AppSettings.animatedCanvas.collectAsState()
    var canvas by remember(song.videoId) { mutableStateOf<CanvasArtwork?>(null) }
    LaunchedEffect(song.videoId, canvasEnabled) {
        canvas = if (canvasEnabled) CanvasRepository.canvasFor(song) else null
    }
    val side by animateFloatAsState(if (showLyrics) 1f else 0f, tween(520), label = "discToSide")

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The disc at its full size, the card down; every other state is a scale of it.
        val ring = (maxHeight * 0.66f).coerceAtMost(344.dp)
        val sideStart = 56.dp
        val sideScale = 0.86f
        val stageUp = maxHeight - TransportReach
        val upScale = ((stageUp - 36.dp) / ring).coerceIn(0.3f, 1f)
        val downX = (maxWidth - ring) / 2
        val downY = (maxHeight - ring) / 2 - 18.dp
        val width = maxWidth
        val height = maxHeight

        Box(
            modifier = Modifier
                .offset(x = downX, y = downY)
                .size(ring)
                .graphicsLayer {
                    val scale = lerp(1f, sideScale, side) * lerp(1f, upScale, up)
                    transformOrigin = TransformOrigin(0f, 0f)
                    scaleX = scale
                    scaleY = scale
                    val shown = ring.toPx() * scale
                    val x = lerp((width.toPx() - shown) / 2f, sideStart.toPx(), side)
                    val y = lerp((height.toPx() - shown) / 2f - 18.dp.toPx(), (stageUp.toPx() - shown) / 2f + 14.dp.toPx(), up)
                    translationX = x - downX.toPx()
                    translationY = y - downY.toPx()
                },
            contentAlignment = Alignment.Center,
        ) {
            val disc = ring - 30.dp
            Box(modifier = Modifier.size(disc).clip(CircleShape)) {
                TvArtwork(url = song.thumbnailUrl, px = 720, shape = CircleShape, modifier = Modifier.fillMaxSize())
                canvas?.let { CanvasArtworkPlayer(canvas = it, isPlaying = isPlaying, modifier = Modifier.fillMaxSize()) }
            }
            OrbitRing(
                ring,
                TvGlass.Ink,
                progress = { if (durationMs > 0) position.positionMs.toFloat() / durationMs else null },
                spinning = isPlaying,
                stroke = 3.dp,
                moon = 8.dp,
                moonColor = TvGlass.Peach,
            )
        }

        // With the card away, the song is named under the disc, riding the same glide to the side.
        val captionShown by animateFloatAsState(if (controlsVisible) 0f else 1f, tween(320), label = "captionFade")
        if (captionShown > 0f) {
            // Centred under the disc; beside the lyrics it is as wide as the disc's column, so a long title stops short of
            // both the screen's edge and the lyrics.
            val captionWidth = androidx.compose.ui.unit.lerp(ring + 120.dp, ring * sideScale + (sideStart - 24.dp) * 2, side)
            val captionCentre = androidx.compose.ui.unit.lerp(downX + ring / 2, sideStart + ring * sideScale / 2, side)
            Column(
                modifier = Modifier
                    .offset(x = captionCentre - captionWidth / 2, y = downY + ring + 12.dp)
                    .width(captionWidth)
                    .graphicsLayer {
                        alpha = captionShown
                        // The disc shrinks about its centre beside the lyrics, so its foot rises by half of what it loses.
                        translationY = lerp(0f, -ring.toPx() * (1f - sideScale) / 2f, side)
                    },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    text = song.title,
                    style = TvType.Title,
                    color = TvGlass.TextPrimary,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (song.artist.isNotBlank()) {
                    Text(
                        text = song.artist,
                        style = TvType.Body.copy(fontWeight = FontWeight.W500, fontSize = 15.sp),
                        color = TvGlass.TextSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }

        if (showLyrics || side > 0f) {
            Box(
                modifier = Modifier
                    .padding(start = sideStart + ring * sideScale + 48.dp, end = 48.dp)
                    .fillMaxHeight()
                    .graphicsLayer {
                        alpha = side
                        compositingStrategy = CompositingStrategy.Offscreen
                    }
                    .drawWithContent {
                        drawContent()
                        // The line that has gone by fades into the top of the screen instead of being cut by it, and while the
                        // card is up the lyrics fade out above it rather than run under it.
                        drawRect(
                            brush = Brush.verticalGradient(
                                0f to Color.Transparent,
                                0.07f to Color.Transparent,
                                0.2f to Color.Black,
                                0.5f to Color.Black,
                                0.62f to Color.Black.copy(alpha = 1f - up),
                                1f to Color.Black.copy(alpha = 1f - up),
                            ),
                            blendMode = BlendMode.DstIn,
                        )
                    },
            ) {
                // The shared panel's type is sized for a phone held at arm's
                // length. Shrink only the glyphs: density stays whole, so its
                // dp geometry still lands on pixels.
                val density = LocalDensity.current
                val lyricDensity = remember(density) {
                    Density(density = density.density, fontScale = density.fontScale * LYRIC_FONT_SCALE)
                }
                CompositionLocalProvider(LocalDensity provides lyricDensity) {
                    SyncedLyricsPanel(
                        lines = lyrics,
                        trackKey = song.videoId,
                        position = position,
                        looking = lyricsLoading,
                        isPlaying = isPlaying,
                        onSeekToLine = onSeek,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Transport: the song's colour card
// ─────────────────────────────────────────────────────────────────────────────

private const val LYRIC_FONT_SCALE = 0.8f

/**
 * The transport as the song's colour card (Orbit rules 3 and 4): flat in the
 * cover's colour, the mono label and quality over the title and the artist on the
 * left, Love, Lyrics, Up next and More as round buttons on the right, the seek
 * line with its moon and the times along the foot, and play in the bite. Up Next
 * opens under the card and pushes it up.
 *
 * D-pad: focus starts on the seek line (Left and Right scrub, Select plays or
 * pauses, Down opens Up Next); Up reaches the buttons; Right at the end of the
 * line, or from the buttons down and right, reaches play in the bite.
 */
@Composable
internal fun TvTransport(
    song: Song,
    isPlaying: Boolean,
    isLoading: Boolean,
    position: PlaybackPosition,
    durationMs: Long,
    scrubTarget: Long?,
    isLiked: Boolean,
    lyricsAvailable: Boolean,
    lyricsOn: Boolean,
    upNextOpen: Boolean,
    scrubberFocus: FocusRequester,
    onScrub: (Long) -> Unit,
    onSelectOnScrubber: () -> Unit,
    onTogglePlay: () -> Unit,
    onOpenUpNext: () -> Unit,
    onToggleLike: () -> Unit,
    onToggleLyrics: () -> Unit,
    onToggleUpNext: () -> Unit,
    onMore: () -> Unit,
    upNext: @Composable () -> Unit,
) {
    val showStats by AppSettings.showNerdStats.collectAsState()
    val (fill, ink) = coverFill(song.thumbnailUrl, song.videoId)
    val look = tvOrbitLook()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 48.dp, end = 48.dp, bottom = 24.dp),
    ) {
        OrbitBiteCard(
            fill = fill,
            modifier = Modifier.fillMaxWidth().height(196.dp),
            bite = {
                OrbitPlayButton(isPlaying, onTogglePlay, look, modifier = Modifier.tvFocusRing(CircleShape, gap = 2.dp))
            },
        ) {
            Column(modifier = Modifier.fillMaxSize().padding(start = 30.dp, end = 30.dp, top = 24.dp, bottom = 16.dp)) {
                Row(verticalAlignment = Alignment.Top) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Text(
                                text = (if (isPlaying) "Now orbiting" else "Paused").uppercase(),
                                style = TvType.Label,
                                color = ink.copy(alpha = 0.75f),
                            )
                            TvQualityLine(showStats = showStats, ink = ink)
                        }
                        Text(
                            text = song.title,
                            style = TvType.LargeTitle.copy(fontSize = 28.sp, lineHeight = 31.sp),
                            color = ink,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Text(
                            text = listOf(song.artist, song.albumName.orEmpty()).filter { it.isNotBlank() }.joinToString(" — "),
                            style = TvType.Body.copy(fontSize = 15.sp),
                            color = ink.copy(alpha = 0.85f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 4.dp),
                        )
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.padding(start = 20.dp)) {
                        TvCardButton(if (isLiked) CosmoIcons.HeartFill else CosmoIcons.Heart, if (isLiked) "Unlove" else "Love", ink, on = isLiked, onClick = onToggleLike)
                        TvCardButton(CosmoIcons.Lyrics, "Lyrics", ink, on = lyricsOn, enabled = lyricsAvailable, onClick = onToggleLyrics)
                        TvCardButton(CosmoIcons.Queue, "Up Next", ink, on = upNextOpen, onClick = onToggleUpNext)
                        TvCardButton(CosmoIcons.More, "More", ink, onClick = onMore)
                    }
                }
                Spacer(modifier = Modifier.weight(1f))
                Box(modifier = Modifier.padding(end = 92.dp)) {
                    TvScrubber(
                        position = position,
                        durationMs = durationMs,
                        isPlaying = isPlaying,
                        isLoading = isLoading,
                        scrubTarget = scrubTarget,
                        ink = ink,
                        focusRequester = scrubberFocus,
                        onScrub = onScrub,
                        onSelect = onSelectOnScrubber,
                        onDown = onOpenUpNext,
                    )
                }
            }
        }
        AnimatedVisibility(
            visible = upNextOpen,
            enter = expandVertically(tween(300)) + fadeIn(tween(240)),
            exit = shrinkVertically(tween(260)) + fadeOut(tween(180)),
        ) {
            Column {
                Spacer(modifier = Modifier.height(18.dp))
                upNext()
            }
        }
    }
}

/** A round tonal button on the card, in the card's own ink; [on] makes it solid. Ringed in peach on focus. */
@Composable
private fun TvCardButton(
    icon: ImageVector,
    label: String,
    ink: Color,
    on: Boolean = false,
    enabled: Boolean = true,
    size: Dp = 42.dp,
    onClick: () -> Unit,
) {
    OrbitRoundButton(
        icon = icon,
        label = label,
        ink = ink,
        on = on,
        size = size,
        onClick = { if (enabled) onClick() },
        modifier = Modifier
            .tvFocusRing(CircleShape)
            .graphicsLayer { alpha = if (enabled) 1f else 0.4f },
    )
}

// ─────────────────────────────────────────────────────────────────────────────
// Scrubber
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The seek line with its moon, in the card's ink (the kit's [com.cosmotune.ui.orbit.OrbitProgressLine] made for the remote): it is
 * drawn straight from [PlaybackPosition] on every frame while playing — carried forward between the player's readings by the time
 * they were taken — so it glides rather than stepping, and redraws only itself. With focus the line thickens and the moon turns
 * peach. Left and right scrub; the seek happens once the presses stop.
 */
@Composable
private fun TvScrubber(
    position: PlaybackPosition,
    durationMs: Long,
    isPlaying: Boolean,
    isLoading: Boolean,
    scrubTarget: Long?,
    ink: Color,
    focusRequester: FocusRequester,
    onScrub: (Long) -> Unit,
    onSelect: () -> Unit,
    onDown: () -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val frame = remember { mutableLongStateOf(0L) }
    LaunchedEffect(isPlaying) {
        if (isPlaying) while (true) withFrameNanos { frame.longValue = it }
    }
    val thickness by animateDpAsState(if (focused) 6.dp else 4.dp, tween(160), label = "scrubThickness")
    val moon by animateDpAsState(if (focused) 9.dp else 6.dp, tween(160), label = "scrubMoon")
    val ring by animateFloatAsState(if (focused) 1f else 0f, tween(120), label = "scrubRing")

    // Focus here is the peach ring round the line and its times, as everywhere else on the TV.
    Column(Modifier.drawBehind { drawFocusRing(RoundedCornerShape(18.dp), ring, gap = 6.dp) }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(24.dp)
                .focusRequester(focusRequester)
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.nativeKeyEvent.keyCode) {
                        KeyEvent.KEYCODE_DPAD_LEFT -> { onScrub(-10_000L); true }
                        KeyEvent.KEYCODE_DPAD_RIGHT -> {
                            // At the very end the press goes on to play in the bite instead.
                            if (durationMs > 0 && (scrubTarget ?: position.positionMs) >= durationMs - 1_000L) false else { onScrub(10_000L); true }
                        }
                        KeyEvent.KEYCODE_DPAD_DOWN -> { onDown(); true }
                        KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> {
                            onSelect(); true
                        }
                        else -> false
                    }
                }
                .focusable(interactionSource = interaction)
                .drawBehind {
                    val now = frame.longValue
                    val shown = scrubTarget ?: livePosition(position, isPlaying, now, durationMs)
                    val fraction = if (durationMs > 0) (shown.toFloat() / durationMs).coerceIn(0f, 1f) else 0f
                    val y = size.height / 2f
                    val w = thickness.toPx()
                    val x = size.width * fraction
                    drawLine(ink.copy(alpha = 0.22f), Offset(0f, y), Offset(size.width, y), w, StrokeCap.Round)
                    if (fraction > 0f) drawLine(ink, Offset(0f, y), Offset(x, y), w, StrokeCap.Round)
                    val m = if (focused) TvGlass.Peach else ink
                    drawCircle(m.copy(alpha = 0.22f), moon.toPx() + 5.dp.toPx(), Offset(x, y))
                    drawCircle(m, moon.toPx(), Offset(x, y))
                },
        )
        TvScrubTimes(
            position = position,
            durationMs = durationMs,
            scrubTarget = scrubTarget,
            isLoading = isLoading,
            ink = ink,
        )
    }
}

private fun livePosition(position: PlaybackPosition, isPlaying: Boolean, frameNanos: Long, durationMs: Long): Long {
    val read = position.positionMs
    if (!isPlaying || !position.advancing || position.sampledAtNanos == 0L || frameNanos == 0L) return read
    val carried = (frameNanos - position.sampledAtNanos) / 1_000_000L
    return (read + carried.coerceIn(0L, 1_500L)).coerceAtMost(durationMs.coerceAtLeast(read))
}

/** Elapsed on the left, remaining on the right, in the mono label — recomposed once a second, not once a frame. */
@Composable
private fun TvScrubTimes(
    position: PlaybackPosition,
    durationMs: Long,
    scrubTarget: Long?,
    isLoading: Boolean,
    ink: Color,
) {
    val second by remember(position) { derivedStateOf { position.positionMs / 1000L } }
    val shownMs = scrubTarget ?: (second * 1000L)
    Box(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = formatClock(shownMs),
            style = TvType.Label,
            color = if (scrubTarget != null) ink else ink.copy(alpha = 0.72f),
            modifier = Modifier.align(Alignment.CenterStart),
        )
        if (isLoading) {
            TvActivityIndicator(size = 13.dp, color = ink, modifier = Modifier.align(Alignment.Center))
        }
        Text(
            text = if (durationMs > 0) "-" + formatClock((durationMs - shownMs).coerceAtLeast(0L)) else "",
            style = TvType.Label,
            color = ink.copy(alpha = 0.72f),
            modifier = Modifier.align(Alignment.CenterEnd),
        )
    }
}

internal fun formatClock(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0L)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
}

// ─────────────────────────────────────────────────────────────────────────────
// Quality
// ─────────────────────────────────────────────────────────────────────────────

/**
 * The quality badge — Dolby Atmos, Hi-Res Lossless or Lossless — read from what's actually decoding, and with Stats for Nerds
 * on, the stream itself; in the card's ink.
 */
@Composable
private fun TvQualityLine(showStats: Boolean, ink: Color, modifier: Modifier = Modifier) {
    val snapshot by NerdStats.current.collectAsState()
    val stats = snapshot ?: return
    val label = when {
        stats.isDolbyAtmos -> "Dolby Atmos"
        stats.isHiRes -> "Hi-Res Lossless"
        stats.isLossless -> "Lossless"
        else -> null
    }
    val detail = if (showStats) streamDetail(stats) else null
    if (label == null && detail.isNullOrBlank()) return
    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (label != null) {
            Row(
                modifier = Modifier
                    .clip(CircleShape)
                    .background(ink.copy(alpha = 0.16f))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (stats.isDolbyAtmos) {
                    Icon(
                        painter = painterResource(Res.drawable.ic_dolby_atmos),
                        contentDescription = null,
                        tint = ink.copy(alpha = 0.85f),
                        modifier = Modifier.height(9.dp),
                    )
                }
                Text(
                    text = label.uppercase(),
                    style = TvType.Label,
                    color = ink.copy(alpha = 0.85f),
                )
            }
        }
        if (!detail.isNullOrBlank()) {
            Text(text = detail, style = TvType.Label, color = ink.copy(alpha = 0.6f), maxLines = 1)
        }
    }
}

private fun streamDetail(s: NerdStats.Snapshot): String {
    val codec = when (val mime = s.mimeType?.lowercase()) {
        null -> null
        "audio/flac" -> "FLAC"
        "audio/alac" -> "ALAC"
        "audio/mp4a-latm", "audio/aac" -> "AAC"
        "audio/opus" -> "Opus"
        "audio/vorbis" -> "Vorbis"
        "audio/mpeg" -> "MP3"
        "audio/eac3", "audio/eac3-joc" -> "Dolby Digital Plus"
        "audio/ac3" -> "Dolby Digital"
        "audio/ac4" -> "Dolby AC-4"
        "audio/raw" -> "PCM"
        else -> mime.substringAfter('/').uppercase()
    }
    val rate = s.sampleRateHz
    val depth = s.bitDepth
    val format = when {
        depth != null && rate != null -> "$depth-bit/${khz(rate)}"
        rate != null -> khz(rate)
        else -> null
    }
    val bitrate = s.bitrateKbps?.takeIf { it > 0 }?.let { "$it kbps" }
    return listOfNotNull(codec, format, bitrate, s.sourceName).joinToString(" · ")
}

private fun khz(hz: Int): String {
    val k = hz / 1000f
    return if (k % 1f == 0f) "${k.toInt()} kHz" else String.format(Locale.US, "%.1f kHz", k)
}

// ─────────────────────────────────────────────────────────────────────────────
// Up Next
// ─────────────────────────────────────────────────────────────────────────────

/** What plays after this song as a row of discs, with the queue's Shuffle, Repeat and Autoplay as round buttons. */
@Composable
internal fun TvUpNext(
    queue: List<Song>,
    currentIndex: Int,
    shuffle: Boolean,
    repeatMode: Int,
    autoplay: Boolean,
    firstCardFocus: FocusRequester,
    onJump: (Int) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onToggleAutoplay: () -> Unit,
) {
    val upcoming = remember(queue, currentIndex) { queue.drop(currentIndex + 1) }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Up next",
                style = TvType.Section,
                color = TvGlass.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                TvCardButton(CosmoIcons.Shuffle, "Shuffle", TvGlass.Ink, on = shuffle, size = 38.dp, onClick = onToggleShuffle)
                TvCardButton(
                    if (repeatMode == Player.REPEAT_MODE_ONE) CosmoIcons.RepeatOne else CosmoIcons.Repeat,
                    when (repeatMode) {
                        Player.REPEAT_MODE_ONE -> "Repeat One"
                        Player.REPEAT_MODE_ALL -> "Repeat All"
                        else -> "Repeat Off"
                    },
                    TvGlass.Ink,
                    on = repeatMode != Player.REPEAT_MODE_OFF,
                    size = 38.dp,
                    onClick = onCycleRepeat,
                )
                TvCardButton(CosmotuneIcons.Infinity, "Autoplay", TvGlass.Ink, on = autoplay, size = 38.dp, onClick = onToggleAutoplay)
            }
        }
        if (upcoming.isEmpty()) {
            Text(
                text = if (autoplay) "Similar music will play when this song ends." else "Nothing is queued after this song.",
                style = TvType.Callout,
                color = TvGlass.TextSecondary,
                modifier = Modifier
                    .padding(vertical = 21.dp)
                    .focusRequester(firstCardFocus)
                    .focusable(),
            )
        } else {
            val indexed = remember(upcoming) { upcoming.withIndex().toList() }
            TvRow(
                items = indexed,
                key = { "${it.value.videoId}#${it.index}" },
                // Room for the first card's focus ring inside the row.
                leading = 12.dp,
                trailing = 19.dp,
                spacing = 20.dp,
                top = 14.dp,
                bottom = 6.dp,
            ) { i, entry ->
                TvLockup(
                    title = entry.value.title,
                    subtitle = entry.value.artist,
                    artworkUrl = entry.value.thumbnailUrl,
                    width = 96.dp,
                    onClick = { onJump(currentIndex + 1 + i) },
                    modifier = if (i == 0) Modifier.focusRequester(firstCardFocus) else Modifier,
                )
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Feedback
// ─────────────────────────────────────────────────────────────────────────────

internal enum class TvFlash { Play, Pause, Next, Previous }

/**
 * The answer to a remote press while the card is down — play, pause, next,
 * previous — as the peach play button shows it, low on the screen and clear of
 * the disc. Shows, holds, fades.
 */
@Composable
internal fun TvFlashGlyph(flash: TvFlash?, tick: Int, modifier: Modifier = Modifier) {
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(tick) {
        if (tick == 0) return@LaunchedEffect
        alpha.snapTo(1f)
        delay(420)
        alpha.animateTo(0f, tween(360))
    }
    val shown = flash ?: return
    Box(
        modifier = modifier
            .padding(bottom = 36.dp)
            .size(72.dp)
            .graphicsLayer {
                this.alpha = alpha.value
                val s = 0.92f + 0.08f * alpha.value
                scaleX = s
                scaleY = s
            }
            .clip(CircleShape)
            .background(TvGlass.Peach),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = when (shown) {
                TvFlash.Play -> CosmoIcons.Play
                TvFlash.Pause -> CosmoIcons.Pause
                TvFlash.Next -> CosmoIcons.Next
                TvFlash.Previous -> CosmoIcons.Previous
            },
            contentDescription = null,
            tint = TvGlass.OnPeach,
            modifier = Modifier.size(28.dp),
        )
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Nothing playing
// ─────────────────────────────────────────────────────────────────────────────

@Composable
internal fun TvNothingPlaying(onBrowse: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(38.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(modifier = Modifier.size(120.dp), contentAlignment = Alignment.Center) {
            Box(Modifier.size(96.dp).clip(CircleShape).background(TvGlass.Fill), contentAlignment = Alignment.Center) {
                Icon(imageVector = CosmotuneIcons.MusicNote, contentDescription = null, tint = TvGlass.TextTertiary, modifier = Modifier.size(40.dp))
            }
            OrbitRing(120.dp, TvGlass.Ink, progress = null, spinning = false, stroke = 2.dp, moon = 5.dp)
        }
        Spacer(modifier = Modifier.height(22.dp))
        Text(text = "Not playing", style = TvType.Title, color = TvGlass.TextPrimary)
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = "Choose something from Home, Explore or your Library.",
            style = TvType.Callout,
            color = TvGlass.TextSecondary,
            textAlign = TextAlign.Center,
            modifier = Modifier.widthIn(max = 384.dp),
        )
        Spacer(modifier = Modifier.height(21.dp))
        TvButton(text = "Browse music", onClick = onBrowse, modifier = Modifier.width(208.dp).tvInitialFocus())
    }
}
