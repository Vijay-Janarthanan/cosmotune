package com.cosmotune.design

/**
 * The pull-to-refresh blob of the B+C spec (`.ptr` in `components-v3bc.css`): a 44 dp circle holding an 18 dp blob that grows,
 * turns and squares up as the list is pulled. The board draws two frames, `.p30` (scale 0.55, turned 30 degrees) and `.p70`
 * (scale 0.85, turned 120 degrees, corners at 30 %); everything between them is a straight line, and everything outside
 * holds the nearest frame, so an overshooting pull (the fraction goes past 1) changes nothing.
 *
 * [p] is the pull fraction, 0 at rest and 1 at the refresh threshold. Pure: the Compose side reads these in `graphicsLayer`.
 */
object PullIndicator {
    /** The circle and the blob inside it, and how far down the circle travels at a fraction of 1 (the board's 64 dp). */
    const val SIZE_DP = 44f
    const val BLOB_DP = 18f
    const val TRAVEL_DP = 64f

    private const val FROM = 0.3f
    private const val TO = 0.7f
    private const val SCALE_FROM = 0.55f
    private const val SCALE_TO = 0.85f
    private const val ROTATION_FROM = 30f
    private const val ROTATION_TO = 120f
    private const val CORNER_FROM = 0.5f
    private const val CORNER_TO = 0.3f

    /** How far between the two frames of the board [p] is: 0 up to 0.3, 1 from 0.7, a line between (a pull that is not a number is the first frame). */
    private fun along(p: Float): Float = if (p.isNaN()) 0f else ((p - FROM) / (TO - FROM)).coerceIn(0f, 1f)

    /** The blob's scale: 0.55 up to a pull of 0.3, 0.85 from 0.7. */
    fun scale(p: Float): Float = SCALE_FROM + (SCALE_TO - SCALE_FROM) * along(p)

    /** The blob's turn in degrees: 30 up to a pull of 0.3, 120 from 0.7. */
    fun rotationDeg(p: Float): Float = ROTATION_FROM + (ROTATION_TO - ROTATION_FROM) * along(p)

    /** The blob's corner radius as a fraction of its side: 0.5 (a circle) up to a pull of 0.3, 0.3 (`border-radius: 30%`) from 0.7. */
    fun cornerFraction(p: Float): Float = CORNER_FROM + (CORNER_TO - CORNER_FROM) * along(p)
}
