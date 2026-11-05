package com.cosmotune.design

import kotlin.math.max

/**
 * The thick slider (volume, equaliser) of the B+C spec, `components-v3bc.css` `.sl2`: two track pieces, 16 dp tall, round a
 * 4 x 44 dp handle with a 6 dp gap on each side. The pieces meet the handle with the small (inner) corner and the bar's ends
 * with the large (outer) one. The drawing is Compose's; the numbers and the pointer maths are here so they are tested.
 */
object SliderMetrics {
    const val HEIGHT_DP = 44f
    const val TRACK_DP = 16f
    const val HANDLE_W_DP = 4f
    const val HANDLE_H_DP = 44f
    const val HANDLE_GAP_DP = 6f
    const val OUTER_RADIUS_DP = 8f
    const val INNER_RADIUS_DP = 3f

    /** The accent dot near the right end of the unplayed piece: 4 dp, 6 dp from the end. */
    const val STOP_DOT_DP = 4f
    const val STOP_INSET_DP = 6f

    /** Tracks and handle are drawn this tall at least; the touch area is never shorter than [MIN_TOUCH_DP]. */
    const val MIN_TOUCH_DP = 48f

    /** The unplayed piece is the neutral track colour: the text colour at this opacity (dark 22 %, light 16 %, the engine's own). */
    const val TRACK_ALPHA_DARK = 0.22f
    const val TRACK_ALPHA_LIGHT = 0.16f

    /** From the handle's centre to the nearest end of either piece: the gap plus half the handle, 8 dp. */
    const val HALF_SLOT_DP = HANDLE_GAP_DP + HANDLE_W_DP / 2f

    private const val SMALL_STEP = 0.01f
    private const val BIG_STEP = 0.10f

    fun trackAlpha(dark: Boolean): Float = if (dark) TRACK_ALPHA_DARK else TRACK_ALPHA_LIGHT

    /** The value a pointer at [x] means on a bar [width] wide. */
    fun valueAt(x: Float, width: Float): Float = if (width <= 0f) 0f else (x / width).coerceIn(0f, 1f)

    /**
     * Where the handle's centre is for [value]: its share of the width, kept [halfSlot] clear of both ends so the handle and
     * its gaps always fit (at 0 and 1 the played or unplayed piece is simply empty).
     */
    fun handleCentre(value: Float, width: Float, halfSlot: Float): Float =
        (value.coerceIn(0f, 1f) * width).coerceIn(halfSlot, max(halfSlot, width - halfSlot))

    /** Right end of the played piece. Never negative. */
    fun activeEnd(centre: Float, halfSlot: Float): Float = max(0f, centre - halfSlot)

    /** Left end of the unplayed piece. */
    fun inactiveStart(centre: Float, halfSlot: Float): Float = centre + halfSlot

    /** The tenth [fraction] is in (0 to 10): a scrub gives a light tick each time it changes. */
    fun tickOf(fraction: Float): Int = (fraction * 10f).toInt()

    /** An arrow key moves one hundredth, with Shift or Page Up and Down a tenth; always inside 0 to 1. */
    fun stepped(value: Float, direction: Int, big: Boolean): Float =
        (value + direction * (if (big) BIG_STEP else SMALL_STEP)).coerceIn(0f, 1f)

    /** The height of the touch area for a control drawn [drawnDp] tall: at least 48 dp. */
    fun touchHeightDp(drawnDp: Float): Float = max(drawnDp, MIN_TOUCH_DP)
}
