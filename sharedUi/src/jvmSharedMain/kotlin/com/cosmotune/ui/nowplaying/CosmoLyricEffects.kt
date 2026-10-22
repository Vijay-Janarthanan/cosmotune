package com.cosmotune.ui.nowplaying

import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.ResolvedTextDirection
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.design.LyricsMetrics
import com.cosmotune.lyrics.LyricParticles
import com.cosmotune.lyrics.WordFill
import com.cosmotune.music.data.lyrics.CharGrowth
import com.cosmotune.music.data.lyrics.GrowingWord
import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.ui.player.clipShiftedDown

/*
 * What the new lyrics view draws for the words of a line as they are sung (the old view's features, carried over): the fill that crosses each timed piece
 * at a steady rate and fades out at its leading edge, the word that lifts off the line as it is sung, the held note that swells
 * a letter at a time, and the bloom behind it. They are the old panel's effects (`PlayerLyrics.kt`: `sweepTo`, `riseWith`,
 * `growEach`, `glowGrown`), which are private there, written again against the new view's two-copy text (a dim copy and an accent
 * copy on top of it), with the right-to-left rows the old code did not handle, and without anything allocated per frame: every
 * loop is an index loop, the letter in hand is one reused [CharGrowth], and the two lambdas the platform's [clipShiftedDown]
 * wants are made once per line, not once per slice.
 *
 * Everything here is called from draw lambdas. The position is the lyric clock's, read by the caller in the draw phase.
 */

/** Where an offset sits across a visual line, on that line: both ends answer with the line's own edges, and anything between is held inside them. */
internal fun TextLayoutResult.xOn(offset: Int, row: Int): Float {
    val left = getLineLeft(row)
    val right = getLineRight(row)
    val rtl = isRtlRow(row)
    return when {
        offset <= getLineStart(row) -> if (rtl) right else left
        offset >= getLineEnd(row, visibleEnd = true) -> if (rtl) left else right
        else -> getHorizontalPosition(offset, usePrimaryDirection = true).coerceIn(left, right)
    }
}

/** Whether the paragraph holding visual line [row] runs right to left. */
internal fun TextLayoutResult.isRtlRow(row: Int): Boolean {
    val length = layoutInput.text.length
    if (length == 0) return false
    return getParagraphDirection(getLineStart(row).coerceIn(0, length - 1)) == ResolvedTextDirection.Rtl
}

/**
 * Where the edge of the fill is across visual line [row] when [chars] characters have been sung. Inside a timed piece that sits
 * whole on the row the edge crosses the piece's width at a steady rate, so a held note draws slowly and an "i" is not crossed
 * as fast as a "w"; across a space, or a piece broken over a wrap, it goes letter by letter. Row-aware at both ends: on the last
 * character of a wrapped row the next position belongs to the row below, and read straight it would put the edge back at the
 * start of the row.
 */
internal fun TextLayoutResult.edgeOn(row: Int, chars: Float, spans: List<IntRange>): Float {
    val rowStart = getLineStart(row)
    val rowEnd = getLineEnd(row, visibleEnd = true)
    val piece = WordFill.pieceAt(spans, chars)
    if (piece >= 0 && WordFill.sitsOnRow(spans[piece], rowStart, rowEnd)) {
        val span = spans[piece]
        val from = xOn(span.first, row)
        val to = xOn(span.last + 1, row)
        return from + (to - from) * WordFill.through(span, chars)
    }
    val index = chars.toInt().coerceIn(rowStart, rowEnd)
    val here = xOn(index, row)
    val next = xOn((index + 1).coerceAtMost(rowEnd), row)
    return here + (next - here) * (chars - index)
}

/**
 * The soft end of the fill: a ramp from opaque to nothing over [widthPx] that erases the last stretch of the accent copy with
 * `DstIn`. The brushes are drawn at the edge, whatever its x, so they are built once per size and the frame allocates none.
 */
internal class FeatherEdge(val widthPx: Float, val leading: Brush, val trailing: Brush)

/** The feather for this density: [leading] for a left-to-right row (opaque on the left of the edge), [trailing] for a right-to-left one. */
internal fun Density.featherEdge(): FeatherEdge {
    val width = LyricsMetrics.WIPE_FEATHER_DP.dp.toPx().coerceAtLeast(1f)
    return FeatherEdge(
        widthPx = width,
        leading = Brush.horizontalGradient(0f to Color.White, 1f to Color.Transparent, startX = -width, endX = 0f),
        trailing = Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.White, startX = 0f, endX = width),
    )
}

