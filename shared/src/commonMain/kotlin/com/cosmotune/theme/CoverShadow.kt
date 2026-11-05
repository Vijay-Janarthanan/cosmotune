package com.cosmotune.theme

private const val SHADOW_L = 0.32
private const val SHADOW_MIN_C = 0.04
private const val SHADOW_MAX_C = 0.12
private const val SHADOW_ALPHA = 0.65 // 0xA6 of 255
private const val GREY_L = 0.22
private const val GREY_C = 0.01
private const val GREY_HUE = 52.0

/**
 * The shadow under a cover tile, in the cover's own colour (01-dynamic-colour.md section 2): OKLCH (L 0.32, C = the seed's
 * chroma held between 0.04 and 0.12, the seed's hue) at 65 percent; a grey cover (the palette is the fallback one) gets
 * the neutral (0.22, 0.01, 52 degrees). ARGB, alpha 0xA6.
 *
 * The colour is built like every other token of the palette, with the gamut mapping of [ColourMath] (lightness and hue
 * kept, chroma lowered until it is inside sRGB), not by clipping: at L 0.32 the chroma of a vivid red or orange is outside
 * sRGB, and clipping would turn its hue by several degrees.
 */
fun coverShadowArgb(seed: Int, isFallback: Boolean): Int {
    val spec = CoverPaletteSpec()
    val math = ColourMath(spec.gamutJnd, spec.gamutEpsilon, spec.gamutMargin)
    if (isFallback) return math.mk(GREY_L, GREY_C, GREY_HUE, SHADOW_ALPHA).argb
    val lch = Oklab.lchFromArgb(seed or (0xFF shl 24))
    // A seed with no chroma at all has no hue: it takes the grey neutral's.
    val hue = if (lch.c < 1e-6) GREY_HUE else lch.h
    return math.mk(SHADOW_L, lch.c.coerceIn(SHADOW_MIN_C, SHADOW_MAX_C), hue, SHADOW_ALPHA).argb
}
