package com.cosmotune.design

/** Where a control's shape is heading: corner radius (dp), superellipse exponent (2 is a circle, 4 a squircle) and scale. */
data class MorphTarget(val radiusDp: Float, val exponent: Float, val scale: Float)

/**
 * The shape-morph table of the design formulas, section 3 (all on the [Springs.PRESS] spring).
 *
 * These numbers belong to the family-A buttons of the v2 design. The owner has since chosen the B+C component system,
 * which keeps these formulas but may change the numbers, so every one of them is a named constant below: the B+C
 * values replace them here and nowhere else.
 */
object ControlMorph {
    /** Circular corners at rest. */
    const val REST_EXPONENT = 2f

    /** Squircle corners while morphed or pressed. */
    const val MORPHED_EXPONENT = 4f

    // Play button: radius as a fraction of its size.
    const val PLAY_PLAYING_RADIUS = 0.30f
    const val PLAY_PRESSED_RADIUS = 0.20f
    const val PLAY_PRESSED_SCALE = 0.92f

    // Icon button: radius as a fraction of its size (16/48 on, 14/48 pressed).
    const val ICON_ON_RADIUS = 16f / 48f
    const val ICON_PRESSED_RADIUS = 14f / 48f
    const val ICON_PRESSED_SCALE = 0.94f

    // Chip: radius as a fraction of its height (10/36 selected, 16/36 pressed).
    const val CHIP_SELECTED_RADIUS = 10f / 36f
    const val CHIP_PRESSED_RADIUS = 16f / 36f

    // Segmented control: absolute radii in dp (the outer ends are a pill, the inner corners of an unselected segment are 6).
    const val SEGMENT_INNER_RADIUS_DP = 6f
    const val SEGMENT_PRESSED_RADIUS_DP = 16f

    // Primary button: absolute pressed radius in dp.
    const val PRIMARY_PRESSED_RADIUS_DP = 16f
    const val PRIMARY_PRESSED_SCALE = 0.96f

    private fun rest(size: Float) = MorphTarget(size / 2f, REST_EXPONENT, 1f)

    fun playButton(sizeDp: Float, playing: Boolean, pressed: Boolean) = when {
        pressed -> MorphTarget(PLAY_PRESSED_RADIUS * sizeDp, MORPHED_EXPONENT, PLAY_PRESSED_SCALE)
        playing -> MorphTarget(PLAY_PLAYING_RADIUS * sizeDp, MORPHED_EXPONENT, 1f)
        else -> rest(sizeDp)
    }

    fun iconButton(sizeDp: Float, on: Boolean, pressed: Boolean) = when {
        pressed -> MorphTarget(sizeDp * ICON_PRESSED_RADIUS, MORPHED_EXPONENT, ICON_PRESSED_SCALE)
        on -> MorphTarget(sizeDp * ICON_ON_RADIUS, MORPHED_EXPONENT, 1f)
        else -> rest(sizeDp)
    }

    fun chip(heightDp: Float, selected: Boolean, pressed: Boolean) = when {
        pressed -> MorphTarget(heightDp * CHIP_PRESSED_RADIUS, MORPHED_EXPONENT, 1f)
        selected -> MorphTarget(heightDp * CHIP_SELECTED_RADIUS, MORPHED_EXPONENT, 1f)
        else -> rest(heightDp)
    }

    /** Inner corners of an unselected segment are 6 dp (outer 20 dp; see CosmoSegmented); a selected one is a full pill. */
    fun segment(heightDp: Float, selected: Boolean, pressed: Boolean) = when {
        pressed -> MorphTarget(SEGMENT_PRESSED_RADIUS_DP, MORPHED_EXPONENT, 1f)
        selected -> rest(heightDp)
        else -> MorphTarget(SEGMENT_INNER_RADIUS_DP, MORPHED_EXPONENT, 1f)
    }

    fun primary(heightDp: Float, pressed: Boolean) =
        if (pressed) MorphTarget(PRIMARY_PRESSED_RADIUS_DP, MORPHED_EXPONENT, PRIMARY_PRESSED_SCALE) else rest(heightDp)
}