/**
 * Draws the content of the node as far as the line has been sung, a word at a time: the visual lines above the one that holds the
 * edge whole, that one from its start edge to the edge (a right-to-left row from its right edge), and the rows below not at all.
 * With a [feather] the last stretch before the edge fades out instead of ending on a cut, which needs the node to be an
 * offscreen layer to erase into. A wrapped line fills in reading order.
 */
internal fun ContentDrawScope.sweepWords(layout: TextLayoutResult?, line: LyricLine, positionMs: Long, feather: FeatherEdge?) {
    if (layout == null) return
    val length = layout.layoutInput.text.length
    if (length == 0) return
    val chars = WordFill.revealedChars(line, positionMs)
    if (chars <= 0f) return
    if (chars >= length) {
        drawContent()
        return
    }
    val spans = line.sweepSpans
    for (row in 0 until layout.lineCount) {
        val start = layout.getLineStart(row)
        // Rows below the edge have nothing lit on them, and neither has anything after them.
        if (chars <= start) return
        val end = layout.getLineEnd(row, visibleEnd = true)
        val cut = chars < end
        val rtl = layout.isRtlRow(row)
        val left = layout.getLineLeft(row)
        val right = layout.getLineRight(row)
        val top = layout.getLineTop(row)
        val bottom = layout.getLineBottom(row)
        val edge = if (cut) layout.edgeOn(row, chars, spans) else if (rtl) left else right
        if (rtl) {
            clipRect(left = edge, top = top, right = right, bottom = bottom) { this@sweepWords.drawContent() }
        } else {
            clipRect(left = left, top = top, right = edge, bottom = bottom) { this@sweepWords.drawContent() }
        }
        // Only the row that holds the edge has one to soften; a row filled to its end runs into the wrap, which is not an edge.
        if (feather == null || !cut) continue
        val available = if (rtl) right - edge else edge - left
        val width = LyricsMetrics.featherWidthPx(feather.widthPx, available)
        if (width <= 0f) continue
        val squeeze = width / feather.widthPx
        // Scoped to this row's band, so the mask cannot reach the rows above and below it.
        translate(left = edge, top = top) {
            scale(squeeze, 1f, Offset.Zero) {
                if (rtl) {
                    drawRect(feather.trailing, topLeft = Offset.Zero, size = Size(feather.widthPx, bottom - top), blendMode = BlendMode.DstIn)
                } else {
                    drawRect(feather.leading, topLeft = Offset(-feather.widthPx, 0f), size = Size(feather.widthPx, bottom - top), blendMode = BlendMode.DstIn)
                }
            }
        }
    }
}

/**
 * The words of one line that move as it is sung: the word being sung lifts off the line and settles back as it is left behind,
 * and a word held long enough to be run through letter by letter swells, leans and lifts a letter at a time, with a bloom
 * behind it. One per line, made once ([WordFill.growers], the letter in hand and the two draw blocks are all kept here), and
 * drawn from the draw phase with the clock's position.
 */
internal class WordLifter(private val line: LyricLine) {
    private val growers: Array<GrowingWord?> = WordFill.growers(line)

    /** Whether any word of the line is held long enough to swell, which is what a bloom is for. */
    val hasHeldWords: Boolean = hasAny(growers)

    /** The letter in hand: filled in and read back a letter at a time, so a frame allocates none. */
    private val growth = CharGrowth()

    // What the two blocks below need from the frame in progress. Set before each slice, read while it draws.
    private var content: ContentDrawScope? = null
    private var shiftPx = 0f
    private var swell = 1f
    private var pivotX = 0f
    private var pivotY = 0f

    private val flat: DrawScope.() -> Unit = { content?.drawContent() }
    private val swelled: DrawScope.() -> Unit = {
        translate(left = shiftPx) { scale(swell, swell, Offset(pivotX, pivotY)) { content?.drawContent() } }
    }

