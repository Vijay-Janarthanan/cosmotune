package com.cosmotune.theme

/**
 * The layered surface scheme of the revamped look: a near-neutral ground with real tonal elevation steps, and the cover's
 * colour kept for the accent family (the accent, the buttons, the accent container, the glow and the ambient light near the
 * cover) instead of painting every ground.
 *
 * The designer's spec ([CoverPaletteSpec] with its defaults) tints the grounds and the three surface tiers with up to 0.055 of
 * the cover's chroma and keeps their lightness within 0.08 of each other, so a red cover turns every surface into one maroon
 * and a card is told from the page by its shadow alone (see the design note on [CoverPaletteSpec]). This keeps the engine and
 * every rail as they are and only moves the numbers of the grounds:
 *
 *  - **Dark.** The page is near black (L 0.145 to 0.16, about `#0B0B0D`) with only a hint of the cover's hue (chroma at most
 *    0.005 to 0.008); the tiers climb in clear steps above it: `surface` 0.205 (cards), `surfaceHigh` 0.24 (sheets, menus, the
 *    dock under glass), `surfaceVariant` 0.285 (chips, quiet buttons). The ambient light by the cover keeps more of the cover
 *    (0.045): the player and the detail headers are where the artwork's colour shows.
 *  - **Light.** An off-white page (L 0.965 to 0.975), white cards (0.995), a slightly deeper chip tier (0.925).
 *  - **AMOLED** ([amoled]) puts the dark page on pure black and keeps the tiers as lifted neutral greys.
 *
 * The contrast loops still run after this (text 4.5:1 on every ground and tier, the accent 3:1 on the surfaces), so every
 * guarantee of the engine holds; [CoverPaletteSpec.intensity] still scales the ground chroma ceilings, so Calm, Balanced and Rich
 * still differ. The defaults of [CoverPaletteSpec] are untouched: the golden vectors of the designer's spec stay byte for byte.
 */
fun CoverPaletteSpec.layered(): CoverPaletteSpec {
    val k = intensity
    return copy(
        darkAmbient = ChromaRule(0.24, 0.8, 0.045 * k),
        darkBackgroundTop = ChromaRule(0.16, 0.10, 0.008 * k),
        darkBackgroundMid = ChromaRule(0.15, 0.08, 0.006 * k),
        darkBackgroundBottom = ChromaRule(0.145, 0.06, 0.005 * k),
        darkSurface = ChromaRule(0.205, 0.10, 0.008 * k),
        darkSurfaceHigh = ChromaRule(0.24, 0.10, 0.009 * k),
        darkSurfaceVariant = ChromaRule(0.285, 0.10, 0.010 * k),
        darkGlass = ChromaRule(0.21, 0.10, 0.008 * k),
        darkSkeletonBase = ChromaRule(0.21, 0.08, 0.007 * k),
        darkSkeletonShine = ChromaRule(0.28, 0.12, 0.012 * k),
        lightAmbient = ChromaRule(0.94, 0.35, 0.030 * k),
        lightBackgroundTop = ChromaRule(0.97, 0.06, 0.006 * k),
        lightBackgroundMid = ChromaRule(0.965, 0.06, 0.006 * k),
        lightBackgroundBottom = ChromaRule(0.975, 0.06, 0.006 * k),
        lightSurface = ChromaRule(0.995, 0.03, 0.003 * k),
        lightSurfaceHigh = ChromaRule(0.985, 0.05, 0.005 * k),
        lightSurfaceVariant = ChromaRule(0.925, 0.12, 0.014 * k),
        lightGlass = ChromaRule(0.985, 0.10, 0.010 * k),
        lightSkeletonBase = ChromaRule(0.93, 0.10, 0.010 * k),
        lightSkeletonShine = ChromaRule(0.975, 0.20, 0.020 * k),
    )
}

/**
 * The AMOLED variant of the dark scheme: the page is pure black (no light is emitted for it on an OLED panel) and the tiers are
 * neutral greys lifted off it, so cards still read as cards. Applied over [layered]; the light theme is untouched.
 */
fun CoverPaletteSpec.amoled(): CoverPaletteSpec = copy(
    darkAmbient = ChromaRule(0.16, 0.6, 0.035 * intensity),
    darkBackgroundTop = ChromaRule(0.0, 0.0, 0.0),
    darkBackgroundMid = ChromaRule(0.0, 0.0, 0.0),
    darkBackgroundBottom = ChromaRule(0.0, 0.0, 0.0),
    darkSurface = ChromaRule(0.18, 0.10, 0.010),
    darkSurfaceHigh = ChromaRule(0.22, 0.10, 0.012),
    darkSurfaceVariant = ChromaRule(0.27, 0.10, 0.014),
    darkGlass = ChromaRule(0.16, 0.10, 0.010),
    darkSkeletonBase = ChromaRule(0.17, 0.08, 0.008),
    darkSkeletonShine = ChromaRule(0.25, 0.15, 0.015),
    // The scrim over video has to stay darker than the page it dims less of; black is as dark as it gets.
    darkScrim = ChromaRule(0.0, 0.0, 0.0),
)
