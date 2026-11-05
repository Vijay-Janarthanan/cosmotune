package com.cosmotune.design

/**
 * Settings > Appearance > Intensity: how much colour the cover brings (`v2/01-dynamic-colour.md` section 3, rule 7). Calm,
 * Balanced and Rich multiply the chroma budget and the accent chroma by [multiplier], and the engine's chroma ceilings and gamut
 * share with it (`CoverPaletteSpec.forIntensity`), so a cover already at a ceiling changes too; the rails hold at every setting, a
 * grey cover keeps its neutral grounds and the brand palettes (shown while there is no cover) stay as they are.
 */
object Intensity {
    const val CALM = 0
    const val BALANCED = 1
    const val RICH = 2
    const val COUNT = 3
    const val DEFAULT = BALANCED

    private val multipliers = doubleArrayOf(0.7, 1.0, 1.25)

    /** The factor of the option at [index] (0 Calm, 1 Balanced, 2 Rich). Another index is held to the nearest of the three. */
    fun multiplier(index: Int): Double = multipliers[clamp(index)]

    /** [index] held inside 0 until [COUNT]. */
    fun clamp(index: Int): Int = index.coerceIn(CALM, RICH)
}
