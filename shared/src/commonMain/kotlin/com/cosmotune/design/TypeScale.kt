package com.cosmotune.design

import kotlin.math.pow

/**
 * The fluid modular scale (design formulas, section 1). Pure; the sizes are in sp, so the user's font scale still multiplies them.
 * Which family, weight and width each step is drawn in belongs to the Compose type styles, not here.
 */
object TypeScale {
    const val CAPTION = -1
    const val BODY = 0
    const val TITLE_S = 1
    const val TITLE_M = 2
    const val TITLE_L = 3
    const val DISPLAY_S = 4
    const val DISPLAY_M = 5
    const val DISPLAY_L = 6
    const val DISPLAY_XL = 7

    /** A title longer than this many characters steps down from display-l to display-s. */
    const val LONG_TITLE_CHARS = 26

    /** size(n, w): 15 x 1.2^n at 360 dp to 16 x 1.25^n at 840 dp, clamped outside (02 section 1), in sp. */
    fun sizeSp(step: Int, windowWidthDp: Float): Float {
        val min = 15.0 * 1.2.pow(step)
        val max = 16.0 * 1.25.pow(step)
        val slope = (max - min) / 480.0
        return (min - slope * 360.0 + slope * windowWidthDp).coerceIn(min, max).toFloat()
    }

    fun titleStep(title: String): Int = if (title.length > LONG_TITLE_CHARS) DISPLAY_S else DISPLAY_L

    /** Devanagari to Malayalam (U+0900..U+0D7F) take line height 1.5. */
    fun needsTallLines(text: String): Boolean = text.any { it.code in 0x0900..0x0D7F }
}
