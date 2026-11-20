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
import com.cosmotune.ui.theme.cosmoPlayerGroundEdges
import androidx.compose.ui.layout.layout
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Now Playing in a wide window (the desktop, or a tablet on its side; the Now Playing board, section D), on the ambient ground
 * that wears the cover:
 *  - the [topRow] across the top (close, "FROM · source", what is switched on, more), in the window's caption area so an empty
 *    stretch of it still moves the window;
 *  - on the left the [media] (the video at its own shape, or the cover centred over a blurred copy of itself), running to the
 *    window's start edge and fading into the page at its top and its foot, over the song [card];
 *  - on the right the [tabs] (Lyrics, Up next with its count, Related) over the [panel] they chose.
 * The left column takes about two thirds; its card is the wide one (the title left, the buttons right, the line and its times
 * across the foot) once the column is 640 dp or more.
 *
 * Everything is marked as what the sheets blur ([hazeState]) and keeps clear of the system bars and the display cutout.
 */
@Composable
internal fun NowPlayingWide(
    hazeState: HazeState,
    topRow: @Composable (Modifier) -> Unit,
    media: @Composable (Modifier) -> Unit,
    card: @Composable (modifier: Modifier, wide: Boolean) -> Unit,
    tabs: @Composable (Modifier) -> Unit,
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
    Box(modifier.fillMaxSize().hazeSource(hazeState)) {
        Box(Modifier.fillMaxSize().cosmoAmbientGround(artwork = true))
        Column(Modifier.fillMaxSize().padding(start = left + SIDE_START, end = right + SIDE_END, bottom = bottom + FOOT)) {
            com.cosmotune.ui.shell.WindowCaptionArea(Modifier.fillMaxWidth()) {
                topRow(Modifier.fillMaxWidth().padding(top = top + TOP))
            }
            Spacer(Modifier.height(20.dp))
            Row(Modifier.weight(1f).fillMaxWidth()) {
                BoxWithConstraints(Modifier.weight(SPLIT).fillMaxHeight()) {
                    val wideCard = maxWidth >= WIDE_CARD_MIN
                    Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                        // To the window's start edge, past the page's margin; the page's ground fades over its top and foot.
                        Box(Modifier.weight(1f).fillMaxWidth().bleedStart(left + SIDE_START)) {
                            media(Modifier.fillMaxSize())
                            Box(Modifier.matchParentSize().cosmoPlayerGroundEdges(top = SOFT_TOP, bottom = SOFT_BOTTOM))
                        }
                        Spacer(Modifier.height(18.dp))
                        card(Modifier.fillMaxWidth().then(if (wideCard) Modifier.height(WIDE_CARD_HEIGHT) else Modifier), wideCard)
                    }
                }
                Spacer(Modifier.width(COLUMN_GAP))
                Column(Modifier.weight(1f - SPLIT).fillMaxHeight()) {
                    tabs(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(12.dp))
                    panel(Modifier.weight(1f).fillMaxWidth())
                }
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

/** Widens the box by [bleed] on its start side, so it runs past the page's margin to the window's edge. */
private fun Modifier.bleedStart(bleed: Dp): Modifier = layout { measurable, constraints ->
    val extra = bleed.roundToPx()
    val wanted = if (constraints.hasBoundedWidth) constraints.maxWidth + extra else constraints.maxWidth
    val placeable = measurable.measure(constraints.copy(minWidth = wanted, maxWidth = wanted))
    layout(constraints.maxWidth, placeable.height) { placeable.placeRelative(-extra, 0) }
}

/** The left column's share of the width (the board's 860 of 1280). */
private const val SPLIT = 0.68f

private val SIDE_START = 40.dp
private val SIDE_END = 28.dp
private val TOP = 20.dp
private val FOOT = 18.dp
private val COLUMN_GAP = 28.dp

/** From this column width the card is the wide one (board section 1), and its height then. */
private val WIDE_CARD_MIN = 640.dp
private val WIDE_CARD_HEIGHT = 188.dp