    /**
     * Redraws the node's content a piece at a time with the word being sung lifted off the line and the ones behind it settling
     * back, each word at its own height. The line is cut at word boundaries (inside a word only for a held one, a letter at a
     * time), so no glyph is sliced; the pieces that are on the floor are one replay between them. Call it only when
     * [WordFill.isLifted] says something is off the floor: a line with no lift on it draws once, with `drawContent()`.
     */
    fun lift(scope: ContentDrawScope, layout: TextLayoutResult, positionMs: Long) = with(scope) {
        content = scope
        val words = line.words
        val spans = line.wordSpans
        val peak = LyricsMetrics.WORD_RISE_DP.dp.toPx()
        val em = layout.layoutInput.style.fontSize.toPx()
        for (row in 0 until layout.lineCount) {
            val rowStart = layout.getLineStart(row)
            val rowEnd = layout.getLineEnd(row, visibleEnd = true)
            // The row's own box. Anything standing still is clipped to exactly this: a band opened upwards would take in the bottom
            // of the row above and draw it a second time.
            val top = layout.getLineTop(row)
            val bottom = layout.getLineBottom(row)
            val left = layout.getLineLeft(row)
            val right = layout.getLineRight(row)
            val rtl = layout.isRtlRow(row)
            // Everything left of `cursor` has been drawn. Words are taken in the order they stand across the row, so from the
            // last word to the first on a right-to-left row.
            var cursor = left
            var step = 0
            while (step < words.size) {
                val index = if (rtl) words.size - 1 - step else step
                step++
                val span = spans[index]
                val start = maxOf(span.first, rowStart)
                val end = minOf(span.last + 1, rowEnd)
                if (start >= end) continue
                val held = growers[index]?.takeIf { positionMs >= it.startMs && positionMs <= it.restsAtMs }
                val rise = line.wordLift(index, positionMs)
                // A word with nothing happening to it is left to the flat run, which is the whole of the line for all but a syllable of it.
                if (held == null && rise <= 0.01f) continue
                val a = layout.xOn(start, row)
                val b = layout.xOn(end, row)
                val x0 = minOf(a, b)
                val x1 = maxOf(a, b)
                // Nothing to cut: left where it is, so the flat run still has it and the row keeps its words.
                if (x1 <= x0) continue
                if (x0 > cursor) clipShiftedDown(cursor, top, x0, bottom, 0f, flat)
                if (held != null) {
                    growEach(layout, held, positionMs, row, start, end, spans[index].first, top, bottom, peak, em)
                } else {
                    // Only what is off the floor gets room above the row to be off it in.
                    clipShiftedDown(x0, top - peak, x1, bottom, -rise * peak, flat)
                }
                if (x1 > cursor) cursor = x1
            }
            if (cursor < right) clipShiftedDown(cursor, top, right, bottom, 0f, flat)
        }
    }

    /**
     * One held word, a letter at a time, each at its own swell, lean and height. Each piece is clipped to where its letter is
     * going rather than where it sits: a glyph grown about its middle reaches past the box it was laid out in, and clipping to
     * that box would shave both sides off it as it swells.
     */
    private fun ContentDrawScope.growEach(
        layout: TextLayoutResult,
        word: GrowingWord,
        positionMs: Long,
        row: Int,
        start: Int,
        end: Int,
        wordFirst: Int,
        top: Float,
        bottom: Float,
        peak: Float,
        em: Float,
    ) {
        // The settle is shared with every other word: a letter comes to rest at the same small lift, and then goes down with the rest of the line.
        val fall = line.wordFall(word.index, positionMs)
        // Room to swell into, above the row rather than inside it; the pivot stays on the row's own middle.
        val ceiling = top - peak * GROW_HEADROOM
        pivotY = (top + bottom) / 2f
        for (char in start until end) {
            word.sampleInto(char - wordFirst, positionMs, growth)
            val from = layout.xOn(char, row)
            val to = layout.xOn(char + 1, row)
            if (to <= from) continue
            shiftPx = growth.shift * em
            swell = growth.scale
            pivotX = (from + to) / 2f
            val overhang = (to - from) * (growth.scale - 1f) / 2f
            clipShiftedDown(from - overhang + shiftPx, ceiling, to + overhang + shiftPx, bottom, -growth.rise * peak * fall, swelled)
        }
    }

