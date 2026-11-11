package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.lyrics.LyricTextDirection
import com.cosmotune.lyrics.styles.LyricArc
import com.cosmotune.lyrics.styles.StageLine
import com.cosmotune.ui.orbit.Disc
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalReducedMotion
import kotlin.math.hypot
import kotlin.math.min

/**
 * A line set on the upper arc of a ring, glyph by glyph: each glyph is measured flat in its arc's own layout (so kerning is kept)
 * and laid out on its own, then turned to its angle at draw time. A line too long for the upper arc at [LyricArc.SCALE_FLOOR] of
 * its size is split over two concentric arcs, its first words on the outer one ([LyricArc.fitUpper]). Scripts whose letters join
 * or stack (Arabic, Devanagari and the rest) are cut at the spaces instead, a word to a piece, so nothing that shapes across
 * letters is cut apart. Built once a line.
 */
internal class ArcText(
    val text: String,
    /** The pieces: their layouts, the characters each covers, its width, its ring and its angle there, and its arc's start. */
    val pieces: Array<TextLayoutResult>,
    val pieceStart: IntArray,
    val pieceEnd: IntArray,
    val widths: FloatArray,
    val radii: FloatArray,
    val angles: FloatArray,
    val starts: FloatArray,
    /** Each character's start and end angle, in reading order (so right to left on a right-to-left line). */
    private val charFrom: FloatArray,
    private val charTo: FloatArray,
    val baseline: Float,
    /** Whether the line is on two arcs, which moves the next line's ring out. */
    val split: Boolean,
) {
    val count: Int get() = pieces.size

    /** The angle at [chars] of the text sung: the sweep's edge, where the moon is. */
    fun angleAtChars(chars: Float): Float {
        val length = text.length
        if (length == 0) return LyricArc.TOP_DEG
        val c = chars.coerceIn(0f, length.toFloat())
        val whole = c.toInt().coerceAtMost(length - 1)
        return if (c >= length) charTo[length - 1] else charFrom[whole] + (charTo[whole] - charFrom[whole]) * (c - whole)
    }

    /** The angle of piece [i] when its ring is drawn at [at] instead of its own (sliding in from the outer ring). */
    fun angleOn(i: Int, at: Float): Float = starts[i] + (angles[i] - starts[i]) * (radii[i] / at.coerceAtLeast(1f))

    companion object {
        /**
         * Sets [text] in [style] on the upper arc of [inner], or over [inner] and [outer] when it is too long for one arc ([outer]
         * null: one arc whatever the length, smaller as it must be; the next line's ring).
         */
        fun build(measurer: TextMeasurer, text: String, style: TextStyle, inner: Float, outer: Float?): ArcText {
            // The words and their widths at full size.
            val wordStart = ArrayList<Int>()
            val wordEnd = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) { i++; continue }
                val from = i
                while (i < text.length && !text[i].isWhitespace()) i++
                wordStart += from
                wordEnd += i
            }
            val full = measurer.measure(text, style, softWrap = false, maxLines = 1)
            val widths = FloatArray(wordStart.size) { w ->
                val a = full.getBoundingBox(wordStart[w])
                val b = full.getBoundingBox(wordEnd[w] - 1)
                maxOf(a.right, b.right) - minOf(a.left, b.left)
            }
            val space = measurer.measure("a a", style, softWrap = false).size.width - measurer.measure("aa", style, softWrap = false).size.width.toFloat()
            val fit = LyricArc.fitUpper(widths, widths.size, space, inner, outer)
            val used = if (fit.scale < 1f) style.copy(fontSize = style.fontSize * fit.scale, lineHeight = style.lineHeight * fit.scale) else style
            // The arcs: the whole line on the inner one, or its first words on the outer one and the rest on the inner.
            val arcs = if (fit.split < 0 || outer == null) {
                listOf(Triple(0, text.length, inner))
            } else {
                listOf(Triple(0, wordEnd[fit.split - 1], outer), Triple(wordStart[fit.split], text.length, inner))
            }
            val byGlyph = glyphSafe(text)
            val pieces = ArrayList<TextLayoutResult>()
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            val pieceWidths = ArrayList<Float>()
            val radii = ArrayList<Float>()
            val angles = ArrayList<Float>()
            val arcStarts = ArrayList<Float>()
            val charFrom = FloatArray(text.length)
            val charTo = FloatArray(text.length)
            var baseline = 0f
            for ((from, until, radius) in arcs) {
                val segment = text.substring(from, until)
                val flat = measurer.measure(segment, used, softWrap = false, maxLines = 1)
                baseline = flat.firstBaseline
                val start = LyricArc.upperStart(LyricArc.sweepDeg(flat.size.width.toFloat(), radius))
                for (c in segment.indices) {
                    val box = flat.getBoundingBox(c)
                    val rtl = flat.getParagraphDirection(c) == ResolvedTextDirection.Rtl
                    charFrom[from + c] = LyricArc.angleAt(if (rtl) box.right else box.left, radius, start)
                    charTo[from + c] = LyricArc.angleAt(if (rtl) box.left else box.right, radius, start)
                }
                var j = 0
                while (j < segment.length) {
                    if (segment[j].isWhitespace()) { j++; continue }
                    val pieceFrom = j
                    j = if (byGlyph) j + 1 else { var k = j; while (k < segment.length && !segment[k].isWhitespace()) k++; k }
                    val a = flat.getBoundingBox(pieceFrom)
                    val b = flat.getBoundingBox(j - 1)
                    val left = minOf(a.left, b.left)
                    val width = maxOf(a.right, b.right) - left
                    pieces += measurer.measure(segment.substring(pieceFrom, j), used, softWrap = false, maxLines = 1)
                    starts += from + pieceFrom
                    ends += from + j
                    pieceWidths += width
                    radii += radius
                    angles += LyricArc.angleAt(left + width / 2f, radius, start)
                    arcStarts += start
                }
            }
            // Characters between the two arcs (the space at the split) are where the first arc ends.
            if (arcs.size == 2) for (c in arcs[0].second until arcs[1].first) { charFrom[c] = charTo[(arcs[0].second - 1).coerceAtLeast(0)]; charTo[c] = charFrom[c] }
            return ArcText(
                text, pieces.toTypedArray(), starts.toIntArray(), ends.toIntArray(), pieceWidths.toFloatArray(), radii.toFloatArray(),
                angles.toFloatArray(), arcStarts.toFloatArray(), charFrom, charTo, baseline, split = arcs.size == 2,
            )
        }

        /** Whether [text] can be cut a character at a time: letters that neither join nor stack, and no surrogates or marks. */
        fun glyphSafe(text: String): Boolean {
            if (LyricTextDirection.isRtl(text)) return false
            for (c in text) {
                if (c.code >= 0x0590 || c.isSurrogate()) return false
                val type = Character.getType(c)
                if (type == Character.NON_SPACING_MARK.toInt() || type == Character.COMBINING_SPACING_MARK.toInt() || type == Character.ENCLOSING_MARK.toInt()) return false
            }
            return true
        }
    }
}

