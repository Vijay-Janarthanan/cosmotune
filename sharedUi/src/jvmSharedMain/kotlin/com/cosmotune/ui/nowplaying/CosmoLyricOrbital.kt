package com.cosmotune.ui.nowplaying

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
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
 * A line set on a ring, glyph by glyph: each glyph is measured flat in the line's own layout (so kerning is kept) and laid out on
 * its own, then turned to its angle on the ring at draw time. Scripts whose letters join or stack (Arabic, Devanagari and the
 * rest) are cut at the spaces instead, a word to a piece, so nothing that shapes across letters is cut apart; a word is short
 * against the ring and sits on it as a chord. Built once a line.
 */
internal class ArcText(
    val text: String,
    /** The pieces: their layouts, the characters each covers, its width, and its angle on the ring at [radius]. */
    val pieces: Array<TextLayoutResult>,
    val pieceStart: IntArray,
    val pieceEnd: IntArray,
    val widths: FloatArray,
    val angles: FloatArray,
    /** Each piece's left edge along the flat line, for the moon to find a character's place. */
    val lefts: FloatArray,
    val radius: Float,
    val startDeg: Float,
    /** Each character's start and end edge along the flat line, in reading order (so right to left on a right-to-left line). */
    private val charFrom: FloatArray,
    private val charTo: FloatArray,
    val baseline: Float,
) {
    val count: Int get() = pieces.size

    /** The angle at [chars] of the text sung: the sweep's edge, where the moon is. */
    fun angleAtChars(chars: Float): Float {
        val length = text.length
        if (length == 0) return startDeg
        val c = chars.coerceIn(0f, length.toFloat())
        val whole = c.toInt().coerceAtMost(length - 1)
        val x = if (c >= length) charTo[length - 1] else charFrom[whole] + (charTo[whole] - charFrom[whole]) * (c - whole)
        return LyricArc.angleAt(x, radius, startDeg)
    }

    /** The angle of piece [i] when the ring is drawn at [at] instead of [radius] (sliding in from the outer ring). */
    fun angleOn(i: Int, at: Float): Float = startDeg + (angles[i] - startDeg) * (radius / at.coerceAtLeast(1f))

    companion object {
        fun build(measurer: TextMeasurer, text: String, style: TextStyle, radius: Float, startDeg: Float = LyricArc.TOP_DEG): ArcText {
            var flat = measurer.measure(text, style, softWrap = false, maxLines = 1)
            val scale = LyricArc.fitScale(flat.size.width.toFloat(), radius)
            val used = if (scale < 1f) style.copy(fontSize = style.fontSize * scale, lineHeight = style.lineHeight * scale) else style
            if (scale < 1f) flat = measurer.measure(text, used, softWrap = false, maxLines = 1)
            val byGlyph = glyphSafe(text)
            val starts = ArrayList<Int>()
            val ends = ArrayList<Int>()
            var i = 0
            while (i < text.length) {
                if (text[i].isWhitespace()) { i++; continue }
                val from = i
                i = if (byGlyph) i + 1 else { var j = i; while (j < text.length && !text[j].isWhitespace()) j++; j }
                starts += from
                ends += i
            }
            val n = starts.size
            val lefts = FloatArray(n)
            val widths = FloatArray(n)
            val pieces = Array(n) { k ->
                val a = flat.getBoundingBox(starts[k])
                val b = flat.getBoundingBox(ends[k] - 1)
                lefts[k] = minOf(a.left, b.left)
                widths[k] = maxOf(a.right, b.right) - lefts[k]
                measurer.measure(text.substring(starts[k], ends[k]), used, softWrap = false, maxLines = 1)
            }
            val angles = FloatArray(n)
            LyricArc.place(lefts, widths, n, radius, startDeg, angles)
            val from = FloatArray(text.length)
            val to = FloatArray(text.length)
            for (c in text.indices) {
                val box = flat.getBoundingBox(c)
                val rtl = flat.getParagraphDirection(c) == ResolvedTextDirection.Rtl
                from[c] = if (rtl) box.right else box.left
                to[c] = if (rtl) box.left else box.right
            }
            return ArcText(text, pieces, starts.toIntArray(), ends.toIntArray(), widths, angles, lefts, radius, startDeg, from, to, flat.firstBaseline)
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
 * Draws [arc] round ([cx], [cy]) on a ring of [at] (its own radius, or another while it slides in): each piece turned to its
 * angle and standing on the ring, lit by [chars] of its text from [unlit] to [lit] (null: every piece in [unlit]).
 */
internal fun DrawScope.drawArcText(arc: ArcText, cx: Float, cy: Float, at: Float, chars: Float?, unlit: Color, lit: Color, alpha: Float) {
    for (i in 0 until arc.count) {
        val piece = arc.pieces[i]
        val deg = if (at == arc.radius) arc.angles[i] else arc.angleOn(i, at)
        val color = if (chars == null) unlit else {
            val through = ((chars - arc.pieceStart[i]) / (arc.pieceEnd[i] - arc.pieceStart[i]).coerceAtLeast(1)).coerceIn(0f, 1f)
            lerp(unlit, lit, through)
        }
        rotate(LyricArc.glyphRotation(deg), Offset(cx, cy)) {
            drawText(piece, color.copy(alpha = color.alpha * alpha), Offset(cx - arc.widths[i] / 2f, cy - at - arc.baseline))
        }
    }
}

/**
 * Orbital (board 02): the line being sung wraps round the cover disc, lit as it is sung, with the moon on its ring at the
 * singer's place; the next line waits on an outer ring and slides inward when its turn comes (reduced motion: it is simply
 * there). [fullScreen] is the TV's, where the stage is the screen.
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
        val rings = remember(side) { LyricArc.rings(side) }
        val current = remember(lines, focus, side, fonts, density) { stageLineOf(lines, focus) }
        val arc = remember(current, rings, fonts, density) {
            current?.let { ArcText.build(measurer, it.text, stageStyle(fonts.orbital, StageWeights.ORBITAL, rings.fontPx, density), rings.current) }
        }
        val nextArc = remember(lines, next, rings, fonts, density) {
            lines.getOrNull(next)?.let { ArcText.build(measurer, it.text, stageTextStyle(fonts, rings.nextFontPx, density, 600), rings.next) }
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
                        if (r > (rings.current + rings.next) / 2f) lines.getOrNull(next)?.timeMs else current?.line?.timeMs
                    },
                    onSeek = onSeek, onLongPress = onLongPress,
                ),
        ) {
            drawOrbital(arc, nextArc, current, clock.longValue, ink, rings, slide.value, moon = !reduced, moonPx = 5.dp.toPx(), trackPx = 1.dp.toPx())
        }
    }
}

/**
 * Orbital at [positionMs] in any [DrawScope] (the stage's canvas, and the share video's frames): the ring the line stands on, the
 * next line on the outer ring, the line being sung lit to the singer's place, and the moon there. [slide] (0..1) brings a new line
 * in from the outer ring; the disc is the caller's.
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
    drawCircle(ink.faint, rings.current, Offset(cx, cy), style = Stroke(trackPx))
    if (nextArc != null) drawArcText(nextArc, cx, cy, nextArc.radius, null, ink.next, ink.next, alpha = slide)
    if (arc == null || current == null) return
    val chars = current.revealedChars(positionMs)
    val at = rings.next + (rings.current - rings.next) * slide
    drawArcText(arc, cx, cy, at, chars, ink.unlit, ink.text, alpha = 0.35f + 0.65f * slide)
    if (!moon) return
    val deg = arc.angleAtChars(chars)
    val place = Offset(LyricArc.x(cx, at, deg), LyricArc.y(cy, at, deg))
    drawCircle(ink.accent.copy(alpha = 0.28f), moonPx * 2.2f, place)
    drawCircle(ink.accent, moonPx, place)
}

internal fun orbitalStyles(fonts: LyricStageFonts, rings: LyricArc.Rings, density: Density): Pair<TextStyle, TextStyle> =
    stageStyle(fonts.orbital, StageWeights.ORBITAL, rings.fontPx, density) to stageTextStyle(fonts, rings.nextFontPx, density, 600)
