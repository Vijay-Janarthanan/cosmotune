package com.cosmotune.theme

/**
 * A colour whose chroma is a share of a chroma budget, held under a ceiling: OKLCH `(l, min(factor * budget, max), hue)`.
 * The budget is the cover's chroma budget `c` for grounds, surfaces, text and shadows, and the accent chroma `A` for
 * the accent container and its text (see [CoverPaletteSpec]).
 */
data class ChromaRule(val l: Double, val factor: Double, val max: Double)

/** A colour whose chroma is a share of the accent chroma, held between a floor and a ceiling: `(l, clamp(factor * A, min, max))`. */
data class AccentChromaRule(val l: Double, val factor: Double, val min: Double, val max: Double)

/**
 * Every number the cover palette uses, in one place: the designer's dynamic-colour formulas
 * (`ui/v2/01-dynamic-colour.md`, implemented by `ui/v2/dynamic-colour.js`), so the design can be retuned without
 * touching the logic in [CoverPalette]. The defaults are the formulas as the owner approved them.
 *
 * Colours are OKLCH (L 0 to 1, chroma, hue in degrees; Bjorn Ottosson's OKLab, as in CSS Color 4). Chroma that sRGB
 * cannot show at that L and hue is lowered, keeping L and hue (CSS Color 4 gamut mapping), so a requested number is a
 * ceiling, not a promise: near white and for blues the screen shows less than the formula asks.
 *
 * Words used below: the **chroma budget** `c` is the seed's chroma times [intensity], at most [groundChromaMax] (or
 * [greyGroundChroma] for a grey cover); the **accent chroma** `A` is the seed's chroma times [intensity] held between
 * [accentChromaMin] and [accentChromaMax] (times [brightAccentChromaFactor] for a bright cover, and in the light theme
 * held between [lightAccentChromaMin] and [lightAccentChromaMax] instead); **k** is [brightGroundFactor] for a bright
 * cover and 1 otherwise, and applies to the background stops and the ambient light only.
 *
 * **Design note for the screens: cards are not told apart from the ground by colour alone.** WCAG contrast between a surface
 * tier and the background stop under it, over 61 test seeds (dark theme): `surface` (L 0.26) on `backgroundTop` (0.28) is
 * 1.05 to 1.08:1 and on `backgroundMid` (0.225) 1.09 to 1.11; `surfaceHigh` (0.30) on `backgroundTop` 1.06 to 1.08;
 * `surfaceVariant` (0.34) is the only tier clearly apart (1.22 to 1.62). Light theme: every tier is within 1.0 to 1.25:1 of
 * the stops. So a card needs what the designer drew on it (`02-design-formulas.md`, section 4): the two-shadow recipe in
 * the palette's `shadow` colour, the inner highlight (`inset 0 1 0 white 10 %` and a 0.5 dp rim at 7 % in the dark theme;
 * 80 % and a 6 % black rim in the light one), and for chips and fields the `outline` token. Do not rely on the fill alone.
 */