    /**
     * Draws the node's content clipped to the letters of the words being held, each at its own brightness: the light a carried
     * note gives off, rather than a band of it dragged along behind the highlight. Nothing at all on a line of ordinary
     * syllables. Each letter is masked to its own bloom with `DstIn`, so the node has to be an offscreen layer to erase into; the
     * blur that spreads it is applied by the caller, after this.
     */
    fun bloom(scope: ContentDrawScope, layout: TextLayoutResult, positionMs: Long) = with(scope) {
        val spans = line.wordSpans
        val length = layout.layoutInput.text.length
        val peak = LyricsMetrics.WORD_RISE_DP.dp.toPx()
        val em = layout.layoutInput.style.fontSize.toPx()
        for (index in growers.indices) {
            val word = growers[index] ?: continue
            if (positionMs < word.startMs || positionMs > word.restsAtMs) continue
            val span = spans[index]
            val fall = line.wordFall(index, positionMs)
            for (char in span.first..minOf(span.last, length - 1)) {
                word.sampleInto(char - span.first, positionMs, growth)
                if (growth.bloom <= 0.01f) continue
                val row = layout.getLineForOffset(char)
                val from = layout.xOn(char, row)
                val to = layout.xOn(char + 1, row)
                if (to <= from) continue
                val dx = growth.shift * em
                val dy = -growth.rise * peak * fall
                val rowTop = layout.getLineTop(row)
                val rowBottom = layout.getLineBottom(row)
                val overhang = (to - from) * (growth.scale - 1f) / 2f
                clipRect(left = from - overhang + dx, top = rowTop - peak * GROW_HEADROOM, right = to + overhang + dx, bottom = rowBottom) {
                    translate(left = dx, top = dy) {
                        scale(growth.scale, growth.scale, Offset((from + to) / 2f, (rowTop + rowBottom) / 2f)) { scope.drawContent() }
                    }
                    // Scoped to this letter's own clip, so it takes this letter's brightness down and leaves its neighbours, which
                    // have their own, a beat behind, where they are.
                    drawRect(color = Color.White.copy(alpha = growth.bloom), blendMode = BlendMode.DstIn)
                }
            }
        }
    }

    private companion object {
        fun hasAny(growers: Array<GrowingWord?>): Boolean {
            for (i in growers.indices) if (growers[i] != null) return true
            return false
        }

        /** How much further up a row is opened when it holds a word being swelled, in multiples of the lift (the old `GROW_HEADROOM`). */
        const val GROW_HEADROOM = 3f
    }
}

/**
 * The motes that rise off a line's romanisation or translation when it changes (the old `lyricParticles`): a few of its letters,
 * each with a faint halo and a core, drifting up and a little sideways and fading in and out as [progress] runs from 0 to 1.
 * The glyph positions are worked out once, when the text has been laid out; [layout] is read then, in the draw cache. With no
 * [progress] (nothing is moving) this is not even a modifier.
 */
internal fun Modifier.translationParticles(layout: () -> TextLayoutResult?, progress: State<Float>?, color: Color): Modifier {
    if (progress == null) return this
    return drawWithCache {
        val measured = layout()
        if (measured == null) return@drawWithCache onDrawWithContent { drawContent() }
        val set = LyricParticles.generate(measured.layoutInput.text.text)
        val count = set.size
        val anchorX = FloatArray(count)
        val anchorY = FloatArray(count)
        for (i in 0 until count) {
            val box = measured.getBoundingBox(set.charIndex[i])
            anchorX[i] = box.center.x
            anchorY[i] = box.center.y
        }
        val dp = density
        val fall = 3.dp.toPx()
        onDrawWithContent {
            drawContent()
            val value = progress.value
            if (value > 0f && value < 1f) {
                for (i in 0 until count) {
                    val t = LyricParticles.phase(value, set.delay[i])
                    val envelope = LyricParticles.envelope(t)
                    val ease = LyricParticles.ease(t)
                    val center = Offset(anchorX[i] + set.driftX[i] * dp * ease, anchorY[i] + set.driftY[i] * dp * ease + fall * t * t)
                    val radius = set.radius[i] * dp
                    // Two inexpensive circles give a soft halo without another blur layer; opacity rises and falls without a flash.
                    drawCircle(color, radius * LyricParticles.HALO_RADIUS_RATIO, center, alpha = envelope * LyricParticles.HALO_ALPHA)
                    drawCircle(color, radius, center, alpha = envelope * LyricParticles.CORE_ALPHA)
                }
            }
        }
    }
}
