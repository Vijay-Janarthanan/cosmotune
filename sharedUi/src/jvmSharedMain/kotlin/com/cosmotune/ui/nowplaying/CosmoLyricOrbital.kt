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
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
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
 * A line set for Orbital, built once a line. Usually on the ring, glyph by glyph, within [LyricArc.SPAN_HALF_DEG] of twelve
 * o'clock: each glyph is measured flat in its arc's own layout (so kerning is kept) and laid out on its own, then turned to its
 * angle at draw time; a line too long for one arc at [LyricArc.SCALE_FLOOR] of its size goes over two concentric arcs, its first
 * words on the outer one. Scripts whose letters join or stack (Arabic, Devanagari and the rest) are cut at the spaces instead, a
 * word to a piece. A line too long even for two arcs is not wrapped round the ring: it is [flat], plain horizontal text centred
 * above the disc (or, for the next line after such a line, below it), lit by the same sweep.
 */
internal class ArcText(
    val text: String,
    /** The pieces on the ring: their layouts, the characters each covers, its width, its ring and its angle, and its arc's start. */
    val pieces: Array<TextLayoutResult>,
    val pieceStart: IntArray,
    val pieceEnd: IntArray,
    val widths: FloatArray,
    val radii: FloatArray,
    val angles: FloatArray,
    val starts: FloatArray,
    /** Each character's start and end angle, in reading order: where the moon is when the sweep reaches it. */
    private val charFrom: FloatArray,
    private val charTo: FloatArray,
    val baseline: Float,
    /** Whether the line is on two arcs, which moves the next line's ring out. */
    val split: Boolean,
    /** The line as horizontal text, when it is not on the ring; [flatAbove] puts it over the disc, else under it, [flatGap] from the centre. */
    val flat: TextLayoutResult? = null,
    val flatAbove: Boolean = true,
    val flatGap: Float = 0f,
    private val flatRows: FlatRows? = null,
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

    /** Where the flat text's top left is, round a centre at ([cx], [cy]). */
    fun flatLeft(cx: Float): Float = cx - (flat?.size?.width ?: 0) / 2f
    fun flatTop(cy: Float): Float = if (flatAbove) cy - flatGap - (flat?.size?.height ?: 0) else cy + flatGap

    /** The flat text's rows and characters, measured once, so the sweep over it allocates nothing. */
    fun rows(): FlatRows? = flatRows

    companion object {
        /** The line being sung, on the rings of [rings]: one arc, two, or flat above the disc. */
        fun line(measurer: TextMeasurer, text: String, style: TextStyle, rings: LyricArc.Rings): ArcText {
            val (wordStart, wordEnd) = words(text)
            val full = measurer.measure(text, style, softWrap = false, maxLines = 1)
            val widths = FloatArray(wordStart.size) { w ->
                val a = full.getBoundingBox(wordStart[w])
                val b = full.getBoundingBox(wordEnd[w] - 1)
                maxOf(a.right, b.right) - minOf(a.left, b.left)
            }
            val space = measurer.measure("a a", style, softWrap = false).size.width - measurer.measure("aa", style, softWrap = false).size.width.toFloat()
            val fit = LyricArc.fitUpper(widths, widths.size, space, rings.inner, rings.outer)
            if (fit.flat) {
                // Too long for the ring: plain text centred over the disc, as large as fits the room between the track and the top.
                val gap = rings.track + rings.clear
                val room = rings.half - gap - rings.clear / 2f
                val width = (rings.half * 1.85f).toInt().coerceAtLeast(1)
                var scale = 1f
                fun at(k: Float) = measurer.measure(
                    text, style.copy(fontSize = style.fontSize * k, lineHeight = style.lineHeight * k, textAlign = TextAlign.Center),
                    constraints = Constraints(maxWidth = width),
                )
                var layout = at(scale)
                while (layout.size.height > room && scale > LyricArc.NEXT_SCALE_FLOOR) {
                    scale *= 0.92f
                    layout = at(scale)
                }
                return flatText(text, layout, above = true, gap = gap)
            }
            val used = scaled(style, fit.scale)
            val arcs = if (fit.split < 0) {
                listOf(Triple(0, text.length, rings.inner))
            } else {
                listOf(Triple(0, wordEnd[fit.split - 1], rings.outer), Triple(wordStart[fit.split], text.length, rings.inner))
            }
            return onArcs(measurer, text, used, arcs)
        }

        /**
         * The next line, smaller, on its ring beyond [after] (the line being sung): within the span, set smaller to fit down to
         * [LyricArc.NEXT_SCALE_FLOOR] and cut with an ellipsis past that. After a line set flat, the next one is flat too, under
         * the disc, so the two never meet.
         */
        fun next(measurer: TextMeasurer, text: String, style: TextStyle, rings: LyricArc.Rings, after: ArcText?): ArcText {
            if (after?.flat != null) {
                val layout = measurer.measure(
                    text, style.copy(textAlign = TextAlign.Center), overflow = TextOverflow.Ellipsis, maxLines = 2,
                    constraints = Constraints(maxWidth = (rings.half * 1.6f).toInt().coerceAtLeast(1)),
                )
                return flatText(text, layout, above = false, gap = rings.track + rings.clear)
            }
            val radius = if (after?.split == true) rings.nextSplit else rings.next
            val total = measurer.measure(text, style, softWrap = false, maxLines = 1).size.width.toFloat()
            val scale = LyricArc.nextScale(total, radius)
            val used = scaled(style, scale)
            var shown = text
            if (total * scale > LyricArc.upperLength(radius)) {
                // Still too long at its smallest: as many words as fit, and an ellipsis.
                val (starts, ends) = words(text)
                var k = ends.size
                while (k > 1) {
                    shown = text.substring(0, ends[k - 1]) + "…"
                    if (measurer.measure(shown, used, softWrap = false, maxLines = 1).size.width <= LyricArc.upperLength(radius)) break
                    k--
                }
                if (k <= 1) shown = text.substring(0, ends.firstOrNull() ?: text.length) + "…"
                if (starts.isEmpty()) shown = text
            }
            return onArcs(measurer, shown, used, listOf(Triple(0, shown.length, radius)))
        }

        private fun scaled(style: TextStyle, scale: Float): TextStyle =
            if (scale < 1f) style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale) else style

        private fun words(text: String): Pair<List<Int>, List<Int>> {
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) { i++; continue }
                val from = i
                while (i < text.length && !text[i].isWhitespace()) i++
                starts += from
                ends += i
            }
            return starts to ends
        }

        /** [text] in [used] on [arcs] (each its characters from, until, and its ring), centred on twelve o'clock. */
        private fun onArcs(measurer: TextMeasurer, text: String, used: TextStyle, arcs: List<Triple<Int, Int, Float>>): ArcText {
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

        /** [text] laid out flat as [layout]; the moon runs the span of the ring as the sweep runs the text. */
        private fun flatText(text: String, layout: TextLayoutResult, above: Boolean, gap: Float): ArcText {
            val length = text.length.coerceAtLeast(1)
            val from = FloatArray(text.length) { LyricArc.spanAngle(it.toFloat() / length) }
            val to = FloatArray(text.length) { LyricArc.spanAngle((it + 1f) / length) }
            return ArcText(
                text, emptyArray(), IntArray(0), IntArray(0), FloatArray(0), FloatArray(0), FloatArray(0), FloatArray(0), from, to,
                baseline = 0f, split = false, flat = layout, flatAbove = above, flatGap = gap, flatRows = FlatRows(layout),
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

/** A flat line's rows and the edges of each character along them, measured once, for a sweep that allocates nothing. */
internal class FlatRows(layout: TextLayoutResult) {
    val rows = layout.lineCount
    val top = FloatArray(rows) { layout.getLineTop(it) }
    val bottom = FloatArray(rows) { layout.getLineBottom(it) }
    val left = FloatArray(rows) { layout.getLineLeft(it) }
    val right = FloatArray(rows) { layout.getLineRight(it) }
    private val length = layout.layoutInput.text.length
    val charRow = IntArray(length) { layout.getLineForOffset(it) }
    /** Each character's start and end edge in reading order (right to left on a right-to-left row). */
    val charFrom = FloatArray(length)
    val charTo = FloatArray(length)

    init {
        for (c in 0 until length) {
            val box = layout.getBoundingBox(c)
            val rtl = layout.getParagraphDirection(c) == ResolvedTextDirection.Rtl
            charFrom[c] = if (rtl) box.right else box.left
            charTo[c] = if (rtl) box.left else box.right
        }
    }
}

/**
 * Draws [arc] round ([cx], [cy]): each piece turned to its angle and standing on its ring, or, while [slide] is under 1, on a ring
 * on its way in from [from]; lit by [chars] of its text from [unlit] to [lit] (null: every piece in [unlit]). A flat line is drawn
 * as text, the rows above the sweep's edge lit whole and the edge's row up to the edge.
 */
internal fun DrawScope.drawArcText(arc: ArcText, cx: Float, cy: Float, from: Float, slide: Float, chars: Float?, unlit: Color, lit: Color, alpha: Float) {
    val flat = arc.flat
    val rows = arc.rows()
    if (flat != null && rows != null) {
        val topLeft = Offset(arc.flatLeft(cx), arc.flatTop(cy))
        drawText(flat, unlit.copy(alpha = unlit.alpha * alpha), topLeft)
        val length = arc.text.length
        if (chars == null || chars <= 0f || length == 0) return
        val litColor = lit.copy(alpha = lit.alpha * alpha)
        if (chars >= length) {
            drawText(flat, litColor, topLeft)
            return
        }
        val whole = chars.toInt().coerceIn(0, length - 1)
        val row = rows.charRow[whole]
        for (r in 0 until row) {
            clipRect(topLeft.x + rows.left[r], topLeft.y + rows.top[r], topLeft.x + rows.right[r], topLeft.y + rows.bottom[r]) { drawText(flat, litColor, topLeft) }
        }
        val x0 = rows.charFrom[whole]
        val x1 = rows.charTo[whole]
        val edge = topLeft.x + x0 + (x1 - x0) * (chars - whole)
        val top = topLeft.y + rows.top[row]
        val bottom = topLeft.y + rows.bottom[row]
        if (x1 < x0) {
            clipRect(edge, top, topLeft.x + rows.right[row], bottom) { drawText(flat, litColor, topLeft) }
        } else {
            clipRect(topLeft.x + rows.left[row], top, edge, bottom) { drawText(flat, litColor, topLeft) }
        }
        return
    }
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

/** The next line, waiting on its ring. */
internal const val ORBITAL_NEXT_ALPHA = 0.6f

/**
 * Orbital (board 02): the line being sung on the upper part of the ring round the cover disc, outside the moon's track, lit as it
 * is sung with the moon on the track at the singer's place; the next line on an outer ring well clear of it, sliding inward when
 * its turn comes (reduced motion: it is simply there). A line too long for the ring is plain text above the disc. [fullScreen] is
 * the TV's, where the stage is the screen.
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
        val arc = remember(current, rings, lineStyle) { current?.let { ArcText.line(measurer, it.text, lineStyle, rings) } }
        val nextArc = remember(lines, next, rings, nextStyle, arc) {
            lines.getOrNull(next)?.let { ArcText.next(measurer, it.text, nextStyle, rings, arc) }
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
                        // The next line seeks to itself; the line being sung, and the disc, to its start.
                        val r = hypot(at.x - width / 2f, at.y - height / 2f)
                        val onNext = if (arc?.flat != null) at.y > height / 2f + rings.track else r > nextRing(rings, arc) - rings.lineHeight * 0.5f
                        if (onNext) lines.getOrNull(next)?.timeMs else current?.line?.timeMs
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
 * Orbital at [positionMs] in any [DrawScope] (the stage's canvas, and the share video's frames): the track, the next line, the
 * line being sung lit to the singer's place, and the moon on the track there. [slide] (0..1) brings a new line in from the outer
 * ring; the disc is the caller's.
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
    val waiting = ink.text.copy(alpha = ORBITAL_NEXT_ALPHA)
    if (nextArc != null) drawArcText(nextArc, cx, cy, rings.nextSplit, 1f, null, waiting, waiting, alpha = slide)
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
