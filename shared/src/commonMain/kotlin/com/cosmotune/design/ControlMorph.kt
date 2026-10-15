package com.cosmotune.design

/**
 * Where a control's shape is heading: corner radius (dp), superellipse exponent (2 is a circle or pill, 4 a squircle) and the
 * factor the control is scaled by. [scale] is the width factor; [scaleY] is the height factor, which differs from it only
 * for a pressed B button (it squishes sideways and its height holds).
 */
data class MorphTarget(val radiusDp: Float, val exponent: Float, val scale: Float, val scaleY: Float = scale)

/**
 * The shape-morph numbers of the B+C component system (`v3/01-components-spec-bc.md`), all animated on [Springs.PRESS].
 * A radius is `k x height`; a control that is round (k = 0.5) is drawn with exponent 2, every other one as a squircle
 * (exponent 4). B controls sit in the page; with `floating = true` the C variants, which sit in glass over content or video.
 *
 * Every number is a named constant, so a later change of the design is a change of one constant. These replace the family-A
 * values (circle at rest and a squircle when playing or on) that the first draft of this task used.
 */
object ControlMorph {
    /** k of a circle or pill. */
    const val ROUND_K = 0.5f

    // B primary, secondary and destructive buttons.
    const val PRIMARY_REST_K = 0.30f
    const val PRIMARY_PRESSED_K = 0.18f

    /** The width factor of a pressed B button: it squishes sideways and its height holds. */
    const val PRIMARY_PRESSED_SCALE_X = 0.965f

    // B icon button.
    const val ICON_REST_K = 0.30f
    const val ICON_PRESSED_K = 0.20f
    const val ICON_PRESSED_SCALE = 0.94f

    // B hero play button: a rounded square when paused, a circle while playing.
    const val PLAY_PAUSED_K = 0.32f
    const val PLAY_PRESSED_K = 0.20f
    const val PLAY_PRESSED_SCALE = 0.92f

    // B filter chip.
    const val CHIP_REST_K = 0.22f
    const val CHIP_PRESSED_K = 0.14f

    // B segmented control: absolute radii in dp. The outer ends are a pill; the spec gives no pressed radius, so the
    // pressed value is the family-A one (16).
    const val SEGMENT_INNER_RADIUS_DP = 6f
    const val SEGMENT_PRESSED_RADIUS_DP = 16f

    // C (floating) controls.
    const val C_PLAY_PLAYING_K = 0.32f
    const val C_PLAY_PRESSED_K = 0.26f
    const val C_PRIMARY_PRESSED_SCALE = 0.97f
    const val C_ICON_PRESSED_SCALE = 0.94f

    private fun exponentFor(k: Float) = if (k >= ROUND_K) 2f else 4f

    private fun shape(heightDp: Float, k: Float, scale: Float = 1f, scaleY: Float = scale) =
        MorphTarget(k * heightDp, exponentFor(k), scale, scaleY)

    /** The play button: B is a rounded square that becomes a circle while playing; C is the reverse (see the spec). */
    fun playButton(sizeDp: Float, playing: Boolean, pressed: Boolean, floating: Boolean = false): MorphTarget = when {
        pressed -> shape(sizeDp, if (floating) C_PLAY_PRESSED_K else PLAY_PRESSED_K, PLAY_PRESSED_SCALE)
        floating -> shape(sizeDp, if (playing) C_PLAY_PLAYING_K else ROUND_K)
        playing -> shape(sizeDp, ROUND_K)
        else -> shape(sizeDp, PLAY_PAUSED_K)
    }

    /** The icon button: B turns round when on; a C icon button is always a circle. */
    fun iconButton(sizeDp: Float, on: Boolean, pressed: Boolean, floating: Boolean = false): MorphTarget = when {
        floating -> shape(sizeDp, ROUND_K, if (pressed) C_ICON_PRESSED_SCALE else 1f)
        pressed -> shape(sizeDp, ICON_PRESSED_K, ICON_PRESSED_SCALE)
        on -> shape(sizeDp, ROUND_K)
        else -> shape(sizeDp, ICON_REST_K)
    }

    /** The filter chip: B turns round when selected; a C chip is always a pill. */
    fun chip(heightDp: Float, selected: Boolean, pressed: Boolean, floating: Boolean = false): MorphTarget = when {
        floating -> shape(heightDp, ROUND_K)
        pressed -> shape(heightDp, CHIP_PRESSED_K)
        selected -> shape(heightDp, ROUND_K)
        else -> shape(heightDp, CHIP_REST_K)
    }

    /** Inner corners of an unselected segment are 6 dp (outer 20 dp; see CosmoSegmented); a selected one is a full pill. */
    fun segment(heightDp: Float, selected: Boolean, pressed: Boolean): MorphTarget = when {
        pressed -> MorphTarget(SEGMENT_PRESSED_RADIUS_DP, 4f, 1f)
        selected -> shape(heightDp, ROUND_K)
        else -> MorphTarget(SEGMENT_INNER_RADIUS_DP, 4f, 1f)
    }

    /** The primary button: B is a rounded square that squares up and squishes when pressed; a C primary is a pill. */
    fun primary(heightDp: Float, pressed: Boolean, floating: Boolean = false): MorphTarget = when {
        floating -> shape(heightDp, ROUND_K, if (pressed) C_PRIMARY_PRESSED_SCALE else 1f)
        pressed -> shape(heightDp, PRIMARY_PRESSED_K, PRIMARY_PRESSED_SCALE_X, 1f)
        else -> shape(heightDp, PRIMARY_REST_K)
    }
}