data class CoverPaletteSpec(
    // --- reading the cover ---------------------------------------------------------------------

    /** The cover is sampled down to at most this many pixels on each side (nearest pixel, centre of each block). */
    val maxSampleSide: Int = 64,
    /** A sampled pixel with an alpha below this (0 to 255) is dropped. (The designer keeps only fully opaque pixels.) */
    val minAlpha: Int = 128,
    /** Pre-filter: a pixel with an OKLab L below this is near-black and dropped. */
    val nearBlackL: Double = 0.16,
    /** Pre-filter: a pixel with an OKLab L above this is near-white and dropped. */
    val nearWhiteL: Double = 0.95,
    /** Pre-filter: a pixel (and a seed) with an OKLCH chroma below this is near-grey and dropped. */
    val nearGreyChroma: Double = 0.03,
    /** If less than this share of the pixels survive the pre-filter the cover is grey and gets the fallback palette. */
    val minColourShare: Double = 0.015,
    /** A seed lighter than this makes the cover bright. */
    val brightSeedL: Double = 0.86,
    /** A cover whose mean pixel L is above this is bright. */
    val brightMeanL: Double = 0.78,
    /** How many colours the quantiser divides the surviving pixels into. */
    val quantizerColors: Int = 128,
    /** How many hue-distinct colours the scorer returns (the first is the seed, the second the secondary). */
    val scoredColours: Int = 4,
    /**
     * A cover made of at most this many distinct colours (counted at 5 bits per channel) is read from its colour
     * histogram as it is. Flat art (a few solid colours) must not go through the quantiser: it merges them into one
     * muddy blend.
     */
    val maxFlatBins: Int = 128,
    /** The secondary colour counts only if its hue is at least this many degrees from the seed's; else the seed's hue is used. */
    val secondHueMinSeparation: Double = 25.0,

    // --- the chroma budget and the bright and yellow rules -------------------------------------

    /** Settings > Look: Calm 0.7, Balanced 1.0, Rich 1.25. It multiplies the chroma budget and the accent chroma before the caps. */
    val intensity: Double = 1.0,
    /** The chroma budget of grounds and surfaces is the seed's chroma times [intensity], but at most this. */
    val groundChromaMax: Double = 0.16,
    /** The chroma budget of a grey cover (a warm neutral). */
    val greyGroundChroma: Double = 0.012,
    /** The accent chroma `A` is held between these. */
    val accentChromaMin: Double = 0.07,
    val accentChromaMax: Double = 0.15,
    /** Every accent and button chroma is limited to this share of the largest in-gamut chroma at that L and hue. */
    val gamutMargin: Double = 0.85,
    /** Bright covers: the accent chroma is multiplied by this, and in the dark theme the accent L is lowered by [brightAccentLShift]. */
    val brightAccentChromaFactor: Double = 0.87,
    val brightAccentLShift: Double = 0.02,
    /** Bright covers: the background stops and the ambient light have their chroma multiplied by this (k). */
    val brightGroundFactor: Double = 0.7,
    /** Yellows (hue from [yellowHueMin] to [yellowHueMax]) have their grounds' hue rotated by [yellowHueShift]: dark yellow reads as olive. */
    val yellowHueMin: Double = 80.0,
    val yellowHueMax: Double = 115.0,
    val yellowHueShift: Double = -12.0,
    /** The button gradient's two stops sit at the seed's hue minus and plus this many degrees... */
    val buttonHueShift: Double = 14.0,
    /** ...reversed for hues from [buttonReverseHueMin] to [buttonReverseHueMax] (yellows and yellow-greens). */
    val buttonReverseHueMin: Double = 70.0,
    val buttonReverseHueMax: Double = 130.0,

    // --- gamut mapping (CSS Color 4) -----------------------------------------------------------

    /** The chroma search stops at the largest chroma whose clipped colour is within this deltaE_OK of the unclipped one. */
    val gamutJnd: Double = 0.02,
    val gamutEpsilon: Double = 0.0001,

    // --- the safety rails ----------------------------------------------------------------------

    /** Dark theme: no background stop or ambient light may have an OKLCH L above this... */
    val darkGroundMaxL: Double = 0.30,
    /** ...or a relative luminance above this (about L* 20); a stop that does is lowered by [groundStep] until it passes. */
    val darkGroundMaxLuminance: Double = 0.030,
    val groundStep: Double = 0.005,
    /** The lowering stops here. */
    val darkGroundMinL: Double = 0.10,
    /** Light theme: no background stop or ambient light may have an OKLCH L below this (raised by [groundStep]). */
    val lightGroundMinL: Double = 0.93,
    /** Text needs this WCAG contrast against every ground it can sit on. */
    val textContrast: Double = 4.5,
    /** Icons, indicators and other interface parts need this. */
    val uiContrast: Double = 3.0,
    /**
     * The contrast loop: a foreground that fails is moved [contrastLStep] in L away from the grounds (up on dark, down on
     * light) while L is inside 0.01 to 0.99, and loses [contrastChromaShrink] of its chroma otherwise, for at most
     * [contrastMaxSteps] steps; if it still fails it becomes white or ink of L [contrastInkL], whichever has the higher minimum.
     */
    val contrastLStep: Double = 0.01,
    /** The loop moves L only while it is inside this range; at either end it cuts chroma instead. */
    val contrastLMin: Double = 0.01,
    val contrastLMax: Double = 0.99,
    val contrastChromaShrink: Double = 0.8,
    val contrastMaxSteps: Int = 60,
    val contrastInkL: Double = 0.16,
    /** The loop treats the grounds as dark when their mean relative luminance is below this. */
    val darkGroundsMeanLuminance: Double = 0.18,
    /** The ink drawn on the buttons: `(l, min(factor * c, max))`; it competes with white for the higher worst contrast. */
    val onAccentInk: ChromaRule = ChromaRule(0.18, 0.25, 0.03),
    /** If the on-accent colour fails on a button stop, both stops move this much in L away from it, at most [onAccentMaxShifts] times, within L 0.2 to 0.9. */
    val onAccentShiftStep: Double = 0.01,
    val onAccentMaxShifts: Int = 40,
    val buttonMinL: Double = 0.2,
    val buttonMaxL: Double = 0.9,

    // --- dark theme ----------------------------------------------------------------------------

    /** The radial light at the top of the screen near the cover (k applies). */
    val darkAmbient: ChromaRule = ChromaRule(0.30, 0.9, 0.09),
    /** The three background stops, top to bottom (k applies; the bottom stop takes the cover's second colour's hue). */
    val darkBackgroundTop: ChromaRule = ChromaRule(0.28, 0.55, 0.055),
    val darkBackgroundMid: ChromaRule = ChromaRule(0.225, 0.42, 0.040),
    val darkBackgroundBottom: ChromaRule = ChromaRule(0.185, 0.30, 0.028),
    /** Cards and tiles, sheets and menus, chips and quiet buttons (the brightest ground). */
    val darkSurface: ChromaRule = ChromaRule(0.26, 0.35, 0.035),
    val darkSurfaceHigh: ChromaRule = ChromaRule(0.30, 0.32, 0.032),
    val darkSurfaceVariant: ChromaRule = ChromaRule(0.34, 0.30, 0.032),
    val darkTextPrimary: ChromaRule = ChromaRule(0.965, 0.08, 0.010),
    val darkTextSecondary: ChromaRule = ChromaRule(0.80, 0.14, 0.022),
    val darkTextTertiary: ChromaRule = ChromaRule(0.71, 0.12, 0.020),
    /** The accent's L is the seed's L held between these (less [brightAccentLShift] for a bright cover). */
    val darkAccentMinL: Double = 0.78,
    val darkAccentMaxL: Double = 0.84,
    /** The accent used as text: L at least this, chroma at most [darkAccentStrongMaxChroma]. */
    val darkAccentStrongMinL: Double = 0.84,
    val darkAccentStrongMaxChroma: Double = 0.11,
    val darkButtonStart: AccentChromaRule = AccentChromaRule(0.85, 0.85, 0.07, 0.13),
    val darkButtonEnd: AccentChromaRule = AccentChromaRule(0.74, 1.15, 0.09, 0.16),
    val darkAccentContainer: AccentChromaRule = AccentChromaRule(0.38, 0.55, 0.04, 0.09),
    /** The text on the accent container: `(l, min(factor * A, max))`. */
    val darkOnAccentContainer: ChromaRule = ChromaRule(0.95, 0.35, 0.04),
    /** The glow under the Now Playing controls: L is the seed's held between these, chroma the seed's held between the chroma bounds. */
    val darkGlowMinL: Double = 0.42,
    val darkGlowMaxL: Double = 0.58,
    val darkGlowChromaMin: Double = 0.06,
    val darkGlowChromaMax: Double = 0.14,
    val darkShadow: ChromaRule = ChromaRule(0.20, 0.6, 0.08),
    val darkShadowAlpha: Double = 0.55,
    /** The colour of the scrim over video (L at most 0.13, so luminance at most 0.0022). */
    val darkScrim: ChromaRule = ChromaRule(0.12, 0.25, 0.025),
    /** The progress track and the outlines are neutral at this L and alpha. */
    val darkTrackL: Double = 0.965,
    val darkTrackAlpha: Double = 0.22,
    val darkOutlineAlpha: Double = 0.14,

    // --- light theme ---------------------------------------------------------------------------

    val lightAmbient: ChromaRule = ChromaRule(0.93, 0.5, 0.05),
    val lightBackgroundTop: ChromaRule = ChromaRule(0.965, 0.25, 0.022),
    val lightBackgroundMid: ChromaRule = ChromaRule(0.945, 0.30, 0.028),
    val lightBackgroundBottom: ChromaRule = ChromaRule(0.985, 0.12, 0.010),
    val lightSurface: ChromaRule = ChromaRule(0.99, 0.06, 0.006),
    val lightSurfaceHigh: ChromaRule = ChromaRule(0.975, 0.10, 0.010),
    val lightSurfaceVariant: ChromaRule = ChromaRule(0.915, 0.25, 0.030),
    val lightTextPrimary: ChromaRule = ChromaRule(0.20, 0.12, 0.020),
    val lightTextSecondary: ChromaRule = ChromaRule(0.42, 0.15, 0.030),
    val lightTextTertiary: ChromaRule = ChromaRule(0.50, 0.12, 0.025),
    /** The light accent chroma `A'` is the seed's chroma times [intensity] held between these (grey cover: [greyLightAccentChroma]). */
    val lightAccentChromaMin: Double = 0.09,
    val lightAccentChromaMax: Double = 0.16,
    val lightAccentMinL: Double = 0.46,
    val lightAccentMaxL: Double = 0.54,
    /** The accent used as text: L at most this, chroma at most [lightAccentStrongMaxChroma]. */
    val lightAccentStrongMaxL: Double = 0.50,
    val lightAccentStrongMaxChroma: Double = 0.14,
    val lightButtonStart: AccentChromaRule = AccentChromaRule(0.56, 0.9, 0.08, 0.14),
    val lightButtonEnd: AccentChromaRule = AccentChromaRule(0.46, 1.1, 0.09, 0.16),
    val lightAccentContainer: AccentChromaRule = AccentChromaRule(0.90, 0.45, 0.04, 0.08),
    val lightOnAccentContainer: ChromaRule = ChromaRule(0.28, 0.5, 0.07),
    val lightGlowL: Double = 0.80,
    val lightGlowChromaMin: Double = 0.05,
    val lightGlowChromaMax: Double = 0.10,
    val lightShadow: ChromaRule = ChromaRule(0.35, 0.5, 0.06),
    val lightShadowAlpha: Double = 0.22,
    val lightScrim: ChromaRule = ChromaRule(0.12, 0.25, 0.025),
    val lightTrackL: Double = 0.20,
    val lightTrackAlpha: Double = 0.16,
    val lightOutlineAlpha: Double = 0.12,

    // --- the grey cover (fewer than [minColourShare] colour pixels) ----------------------------

    /** The glow's chroma for a grey cover. */
    val greyGlowChroma: Double = 0.03,
    /** The light accent of a grey cover (dark: the fallback seed itself, the brand apricot). */
    val greyLightAccentL: Double = 0.52,
    val greyLightAccentChroma: Double = 0.10,

    // --- re-theming and the fade ---------------------------------------------------------------

    /** Keep the palette while the visible colours (accent and top of the background) differ by less than this deltaE_OK: tracks of the same album... */
    val settleSameAlbumDeltaE: Double = 0.10,
    /** ...and a different album. */
    val settleAcrossAlbumDeltaE: Double = 0.03,
    /** The fade between two palettes: milliseconds, and with reduced motion. */
    val fadeMillis: Int = 700,
    val fadeMillisReducedMotion: Int = 150,
    /** The fade's easing, the CSS cubic-bezier(0.2, 0, 0, 1): control points (x1, y1) and (x2, y2). */
    val easeX1: Double = 0.2,
    val easeY1: Double = 0.0,
    val easeX2: Double = 0.0,
    val easeY2: Double = 1.0,
)
