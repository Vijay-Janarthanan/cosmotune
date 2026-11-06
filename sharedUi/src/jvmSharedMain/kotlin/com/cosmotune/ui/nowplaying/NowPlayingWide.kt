package com.cosmotune.ui.nowplaying

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.cosmotune.ui.theme.cosmoAmbientGround
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Now Playing in a wide window (the desktop, or a tablet on its side; the Orbit board, sections 1 and 3), on the ambient ground
 * that wears the cover:
 *  - the [topRow] across the top (close, "FROM · source", the Lyrics / Up next / Related chips, more), in the window's caption
 *    area so an empty stretch of it still moves the window;
 *  - on the left the [media] (the video, rounded 34, or without one the record) over the song [card];
 *  - on the right the [panel] the chips chose (Up next as Home's song rows, the lyrics in the wide face, or Related).
 * With a [video] the left column takes about two thirds and the card is the wide one (the title left, the buttons right, the line
 * across its foot) once the column is 640 dp or more; with the record the columns are about even and the card is the compact one.
 *
 * Everything is marked as what the sheets blur ([hazeState]) and keeps clear of the system bars and the display cutout.
 */
@Composable
internal fun NowPlayingWide(
    hazeState: HazeState,
    video: Boolean,
    topRow: @Composable (Modifier) -> Unit,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (modifier: Modifier, wide: Boolean) -> Unit,
    panel: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val direction = LocalLayoutDirection.current
    // The status bar's stable inset is folded in: hiding the bar in the player drops it out of systemBars, and the layout keeps its place.
    val insets = WindowInsets.systemBars.union(WindowInsets.displayCutout).union(com.cosmotune.music.ui.theme.stableStatusBarInsets())
    val left = with(density) { insets.getLeft(density, direction).toDp() }
    val top = with(density) { insets.getTop(density).toDp() }
    val right = with(density) { insets.getRight(density, direction).toDp() }
    val bottom = with(density) { insets.getBottom(density).toDp() }
    val split by animateFloatAsState(if (video) VIDEO_SPLIT else RECORD_SPLIT, tween(420), label = "split")
    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        Box(Modifier.fillMaxSize().cosmoAmbientGround(artwork = true))
        Column(Modifier.fillMaxSize().padding(start = left + SIDE_START, end = right + SIDE_END, bottom = bottom + FOOT)) {
            com.cosmotune.ui.shell.WindowCaptionArea(Modifier.fillMaxWidth()) {
                topRow(Modifier.fillMaxWidth().padding(top = top + TOP))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(split).fillMaxHeight()) {
                    val wideCard = video && maxWidth >= WIDE_CARD_MIN
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        media(Modifier.weight(1f).fillMaxWidth())
                        Spacer(Modifier.height(18.dp))
                        card(Modifier.fillMaxWidth().then(if (wideCard) Modifier.height(WIDE_CARD_HEIGHT) else Modifier), wideCard)
                    }
                }
                Spacer(Modifier.width(COLUMN_GAP))
                panel(Modifier.weight(1f - split).fillMaxHeight())
            }
        }
    }
}

/** The right column (or the phone's panel): the chosen panel, crossing over in [PANEL_FADE_MS]. */
@Composable
internal fun NowPlayingPanelSwitch(
    panel: NowPlayingPanel,
    lyrics: @Composable (Modifier) -> Unit,
    upNext: @Composable (Modifier) -> Unit,
    related: @Composable (Modifier) -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedContent(panel, modifier, transitionSpec = { fadeIn(tween(PANEL_FADE_MS)) togetherWith fadeOut(tween(PANEL_FADE_MS)) }, label = "panel") { shown ->
        when (shown) {
            NowPlayingPanel.UP_NEXT -> upNext(Modifier.fillMaxSize())
            NowPlayingPanel.RELATED -> related(Modifier.fillMaxSize())
            else -> lyrics(Modifier.fillMaxSize())
        }
    }
}

/** The record in a media box: as large as the box allows (the ring included), centred. */
@Composable
internal fun RecordBox(modifier: Modifier, maxDisc: Dp = 440.dp, record: @Composable (disc: Dp) -> Unit) {
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val disc = (minOf(maxWidth, maxHeight) - RECORD_RING_GAP * 2 - 8.dp).coerceIn(120.dp, maxDisc)
        record(disc)
    }
}

/** The left column's share with a video, and with the record. */
private const val VIDEO_SPLIT = 0.68f
private const val RECORD_SPLIT = 0.48f

private val SIDE_START = 40.dp
private val SIDE_END = 28.dp
private val TOP = 20.dp
private val FOOT = 18.dp
private val COLUMN_GAP = 28.dp

/** From this column width the card is the wide one (board section 1), and its height then. */
private val WIDE_CARD_MIN = 640.dp
private val WIDE_CARD_HEIGHT = 188.dp
