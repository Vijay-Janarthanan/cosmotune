package com.cosmotune.lyrics.styles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Text on a circle, for Orbital: the line set along a ring round the cover disc, glyph by glyph, as SVG's `textPath` sets it on
 * the board. The glyphs are measured flat first (each one's left edge and width along the line, kerning included, from the
 * line's own layout); here those flat positions become angles. Angles are degrees, clockwise from three o'clock on a screen (y
 * down), so -90 is twelve o'clock; a glyph's baseline sits on the ring and its top points away from the centre.
 *
 * Readability beats the effect. Text only sits within [SPAN_HALF_DEG] either side of twelve o'clock, so no glyph is ever turned
 * more than that from upright, and it is centred there. A line that does not fit at [SCALE_FLOOR] of its size is split over two
 * concentric arcs, each within the same span, the first words on the outer one (read first, as the upper of two rows). A line
 * that does not fit even so is not wrapped round the ring at all: it is set as plain horizontal text above the disc ([Fit.flat]).
 */
object LyricArc {
    /** Twelve o'clock. */
    const val TOP_DEG = -90f

    /** How far either side of twelve o'clock text may sit: no glyph is turned further than this from upright. */
    const val SPAN_HALF_DEG = 65f
    const val UPPER_FROM_DEG = TOP_DEG - SPAN_HALF_DEG
    const val UPPER_TO_DEG = TOP_DEG + SPAN_HALF_DEG
    const val UPPER_SWEEP_DEG = 2f * SPAN_HALF_DEG

    /** The smallest a line on the ring is set; below it the line is split, and past that set flat. */
    const val SCALE_FLOOR = 0.75f

    /** The next line, which is only waiting, may be set this small before it is cut short with an ellipsis. */
    const val NEXT_SCALE_FLOOR = 0.6f

    /** How many degrees [length] of text takes round a ring of [radius]. */
    fun sweepDeg(length: Float, radius: Float): Float = if (radius <= 0f) 0f else toDeg(length / radius)

    /** How long the span is on a ring of [radius]. */
    fun upperLength(radius: Float): Float = (radius * UPPER_SWEEP_DEG * PI / 180.0).toFloat()

    /** The angle of the point [x] along the line, for a line that starts at [startDeg] on a ring of [radius]. */
    fun angleAt(x: Float, radius: Float, startDeg: Float = TOP_DEG): Float = startDeg + sweepDeg(x, radius)

    /** Where a line [sweep] degrees long starts: centred on twelve o'clock. */
    fun upperStart(sweep: Float): Float = TOP_DEG - sweep / 2f

    /** The angle a fraction [through] (0..1) of the way along the span: where the moon is under a line set flat. */
    fun spanAngle(through: Float): Float = UPPER_FROM_DEG + UPPER_SWEEP_DEG * through.coerceIn(0f, 1f)

    /**
     * Fills [out] with the angle of the centre of each of the first [count] glyphs, whose flat left edges are [lefts] and widths
     * [widths] (already scaled), on a ring of [radius] starting at [startDeg]. A glyph is turned to its centre's angle, so a wide
     * letter sits on the ring as a chord rather than leaning off it.
     */
    fun place(lefts: FloatArray, widths: FloatArray, count: Int, radius: Float, startDeg: Float, out: FloatArray) {
        for (i in 0 until count) out[i] = angleAt(lefts[i] + widths[i] / 2f, radius, startDeg)
    }

    /** How a line fits: the scale it is set at, the word its second arc starts at (-1: one arc), or [flat]: not on the ring. */
    class Fit(val scale: Float, val split: Int, val flat: Boolean)

    /**
     * Fits a line of [count] words, [widths] wide with [space] between them (all at full size), to the span of [inner]: at full
     * size if it fits, smaller down to [SCALE_FLOOR], then over two arcs (the first words on [outer], the rest on [inner], split
     * where the two can be set largest) at no less than [SCALE_FLOOR] either, and otherwise flat.
     */
    fun fitUpper(widths: FloatArray, count: Int, space: Float, inner: Float, outer: Float): Fit {
        var total = 0f
        for (i in 0 until count) total += widths[i]
        total += space * (count - 1).coerceAtLeast(0)
        val single = if (total <= 0f) 1f else minOf(1f, upperLength(inner) / total)
        if (single >= SCALE_FLOOR) return Fit(single, -1, flat = false)
        var best = -1
        var bestScale = 0f
        var first = 0f
        for (k in 1 until count) {
            first += widths[k - 1] + if (k > 1) space else 0f
            val second = total - first - space
            val scale = minOf(1f, upperLength(outer) / first.coerceAtLeast(1e-3f), upperLength(inner) / second.coerceAtLeast(1e-3f))
            if (scale > bestScale) { bestScale = scale; best = k }
        }
        return if (best > 0 && bestScale >= SCALE_FLOOR) Fit(bestScale, best, flat = false) else Fit(1f, -1, flat = true)
    }

    /** The scale the next line ([total] long) is set at on its ring of [radius]: to fit the span, down to [NEXT_SCALE_FLOOR]. */
    fun nextScale(total: Float, radius: Float): Float =
        if (total <= 0f) 1f else minOf(1f, upperLength(radius) / total).coerceAtLeast(NEXT_SCALE_FLOOR)

    /** The x of the point at [deg] on a ring of [radius] round ([cx], [cy]); [y] the same for y. */
    fun x(cx: Float, radius: Float, deg: Float): Float = cx + radius * cos(toRad(deg))
    fun y(cy: Float, radius: Float, deg: Float): Float = cy + radius * sin(toRad(deg))

    /** The canvas rotation that stands a glyph at [deg] upright on the ring: its top towards the outside. */
    fun glyphRotation(deg: Float): Float = deg + 90f

    /**
     * The rings of an Orbital stage, outwards: the cover disc; the track the moon runs on; the baseline of the line being sung
     * ([inner]), set outside the track with [clear] between the track and the line's descenders; the baseline of a split line's
     * first half ([outer]), [ARC_GAP_LINES] line heights further out; and the next line's baseline, [NEXT_GAP_LINES] line
     * heights beyond the line being sung, on [next] after a line on one arc and on [nextSplit] after one on two. [half] is the
     * stage's half size and [clear] the clearance, in px.
     */
    class Rings(
        val disc: Float,
        val track: Float,
        val inner: Float,
        val outer: Float,
        val next: Float,
        val nextSplit: Float,
        val fontPx: Float,
        val nextFontPx: Float,
        val lineHeight: Float,
        val half: Float,
        val clear: Float,
    )

    /**
     * The rings of a stage [size] across (its smaller side), with [dp] pixels to a dp. Laid out from the outside in, so the next
     * line's letters stay inside the stage whatever comes; the disc takes what is left in the middle.
     */
    fun rings(size: Float, dp: Float = 1f): Rings {
        val half = size / 2f
        val font = half * FONT_OF_HALF
        val line = font * LINE_EM
        val nextFont = font * NEXT_FONT_OF_FONT
        val nextSplit = half - MARGIN_DP * dp - nextFont * ASCENT_EM
        val outer = nextSplit - NEXT_GAP_LINES * line
        val inner = outer - ARC_GAP_LINES * line
        val track = inner - font * DESCENT_EM - CLEAR_DP * dp
        return Rings(
            disc = (track - maxOf(DISC_GAP_DP * dp, track * 0.1f)).coerceAtLeast(0f),
            track = track,
            inner = inner,
            outer = outer,
            next = inner + NEXT_GAP_LINES * line,
            nextSplit = nextSplit,
            fontPx = font,
            nextFontPx = nextFont,
            lineHeight = line,
            half = half,
            clear = CLEAR_DP * dp,
        )
    }

    /** The clearance between the track and the line's descenders, and what is kept free at the stage's edge, in dp. */
    const val CLEAR_DP = 8f
    const val MARGIN_DP = 4f
    private const val DISC_GAP_DP = 8f

    /** Between the two arcs of one line, and from the line being sung to the next line's ring, in line heights. */
    const val ARC_GAP_LINES = 1.3f
    const val NEXT_GAP_LINES = 1.5f

    private const val FONT_OF_HALF = 0.105f
    private const val NEXT_FONT_OF_FONT = 0.7f
    const val LINE_EM = 1.18f
    private const val ASCENT_EM = 0.8f
    private const val DESCENT_EM = 0.24f

    private fun toDeg(rad: Float): Float = (rad * 180.0 / PI).toFloat()
    private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()
}
