package com.cosmotune.ui.nowplaying

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cosmotune.lyrics.styles.PosterLines
import com.cosmotune.lyrics.styles.StageFit
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.orbit.STAND_INS
import com.cosmotune.ui.orbit.rememberCoverTint
import com.cosmotune.ui.theme.LocalReducedMotion
import java.util.Locale

/**
 * Poster's line laid out: each word in condensed caps at the size that fills the frame ([StageFit.posterSize]), placed row by row
 * from the top left. Built once a line.
 */
internal class PosterScene(
    val stage: StageLine,
    val words: Array<TextLayoutResult>,
    val x: FloatArray,
    val y: FloatArray,
) {
    companion object {
        fun build(measurer: TextMeasurer, stage: StageLine, fonts: LyricStageFonts, density: Density, width: Float, height: Float): PosterScene {
            val n = stage.count
            val text = Array(n) { stage.text.substring(stage.charStart[it], stage.charEnd[it]).uppercase(Locale.ROOT) }
            // Fitted with the row height the line's scripts need (fallback glyphs are taller than Archivo's caps)...
            val factor = PosterLines.factor(stage.text)
            val ref = 100f
            val refStyle = stageStyle(fonts.poster, StageWeights.POSTER, ref, density, LINE)
            val widths = FloatArray(n) { measurer.measure(text[it], refStyle, softWrap = false, maxLines = 1).size.width.toFloat() }
            val space = measurer.measure("M", refStyle, softWrap = false, maxLines = 1).size.width * 0.32f
            val rowStart = IntArray(n.coerceAtLeast(1))
            val fit = StageFit.posterSize(widths, n, space, ref, factor, width, height, minSize = 12f, maxSize = height / 2.2f, rowStart = rowStart)
            // ...then placed by the shaped text itself: a row is never shorter than its tallest word's glyphs, so no script runs
            // into the next row. Shrunk a step at a time if the measured rows come out taller than the frame.
            var size = fit.size
            var placed = place(measurer, text, widths, space, ref, fonts, density, size, fit.onePerRow, width, rowStart)
            var tries = 0
            while (placed.second > height && size > 12f && tries < 8) {
                size *= (height / placed.second).coerceIn(0.7f, 0.95f)
                placed = place(measurer, text, widths, space, ref, fonts, density, size, fit.onePerRow, width, rowStart)
                tries++
            }
            return placed.first.let { (words, xs, ys) -> PosterScene(stage, words, xs, ys) }
        }

        /** The words at [size], packed into rows and each row set at the height its shaped glyphs need; and the total height. */
        private fun place(
            measurer: TextMeasurer,
            text: Array<String>,
            widthsAtRef: FloatArray,
            spaceAtRef: Float,
            ref: Float,
            fonts: LyricStageFonts,
            density: Density,
            size: Float,
            onePerRow: Boolean,
            width: Float,
            rowStart: IntArray,
        ): Pair<Triple<Array<TextLayoutResult>, FloatArray, FloatArray>, Float> {
            val n = text.size
            val k = size / ref
            val rows = StageFit.packRows(FloatArray(n) { widthsAtRef[it] * k }, n, spaceAtRef * k, width, onePerRow, rowStart)
            // The natural line height (no fixed line height) carries the real ascent and descent of whatever font drew the glyphs.
            val style = stageStyle(fonts.poster, StageWeights.POSTER, size, density, LINE).copy(lineHeight = TextUnit.Unspecified)
            val words = Array(n) { measurer.measure(text[it], style, softWrap = false, maxLines = 1) }
            val xs = FloatArray(n)
            val ys = FloatArray(n)
            var top = 0f
            for (r in 0 until rows) {
                val first = rowStart[r]
                val end = if (r + 1 < rows) rowStart[r + 1] else n
                var tight = true
                var ascent = 0f
                var descent = 0f
                for (i in first until end) {
                    if (!PosterLines.tight(text[i])) tight = false
                    ascent = maxOf(ascent, words[i].firstBaseline)
                    descent = maxOf(descent, words[i].size.height - words[i].firstBaseline)
                }
                // Archivo's caps alone sit tight, as on the board; anything else gets the room its glyphs measure.
                val baseline = if (tight) top + size * CAPS else top + ascent
                val rowHeight = if (tight) size * LINE else ascent + descent
                var x = 0f
                for (i in first until end) {
                    xs[i] = x
                    ys[i] = baseline - words[i].firstBaseline
                    x += words[i].size.width + spaceAtRef * k
                }
                top += rowHeight
            }
            return Triple(words, xs, ys) to top
        }

        /** Where the caps' baseline sits in a tight row, as a fraction of the size. */
        private const val CAPS = 0.8f

        /** Rows sit tight, as on the board (`line-height: .86`). */
        const val LINE = 0.88f
    }
}

