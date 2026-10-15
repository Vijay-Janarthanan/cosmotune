package com.cosmotune.ui.nowplaying

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import com.cosmotune.design.NowPlayingMetrics
import com.cosmotune.design.Scrim
import com.cosmotune.theme.CoverField
import com.cosmotune.ui.components.CoverImage
import com.cosmotune.ui.theme.CosmoRadii
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.cosmoAmbientGround
import com.cosmotune.ui.theme.drawEllipseGlow
import com.music.bitchord.data.canvas.CanvasArtwork
import com.music.bitchord.ui.player.CanvasArtworkPlayer
import com.music.bitchord.ui.player.CanvasContentMode
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.hazeSource

/**
 * Everything behind the controls (z 0 and 1 of the board): the ambient ground while the video is not all the way in, the
 * looping clip when one is wanted, the scrims over it and the glow at its foot. [hazeState] marks the whole of it as what the
 * sheets blur ([hazeSource]); the video is sampled there exactly as the old player's was.
 *
 * [reveal] (0 = the cover shows, 1 = the video is all the way in) is a [State] and is read only in layer and draw lambdas and
 * in two `derivedStateOf`: a fade of the video recomposes nothing. The clip's own alpha goes through `presentationAlpha`, not
 * a Compose layer: on the phone the clip is a `TextureView`, which does not reliably take a Compose layer's alpha (the old
 * player says so at `CanvasArtworkPlayer`'s `update`).
 */
@Composable
internal fun NowPlayingBackdrop(
    canvas: CanvasArtwork?,
    wantVideo: Boolean,
    isPlaying: Boolean,
    reveal: State<Float>,
    lyricsOpen: Boolean,
    onRenderedChanged: (Boolean) -> Unit,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
) {
    // The spring settles on 1 a little at a time; the clip is opaque long before, so the ground goes at 99.9 %.
    val groundShown by remember(reveal) { derivedStateOf { reveal.value < 0.999f } }
    val scrimsShown by remember(reveal) { derivedStateOf { reveal.value > 0f } }
    Box(modifier.hazeSource(hazeState)) {
        // Under the video, which covers it from reveal 1: not drawn from then on.
        if (groundShown) Box(Modifier.fillMaxSize().cosmoAmbientGround())
        if (wantVideo && canvas != null) {
            CanvasArtworkPlayer(
                canvas = canvas,
                isPlaying = isPlaying,
                modifier = Modifier.fillMaxSize(),
                contentMode = CanvasContentMode.CROP,
                onRenderedChanged = onRenderedChanged,
                presentationAlpha = { reveal.value.coerceIn(0f, 1f) },
            )
        }
        if (scrimsShown) Box(Modifier.fillMaxSize().nowPlayingScrims(reveal, lyricsOpen))
    }
}

/**
 * The scrims and the glow over the video (`.np2-top-scrim`, `.np2-bottom-scrim`, `.np2-glow`), faded in by [reveal] as one layer.
 * The top band is [Scrim.TOP_BAND_DP] dp tall; the bottom band [Scrim.BOTTOM_BAND_DP] dp, or with [lyricsOpen] one gradient
 * over the whole height ([Scrim.LYRICS_STOPS]); the glow is [NowPlayingMetrics.GLOW_BAND_DP] dp at the bottom, over the scrims.
 * Every colour is the palette's on screen this frame, read inside the cache block, so a palette fade rebuilds the brushes
 * (a few a frame, only while it runs) and recomposes nothing. Nothing is allocated while the picture is still.
 */
@Composable
private fun Modifier.nowPlayingScrims(reveal: State<Float>, lyricsOpen: Boolean): Modifier {
    val colors = LocalCosmoColors.current
    return graphicsLayer { alpha = reveal.value.coerceIn(0f, 1f) }.drawWithCache {
        val width = size.width
        val height = size.height
        val scrim = colors.live(CoverField.SCRIM)
        val topHeight = minOf(Scrim.TOP_BAND_DP.dp.toPx(), height)
        val topBrush = Brush.verticalGradient(colorStops = scrimStops(Scrim.TOP_STOPS, scrim), startY = 0f, endY = topHeight)
        val bottomHeight = minOf(Scrim.BOTTOM_BAND_DP.dp.toPx(), height)
        val bottomTop = height - bottomHeight
        val bottomBrush = if (lyricsOpen) {
            Brush.verticalGradient(colorStops = scrimStops(Scrim.LYRICS_STOPS, scrim), startY = 0f, endY = height)
        } else {
            Brush.verticalGradient(colorStops = scrimStops(Scrim.BOTTOM_STOPS, scrim), startY = bottomTop, endY = height)
        }
        val glow = colors.live(CoverField.GLOW)
        val glowStops = arrayOf(
            0f to glow.copy(alpha = glow.alpha * NowPlayingMetrics.GLOW_ALPHA_INNER),
            NowPlayingMetrics.GLOW_MID_AT to glow.copy(alpha = glow.alpha * NowPlayingMetrics.GLOW_ALPHA_MID),
            NowPlayingMetrics.GLOW_END_AT to glow.copy(alpha = 0f),
        )
        val band = minOf(NowPlayingMetrics.GLOW_BAND_DP.dp.toPx(), height)
        onDrawBehind {
            drawRect(topBrush, size = Size(width, topHeight))
            if (lyricsOpen) drawRect(bottomBrush, size = Size(width, height))
            else drawRect(bottomBrush, topLeft = Offset(0f, bottomTop), size = Size(width, bottomHeight))
            // The glow is a radial gradient squashed to an ellipse, centred 1.15 bands down its own band.
            clipRect(0f, height - band, width, height) {
                translate(0f, height - band) {
                    drawEllipseGlow(
                        glowStops,
                        cx = 0.5f * width,
                        cy = NowPlayingMetrics.GLOW_CY_OF_BAND * band,
                        rx = NowPlayingMetrics.GLOW_RX_OF_WIDTH * width,
                        ry = NowPlayingMetrics.GLOW_RY_OF_BAND * band,
                    )
                }
            }
        }
    }
}