/**
 * Draws [arc] round ([cx], [cy]): each piece turned to its angle and standing on its ring, or, while [slide] is under 1, on a ring
 * on its way in from [from]; lit by [chars] of its text from [unlit] to [lit] (null: every piece in [unlit]).
 */
internal fun DrawScope.drawArcText(arc: ArcText, cx: Float, cy: Float, from: Float, slide: Float, chars: Float?, unlit: Color, lit: Color, alpha: Float) {
    for (i in 0 until arc.count) {
        val piece = arc.pieces[i]
        val at = from + (arc.radii[i] - from) * slide
        val deg = if (slide >= 1f) arc.angles[i] else arc.angleOn(i, at)
        val color = if (chars == null) unlit else {
            val through = ((chars - arc.pieceStart[i]) / (arc.pieceEnd[i] - arc.pieceStart[i]).coerceAtLeast(1)).coerceIn(0f, 1f)
            lerp(unlit, lit, through)
        }
        rotate(LyricArc.glyphRotation(deg), Offset(cx, cy)) {
            drawText(piece, color.copy(alpha = color.alpha * alpha), Offset(cx - arc.widths[i] / 2f, cy - at - arc.baseline))
        }
    }
}

/** Upcoming words on the ring stay readable: never dimmer than this. */
internal const val ORBITAL_UNLIT_ALPHA = 0.5f

/**
 * Orbital (board 02): the line being sung on the upper arc round the cover disc, outside the moon's track, lit as it is sung with
 * the moon on the track at the singer's place; the next line on an outer ring well clear of it, sliding inward when its turn
 * comes (reduced motion: it is simply there). [fullScreen] is the TV's, where the stage is the screen.
 */