/** Draws [scene] from [origin]: each word at [UNSUNG_ALPHA] of [ink] until it is sung, then whole, fading in over [WORD_LIGHT_MS]. */
internal fun DrawScope.drawPoster(scene: PosterScene, positionMs: Long, ink: Color, origin: Offset, reduced: Boolean) {
    val stage = scene.stage
    for (i in 0 until scene.words.size) {
        val since = positionMs - stage.startMs[i]
        val lit = if (since < 0L) 0f else if (reduced) 1f else (since / WORD_LIGHT_MS).coerceIn(0f, 1f)
        val alpha = UNSUNG_ALPHA + (1f - UNSUNG_ALPHA) * lit
        drawText(scene.words[i], ink.copy(alpha = ink.alpha * alpha), Offset(origin.x + scene.x[i], origin.y + scene.y[i]))
    }
}

/** A word still to come is a shadow of itself on the poster (the board's `.18`). */
internal const val UNSUNG_ALPHA = 0.18f

/** The poster's colours: the cover's own deep colour and its pale ink, in either theme (a colour card is deep in both). */
@Composable
internal fun posterColors(song: LyricStageSong?): Pair<Color, Color> {
    val tint = rememberCoverTint(song?.coverUrl)
    val stand = STAND_INS[Math.floorMod((song?.coverUrl ?: song?.title ?: "").hashCode(), STAND_INS.size)]
    val fill by animateColorAsState(tint?.fill ?: stand, tween(450), label = "posterFill")
    return fill to (tint?.ink ?: POSTER_INK)
}

internal val POSTER_INK = Color(0xFFFFE3CC)

/**
 * Poster (board 06): the line as a typographic poster in the cover's colour, condensed caps filling the card, a label at the top
 * naming the song, and at the foot the next line and the cover as a small disc. The card shared as video, and the TV's ambient mode.
 */
@Composable
internal fun PosterStage(cursor: StageCursor, song: LyricStageSong?, onSeek: (Long) -> Unit, onLongPress: (() -> Unit)?, modifier: Modifier, corner: Float = 30f) {
    val reduced = LocalReducedMotion.current
    val fonts = rememberLyricStageFonts()
    val measurer = rememberTextMeasurer(cacheSize = 32)
    val density = LocalDensity.current
    val (fill, ink) = posterColors(song)
    val lines = cursor.lines
    val focus = cursor.focus.value
    val next = cursor.next.value
    val label = remember(song) { posterLabel(song) }
    val labelStyle = remember { TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.SemiBold, fontSize = 10.5.sp, letterSpacing = 1.3.sp) }
    BoxWithConstraints(modifier.clip(RoundedCornerShape(corner.dp)).background(fill)) {
        val pad = with(density) { 22.dp.toPx() }
        val foot = with(density) { 64.dp.toPx() }
        val head = with(density) { 34.dp.toPx() }
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        val scene = remember(lines, focus, fonts, density, width, height) {
            stageLineOf(lines, focus)?.let { PosterScene.build(measurer, it, fonts, density, (width - pad * 2).coerceAtLeast(1f), (height - head - foot - pad).coerceAtLeast(1f)) }
        }
        val nextLayout = remember(lines, next, fonts, density, width) {
            lines.getOrNull(next)?.let { measurer.measure(it.text, stageTextStyle(fonts, with(density) { 13.sp.toPx() }, density), overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (width - pad * 2 - with(density) { 70.dp.toPx() }).toInt().coerceAtLeast(1))) }
        }
        val labelLayout = remember(label, width) { measurer.measure(label, labelStyle, overflow = TextOverflow.Ellipsis, maxLines = 1, constraints = Constraints(maxWidth = (width - pad * 2).toInt().coerceAtLeast(1))) }
        val clock = cursor.clock
        Spacer(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = scene?.stage?.text.orEmpty() }
                .stageTouch(
                    target = { at -> if (at.y >= height - foot) lines.getOrNull(next)?.timeMs else scene?.stage?.line?.timeMs },
                    onSeek = onSeek, onLongPress = onLongPress,
                )
                .drawBehind {
                    drawText(labelLayout, ink.copy(alpha = 0.6f), Offset(pad, pad))
                    if (scene != null) drawPoster(scene, clock.longValue, ink, Offset(pad, head + pad * 0.6f), reduced)
                    nextLayout?.let { drawText(it, ink.copy(alpha = 0.6f), Offset(pad, size.height - pad - it.size.height - with(density) { 10.dp.toPx() })) }
                },
        )
        Box(Modifier.align(Alignment.BottomEnd).padding(18.dp).size(54.dp).border(3.dp, Color(0xFF1C0F08).copy(alpha = 0.5f), CircleShape).padding(3.dp)) {
            Disc(song?.coverUrl, 48.dp)
        }
    }
}

/** "ALBUM · ARTIST" (or the title, when there is no album), for the top of the poster. */
internal fun posterLabel(song: LyricStageSong?): String {
    if (song == null) return "COSMOTUNE"
    val first = song.album?.takeIf { it.isNotBlank() } ?: song.title
    return listOf(first, song.artist).filter { it.isNotBlank() }.joinToString(" · ").uppercase(Locale.ROOT)
}
