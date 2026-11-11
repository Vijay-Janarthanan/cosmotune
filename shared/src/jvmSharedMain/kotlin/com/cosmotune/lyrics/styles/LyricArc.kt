package com.cosmotune.lyrics.styles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Text on a circle, for Orbital: the line set along a ring round the cover disc, glyph by glyph, as SVG's `textPath` sets it on
 * the board. The glyphs are measured flat first (each one's left edge and width along the line, kerning included, from the
 * line's own layout); here those flat positions become angles. Angles are degrees, clockwise from three o'clock on a screen (y
 * down), so -90 is twelve o'clock; a glyph's baseline sits on the ring and its top points away from the centre.
 */
object LyricArc {
    /** Twelve o'clock, where the board starts the line. */
    const val TOP_DEG = -90f

    /** The most of the ring a line may take, so its end never runs into its start. */
    const val MAX_SWEEP_DEG = 324f

    /** How many degrees [length] of text takes round a ring of [radius]. */
    fun sweepDeg(length: Float, radius: Float): Float = if (radius <= 0f) 0f else toDeg(length / radius)

    /** The angle of the point [x] along the line, for a line that starts at [startDeg] on a ring of [radius]. */
    fun angleAt(x: Float, radius: Float, startDeg: Float = TOP_DEG): Float = startDeg + sweepDeg(x, radius)

    /**
     * Fills [out] with the angle of the centre of each of the first [count] glyphs, whose flat left edges are [lefts] and widths
     * [widths] (already scaled), on a ring of [radius] starting at [startDeg]. A glyph is turned to its centre's angle, so a wide
     * letter sits on the ring as a chord rather than leaning off it.
     */
    fun place(lefts: FloatArray, widths: FloatArray, count: Int, radius: Float, startDeg: Float, out: FloatArray) {
        for (i in 0 until count) out[i] = angleAt(lefts[i] + widths[i] / 2f, radius, startDeg)
    }

    /**
     * The scale a line [length] long is drawn at to fit [maxSweepDeg] of a ring of [radius]: 1 when it fits, smaller when it
     * would run round into itself, never below [minScale] (a very long line is then cut at the end of the sweep by the caller).
     */
    fun fitScale(length: Float, radius: Float, maxSweepDeg: Float = MAX_SWEEP_DEG, minScale: Float = 0.5f): Float {
        val sweep = sweepDeg(length, radius)
        if (sweep <= maxSweepDeg || sweep <= 0f) return 1f
        return (maxSweepDeg / sweep).coerceAtLeast(minScale)
    }

    /** The x of the point at [deg] on a ring of [radius] round ([cx], [cy]); [y] the same for y. */
    fun x(cx: Float, radius: Float, deg: Float): Float = cx + radius * cos(toRad(deg))
    fun y(cy: Float, radius: Float, deg: Float): Float = cy + radius * sin(toRad(deg))

    /** The canvas rotation that stands a glyph at [deg] upright on the ring: its top towards the outside. */
    fun glyphRotation(deg: Float): Float = deg + 90f

    /**
     * The radii of an Orbital stage [size] across (the smaller side): the disc, the ring the line being sung sits on, and the outer
     * ring the next line waits on, in the board's proportions (78, 112 and 138 of a 300-wide stage; the type a little larger than the board's, for reading at arm's length). [fontPx] is the line's size.
     */
    class Rings(val disc: Float, val current: Float, val next: Float, val fontPx: Float, val nextFontPx: Float)

    fun rings(size: Float): Rings {
        val half = size / 2f
        return Rings(
            disc = half * DISC_OF_HALF,
            current = half * CURRENT_OF_HALF,
            next = half * NEXT_OF_HALF,
            fontPx = half * FONT_OF_HALF,
            nextFontPx = half * NEXT_FONT_OF_HALF,
        )
    }

    private const val DISC_OF_HALF = 78f / 150f
    private const val CURRENT_OF_HALF = 112f / 150f
    private const val NEXT_OF_HALF = 138f / 150f
    private const val FONT_OF_HALF = 17f / 150f
    private const val NEXT_FONT_OF_HALF = 12f / 150f

    private fun toDeg(rad: Float): Float = (rad * 180.0 / PI).toFloat()
    private fun toRad(deg: Float): Float = (deg * PI / 180.0).toFloat()
}