@Composable
internal fun OrbitalStage(
    cursor: StageCursor,
    song: LyricStageSong?,
    onSeek: (Long) -> Unit,
    onLongPress: (() -> Unit)?,
    fullScreen: Boolean,
    modifier: Modifier,
) {
    val reduced = LocalReducedMotion.current
    val colors = LocalCosmoColors.current
    val ink = remember(colors.palette, colors.dark) { stageInkOf(colors.palette, colors.dark) }
    val fonts = rememberLyricStageFonts()
    val measurer = rememberTextMeasurer(cacheSize = 64)
    val density = LocalDensity.current
    val lines = cursor.lines
    val focus = cursor.focus.value
    val next = cursor.next.value
    BoxWithConstraints(modifier, contentAlignment = Alignment.Center) {
        val side = min(constraints.maxWidth, constraints.maxHeight).toFloat().coerceAtLeast(1f)
        val rings = remember(side, density) { LyricArc.rings(side, density.density) }
        val current = remember(lines, focus, side, fonts, density) { stageLineOf(lines, focus) }
        val (lineStyle, nextStyle) = remember(fonts, rings, density) { orbitalStyles(fonts, rings, density) }
        val arc = remember(current, rings, lineStyle) { current?.let { ArcText.build(measurer, it.text, lineStyle, rings.inner, rings.outer) } }
        val nextArc = remember(lines, next, rings, nextStyle, arc?.split) {
            lines.getOrNull(next)?.let { ArcText.build(measurer, it.text, nextStyle, nextRing(rings, arc), null) }
        }
        // The new line comes in from the outer ring when its turn comes.
        val slide = remember { Animatable(1f) }
        LaunchedEffect(focus) {
            if (reduced) slide.snapTo(1f) else { slide.snapTo(0f); slide.animateTo(1f, tween(520, easing = FastOutSlowInEasing)) }
        }
        val discDp = with(density) { (rings.disc * 2f).toDp() }
        Disc(song?.coverUrl, discDp)
        val clock = cursor.clock
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        Canvas(
            Modifier
                .fillMaxSize()
                .semantics { contentDescription = current?.text.orEmpty() }
                .stageTouch(
                    target = { at ->
                        // The outer ring seeks to the line waiting on it; the line being sung, and the disc, to its start.
                        val r = hypot(at.x - width / 2f, at.y - height / 2f)
                        if (r > nextRing(rings, arc) - rings.lineHeight * 0.5f) lines.getOrNull(next)?.timeMs else current?.line?.timeMs
                    },
                    onSeek = onSeek, onLongPress = onLongPress,
                ),
        ) {
            drawOrbital(arc, nextArc, current, clock.longValue, ink, rings, slide.value, moon = !reduced, moonPx = 5.dp.toPx(), trackPx = 1.dp.toPx())
        }
    }
}

/** Where the next line waits: [LyricArc.NEXT_GAP_LINES] line heights beyond the line being sung, which may be on two arcs. */
internal fun nextRing(rings: LyricArc.Rings, current: ArcText?): Float = if (current?.split == true) rings.nextSplit else rings.next

/**
 * Orbital at [positionMs] in any [DrawScope] (the stage's canvas, and the share video's frames): the track, the next line on its
 * ring, the line being sung lit to the singer's place, and the moon on the track there. [slide] (0..1) brings a new line in from
 * the outer ring; the disc is the caller's.
 */
internal fun DrawScope.drawOrbital(
    arc: ArcText?,
    nextArc: ArcText?,
    current: StageLine?,
    positionMs: Long,
    ink: StageInk,
    rings: LyricArc.Rings,
    slide: Float,
    moon: Boolean,
    moonPx: Float,
    trackPx: Float,
) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    drawCircle(ink.faint, rings.track, Offset(cx, cy), style = Stroke(trackPx))
    if (nextArc != null) drawArcText(nextArc, cx, cy, rings.nextSplit, 1f, null, ink.next, ink.next, alpha = slide)
    if (arc == null || current == null) return
    val chars = current.revealedChars(positionMs)
    val unlit = ink.text.copy(alpha = maxOf(ink.unlit.alpha, ORBITAL_UNLIT_ALPHA))
    drawArcText(arc, cx, cy, rings.nextSplit, slide, chars, unlit, ink.text, alpha = 0.35f + 0.65f * slide)
    if (!moon) return
    val deg = arc.angleAtChars(chars)
    val place = Offset(LyricArc.x(cx, rings.track, deg), LyricArc.y(cy, rings.track, deg))
    drawCircle(ink.accent.copy(alpha = 0.28f), moonPx * 2.2f, place)
    drawCircle(ink.accent, moonPx, place)
}

internal fun orbitalStyles(fonts: LyricStageFonts, rings: LyricArc.Rings, density: Density): Pair<TextStyle, TextStyle> =
    stageStyle(fonts.orbital, StageWeights.ORBITAL, rings.fontPx, density) to stageTextStyle(fonts, rings.nextFontPx, density, 600)
