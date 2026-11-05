package com.cosmotune.design

/**
 * The scrim over the video on Now Playing (`01-dynamic-colour.md` section 5; `v2.js` `V2.scrimAlpha`): a top band, a bottom
 * band, or one tall gradient while the lyrics are open. The colour is the palette's `scrim`; this is only its opacity.
 */
object Scrim {
    const val TOP_BAND_DP = 190f
    const val BOTTOM_BAND_DP = 470f

    // (y in dp from the band's start, alpha).
    private val TOP_DP = floatArrayOf(0f, 0.72f, 90f, 0.64f, 150f, 0.30f, 190f, 0f)
    private val BOTTOM_DP = floatArrayOf(0f, 0f, 90f, 0.55f, 190f, 0.86f, 470f, 0.90f)

    /** (y as a fraction of the top band, alpha) pairs, for a vertical gradient over the band. */
    val TOP_STOPS: FloatArray = fractions(TOP_DP, TOP_BAND_DP)

    /** (y as a fraction of the bottom band, alpha) pairs. */
    val BOTTOM_STOPS: FloatArray = fractions(BOTTOM_DP, BOTTOM_BAND_DP)

    /** (y as a fraction of the screen height, alpha) pairs while the lyrics are open. */
    val LYRICS_STOPS: FloatArray = floatArrayOf(0f, 0.70f, 0.3f, 0.88f, 1f, 0.90f)

    /** The scrim's opacity [yDp] down a screen [heightDp] tall. */
    fun alphaAt(yDp: Float, heightDp: Float, lyrics: Boolean): Float {
        if (lyrics) return interpolate(LYRICS_STOPS, yDp / heightDp)
        val top = if (yDp <= TOP_BAND_DP) interpolate(TOP_DP, yDp) else 0f
        val bottomY = yDp - (heightDp - BOTTOM_BAND_DP)
        val bottom = if (bottomY <= 0f) 0f else interpolate(BOTTOM_DP, bottomY)
        return 1f - (1f - top) * (1f - bottom)
    }

    private fun fractions(stopsDp: FloatArray, band: Float) =
        FloatArray(stopsDp.size) { if (it % 2 == 0) stopsDp[it] / band else stopsDp[it] }

    /** Piecewise linear through (x, y) pairs; the first and last values hold outside them. */
    private fun interpolate(stops: FloatArray, x: Float): Float {
        if (x <= stops[0]) return stops[1]
        var i = 2
        while (i < stops.size) {
            if (x <= stops[i]) {
                val x0 = stops[i - 2]
                val y0 = stops[i - 1]
                return y0 + (stops[i + 1] - y0) * (x - x0) / (stops[i] - x0)
            }
            i += 2
        }
        return stops[stops.size - 1]
    }
}
