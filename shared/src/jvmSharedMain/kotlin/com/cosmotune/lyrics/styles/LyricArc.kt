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
 * Reading comes first: a line keeps to the upper arc ([UPPER_FROM_DEG] to [UPPER_TO_DEG]), where its letters stand more or less
 * upright, rather than running round the side where they turn sideways and then upside down. A line too long for that arc is
 * set smaller, down to [SCALE_FLOOR]; past that it is split into two concentric arcs, the first half on the outer one (read
 * first, as the upper of two rows), the rest on the inner.
 */
object LyricArc {
    /** Twelve o'clock. */
    const val TOP_DEG = -90f

    /** The upper arc a line keeps to: from a little above nine o'clock to a little below three. */
    const val UPPER_FROM_DEG = -160f
    const val UPPER_TO_DEG = 20f
    const val UPPER_SWEEP_DEG = UPPER_TO_DEG - UPPER_FROM_DEG

    /** A line short enough to sit within this much either side of twelve o'clock is centred there. */
    const val CENTRED_SWEEP_DEG = 140f

    /** The smallest a line is set to stay on one arc; below it the line is split over two. */
    const val SCALE_FLOOR = 0.7f

    /** The smallest a line is ever set (a very long line, even split, then runs a little past the arc's end). */
    const val SCALE_MIN = 0.45f

    /** How many degrees [length] of text takes round a ring of [radius]. */
    fun sweepDeg(length: Float, radius: Float): Float = if (radius <= 0f) 0f else toDeg(length / radius)

    /** How long the upper arc is on a ring of [radius]. */
    fun upperLength(radius: Float): Float = (radius * UPPER_SWEEP_DEG * PI / 180.0).toFloat()

    /** The angle of the point [x] along the line, for a line that starts at [startDeg] on a ring of [radius]. */
    fun angleAt(x: Float, radius: Float, startDeg: Float = TOP_DEG): Float = startDeg + sweepDeg(x, radius)

    /**
     * Where a line [sweep] degrees long starts: centred on twelve o'clock while it fits [CENTRED_SWEEP_DEG], else from the
     * start of the upper arc, so it never reaches past [UPPER_TO_DEG] while it fits the arc at all.
     */
    fun upperStart(sweep: Float): Float = if (sweep <= CENTRED_SWEEP_DEG) TOP_DEG - sweep / 2f else UPPER_FROM_DEG

    /**
     * Fills [out] with the angle of the centre of each of the first [count] glyphs, whose flat left edges are [lefts] and widths
     * [widths] (already scaled), on a ring of [radius] starting at [startDeg]. A glyph is turned to its centre's angle, so a wide
     * letter sits on the ring as a chord rather than leaning off it.
     */
    fun place(lefts: FloatArray, widths: FloatArray, count: Int, radius: Float, startDeg: Float, out: FloatArray) {
        for (i in 0 until count) out[i] = angleAt(lefts[i] + widths[i] / 2f, radius, startDeg)
    }

    /** How a line fits the upper arc: the scale it is set at, and the word the second arc starts at (-1: one arc). */
    class Fit(val scale: Float, val split: Int)

    /**
     * Fits a line of [count] words, [widths] wide with [space] between them (all at full size), to the upper arc of [inner]: at
     * full size if it fits, smaller down to [SCALE_FLOOR], and past that over two arcs, the first words on [outer] (when there is
     * one) and the rest on [inner], split where the two can be set largest. A line that will not fit even so is set at
     * [SCALE_MIN] and runs on.
     */
    fun fitUpper(widths: FloatArray, count: Int, space: Float, inner: Float, outer: Float?): Fit {
        var total = 0f
        for (i in 0 until count) total += widths[i]
        total += space * (count - 1).coerceAtLeast(0)
        val single = if (total <= 0f) 1f else minOf(1f, upperLength(inner) / total)
        if (single >= SCALE_FLOOR || outer == null || count < 2) return Fit(single.coerceAtLeast(SCALE_MIN), -1)
        var best = -1
        var bestScale = 0f
        var first = 0f
        for (k in 1 until count) {
            first += widths[k - 1] + if (k > 1) space else 0f
            val second = total - first - space
            val scale = minOf(1f, upperLength(outer) / first.coerceAtLeast(1e-3f), upperLength(inner) / second.coerceAtLeast(1e-3f))
            if (scale > bestScale) { bestScale = scale; best = k }
        }
        return if (bestScale > single) Fit(bestScale.coerceAtLeast(SCALE_MIN), best) else Fit(single.coerceAtLeast(SCALE_MIN), -1)
    }

    /** The x of the point at [deg] on a ring of [radius] round ([cx], [cy]); [y] the same for y. */
    fun x(cx: Float, radius: Float, deg: Float): Float = cx + radius * cos(toRad(deg))
    fun y(cy: Float, radius: Float, deg: Float): Float = cy + radius * sin(toRad(deg))

    /** The canvas rotation that stands a glyph at [deg] upright on the ring: its top towards the outside. */
    fun glyphRotation(deg: Float): Float = deg + 90f

    /**
     * The rings of an Orbital stage, outwards: the cover disc; the track the moon runs on; the baseline of the line being sung
     * ([inner]), set outside the track with [CLEAR_DP] between the track and the line's descenders; the baseline of a split
     * line's first half ([outer]), a line height further out; and the next line's baseline, [NEXT_GAP_LINES] line heights
     * beyond the line being sung, on [next] after a line on one arc and on [nextSplit] after one on two.
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
        val inner = outer - line
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
        )
    }

    /** The clearance between the track and the line's descenders, and what is kept free at the stage's edge, in dp. */
    const val CLEAR_DP = 8f
    const val MARGIN_DP = 4f
    private const val DISC_GAP_DP = 8f

    /** How far the next line's ring stands from the line being sung's, in line heights. */
    const val NEXT_GAP_LINES = 1.5f

    private const val FONT_OF_HALF = 0.105f
    private const val NEXT_FONT_OF_FONT = 0.7f
    const val LINE_EM = 1.18f
    private const val ASCENT_EM = 0.8f
    private const val DESCENT_EM = 0.24f

    private fun toDeg(rad: Float): Float = (rad * 180.0 / PI).toFloat()
    private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()
}