/** (position, alpha) pairs flattened in [stops] become gradient stops of [scrim] at that alpha. */
private fun scrimStops(stops: FloatArray, scrim: Color): Array<Pair<Float, Color>> =
    Array(stops.size / 2) { i -> stops[2 * i] to scrim.copy(alpha = scrim.alpha * stops[2 * i + 1]) }

/**
 * The cover of the song, without a video (`.np2.no-video .np2-art`): [NowPlayingMetrics.COVER_DP] square at most, centred,
 * 112 dp from the top, with its own shadow, on the ambient ground behind it. It is placed by its own layout: its side is
 * [NowPlayingMetrics.coverSideDp] of the window and of the stack under it (read from [stackHeightPx] in the layout phase, so a
 * title that grows a line moves the cover without recomposing it).
 *
 * As [reveal] goes from 0 to 1 the cover travels (a layer: translation and scale, nothing is laid out again) into the chip
 * slot of the artist row, whose top-left corner is [chipOrigin] in root coordinates; in the last stretch it fades out while the
 * real 32 dp picture in the slot fades in ([NowPlayingMetrics.chipShare]). [shell] gets the cover's resting rectangle as
 * `playerCoverBounds`, which the player's open and close morph lands its travelling cover on.
 *
 * The travelling layer is inside the box that reports the rectangle, so the rectangle is always the resting one, whatever the
 * layer does. `playerCoverBounds` keeps the last rectangle when the page leaves composition, so the next opening's first frame has a place to land.
 */
@Composable
internal fun NowPlayingCover(
    url: String?,
    reveal: State<Float>,
    chipOrigin: State<Offset?>,
    stackHeightPx: State<Int>,
    shell: CosmoShellState?,
    modifier: Modifier = Modifier,
) {
    val rest = remember { mutableStateOf<Rect?>(null) }
    Layout(
        content = {
            Box(
                Modifier.onGloballyPositioned { coordinates ->
                    val bounds = coordinates.boundsInRoot()
                    rest.value = bounds
                    shell?.playerCoverBounds = bounds
                },
            ) {
                Box(
                    Modifier.fillMaxSize().graphicsLayer {
                        val progress = reveal.value
                        val from = rest.value
                        val to = chipOrigin.value
                        if (progress > 0f && from != null && to != null && from.width > 0f) {
                            val chip = NowPlayingMetrics.CHIP_DP.dp.toPx()
                            translationX = NowPlayingMetrics.travel(progress, from.center.x, to.x + chip / 2f)
                            translationY = NowPlayingMetrics.travel(progress, from.center.y, to.y + chip / 2f)
                            val scale = NowPlayingMetrics.coverScale(progress, from.width / density)
                            scaleX = scale
                            scaleY = scale
                        }
                        alpha = 1f - NowPlayingMetrics.chipShare(progress)
                    },
                ) {
                    CoverImage(url, NowPlayingMetrics.COVER_DP.dp, CosmoRadii.coverNowPlaying, Modifier)
                }
            }
        },
        modifier = modifier.fillMaxSize(),
    ) { measurables, constraints ->
        val windowWidthDp = constraints.maxWidth.toDp().value
        val windowHeightDp = constraints.maxHeight.toDp().value
        val sideDp = NowPlayingMetrics.coverSideDp(windowWidthDp, windowHeightDp, stackHeightPx.value.toDp().value)
        val side = sideDp.dp.roundToPx()
        val placeable = measurables.first().measure(Constraints.fixed(side, side))
        layout(constraints.maxWidth, constraints.maxHeight) {
            placeable.place((constraints.maxWidth - side) / 2, NowPlayingMetrics.COVER_TOP_DP.dp.roundToPx())
        }
    }
}
