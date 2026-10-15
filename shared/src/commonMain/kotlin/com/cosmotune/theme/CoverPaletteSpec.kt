package com.cosmotune.theme

/**
 * Every number the cover palette uses, in one place, so the design can be retuned without touching
 * [CoverPalette]'s logic. The defaults are the current design.
 *
 * Colours are described in HCT (hue, chroma, tone): hue in degrees, chroma as CAM16 chroma (0 is grey, about 40
 * is a calm colour, about 110 is the most vivid red an sRGB screen shows), and tone as CIE L* (0 black, 100 white;
 * a tone difference of 50 between two colours guarantees a contrast ratio of at least 4.5). A requested chroma the
 * screen cannot show at that hue and tone is lowered until it fits; channels are never clipped.
 *
 * **For the design handoff: the defaults are provisional, and three things in them need a decision.**
 * - In the dark theme `surface` (tone 10) lies on `backgroundMid` (tone 10) at a contrast of 1.00:1, and
 *   `surfaceVariant` (tone 17) on `backgroundTop` (tone 15) at 1.05:1. A card drawn with them is invisible by
 *   luminance alone: the screens need a border or a scrim, or the tones need retuning so that cards and background
 *   are apart.
 * - sRGB narrows towards white, so the light theme's asked-for background chroma of 6, 10 and 14 at tones 98, 96 and
 *   94 cannot be shown: the apricot seed gives about 1.6, 4.7 and 7.7. The light backgrounds are a faint tint.
 * - The same limit applies to the dark button gradient's start (chroma 42 at tone 82): an orange-red seed gives about 26.
 */
data class CoverPaletteSpec(
    // --- reading the cover ---------------------------------------------------------------------

    /** The cover is sampled down to at most this many pixels on each side (nearest pixel, centre of each block). */
    val maxSampleSide: Int = 64,
    /** A sampled pixel with an alpha below this (0 to 255) is dropped. */
    val minAlpha: Int = 128,
    /** How many colours the quantiser divides the sampled cover into. */
    val quantizerColors: Int = 128,
    /**
     * A cover made of at most this many distinct colours (counted at 5 bits per channel) is read from its colour
     * histogram as it is. Flat art (a few solid colours) must not go through the quantiser: it merges them into one
     * muddy blend.
     */
    val maxFlatBins: Int = 128,
    /** A seed with a chroma below this counts as no colour: the cover is grey, white or black. */
    val minSeedChroma: Double = 10.0,
    /** Two seeds closer than this (OKLab distance times 100) count as the same colour in [CoverPalette.settle]. */
    val settleMinDeltaE: Float = 3.0f,

    // --- the safety rails ----------------------------------------------------------------------

    /** Text needs at least this contrast ratio (WCAG) against what it is drawn on. */
    val minTextContrast: Double = 4.5,
    /** The accent needs at least this contrast ratio against the surface (WCAG, user interface components). */
    val minComponentContrast: Double = 3.0,
    /** A contrast check passes at the minimum plus this, so that rounding to 8 bits cannot bring it just under. */
    val contrastMargin: Double = 0.05,
    /** A failing contrast is repaired by moving the foreground tone by this much per step. */
    val toneStep: Double = 2.0,
    /** In the dark theme no background or surface colour has a relative luminance above this ("not too bright"). */
    val maxDarkBackgroundLuminance: Double = 0.12,
    /** No accent colour (accent, accent strong, button gradient, glow) has a tone above this. */
    val maxAccentTone: Double = 84.0,
    /** If no tone of the on-accent colour is readable, it becomes the better of these two. */
    val onAccentNearBlackTone: Double = 4.0,
    val onAccentNearWhiteTone: Double = 98.0,
    val onAccentFlipChroma: Double = 4.0,

    // --- the accent family (both themes) -------------------------------------------------------

    /** The accent's chroma is the seed's chroma held between these. */
    val accentChromaMin: Double = 36.0,
    val accentChromaMax: Double = 64.0,
    /** The strong accent has the accent's chroma plus this. */
    val accentStrongChromaBoost: Double = 4.0,
    /** The button gradient's two ends: the hue turns by this many degrees from the seed's, and the chroma. */
    val buttonStartHueShift: Double = -14.0,
    val buttonStartChroma: Double = 42.0,
    val buttonEndHueShift: Double = 14.0,
    val buttonEndChroma: Double = 52.0,
    /** The glow is the accent's hue at this chroma. */
    val glowChroma: Double = 48.0,

    // --- dark theme ----------------------------------------------------------------------------

    val darkAccentTone: Double = 78.0,
    val darkAccentStrongTone: Double = 68.0,
    val darkButtonStartTone: Double = 82.0,
    val darkButtonEndTone: Double = 70.0,
    val darkGlowTone: Double = 60.0,

    /** A background stop's chroma is the seed's chroma times the factor, but not above the max. */
    val darkBackgroundTopTone: Double = 15.0,
    val darkBackgroundTopChromaFactor: Double = 0.5,
    val darkBackgroundTopChromaMax: Double = 28.0,
    val darkBackgroundMidTone: Double = 10.0,
    val darkBackgroundMidChromaFactor: Double = 0.4,
    val darkBackgroundMidChromaMax: Double = 22.0,
    val darkBackgroundBottomTone: Double = 6.0,
    val darkBackgroundBottomChromaFactor: Double = 0.3,
    val darkBackgroundBottomChromaMax: Double = 16.0,

    val darkSurfaceTone: Double = 10.0,
    val darkSurfaceChroma: Double = 10.0,
    val darkSurfaceVariantTone: Double = 17.0,
    val darkSurfaceVariantChroma: Double = 14.0,

    val darkTextPrimaryTone: Double = 96.0,
    val darkTextPrimaryChroma: Double = 4.0,
    val darkTextSecondaryTone: Double = 78.0,
    val darkTextSecondaryChroma: Double = 8.0,

    val darkOnAccentTone: Double = 8.0,
    val darkOnAccentChroma: Double = 6.0,

    // --- light theme ---------------------------------------------------------------------------

    val lightAccentTone: Double = 44.0,
    val lightAccentStrongTone: Double = 38.0,
    /** Darker than the dark theme's (82 and 70) so that one white on-accent colour reads on both ends (5.2:1 and better). */
    val lightButtonStartTone: Double = 46.0,
    val lightButtonEndTone: Double = 38.0,
    val lightGlowTone: Double = 70.0,

    /** A background stop's chroma is this, but never more than the seed's. */
    val lightBackgroundTopTone: Double = 98.0,
    val lightBackgroundTopChroma: Double = 6.0,
    val lightBackgroundMidTone: Double = 96.0,
    val lightBackgroundMidChroma: Double = 10.0,
    val lightBackgroundBottomTone: Double = 94.0,
    val lightBackgroundBottomChroma: Double = 14.0,

    val lightSurfaceTone: Double = 99.0,
    val lightSurfaceChroma: Double = 4.0,
    val lightSurfaceVariantTone: Double = 93.0,
    val lightSurfaceVariantChroma: Double = 8.0,

    val lightTextPrimaryTone: Double = 10.0,
    val lightTextPrimaryChroma: Double = 4.0,
    val lightTextSecondaryTone: Double = 35.0,
    val lightTextSecondaryChroma: Double = 8.0,

    val lightOnAccentTone: Double = 100.0,
    val lightOnAccentChroma: Double = 0.0,
)
