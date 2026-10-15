package com.cosmotune.theme

/**
 * Every colour a screen needs for one song, derived from its cover art by [CoverPalette]. All values are opaque
 * ARGB integers (`0xFFRRGGBB`), the form both Android's `Color(Int)` and Compose's `Color(Long)` take.
 *
 * Nothing here is chosen by hand: the backgrounds, buttons, text and glow all come from the cover, and the
 * rails in [CoverPaletteSpec] keep them calm (never too bright) and readable (contrast).
 *
 * @property seed the one colour picked from the cover; the others are derived from its hue and chroma.
 * @property backgroundTop the three stops of the screen's vertical gradient, top to bottom.
 * @property surface cards and sheets laid over the background.
 * @property surfaceVariant a second, slightly lighter surface (chips, inactive tracks).
 * @property accent the highlight colour: the progress bar, the active icon, links.
 * @property accentStrong a deeper accent for pressed states and emphasis.
 * @property onAccent text and icons drawn on [accent] and on the button gradient.
 * @property buttonGradientStart the two ends of the main button's gradient (start is hue-shifted one way, end the other).
 * @property textPrimary titles; readable on every background stop and on [surface].
 * @property textSecondary captions; readable on [surface] and on [backgroundMid].
 * @property glow the soft light behind the artwork and the active button.
 * @property isFallback true when the cover gave no usable colour (white, black, grey, empty, transparent) and the
 *   palette comes from the fallback seed instead.
 */
data class CoverColors(
    val seed: Int,
    val backgroundTop: Int,
    val backgroundMid: Int,
    val backgroundBottom: Int,
    val surface: Int,
    val surfaceVariant: Int,
    val accent: Int,
    val accentStrong: Int,
    val onAccent: Int,
    val buttonGradientStart: Int,
    val buttonGradientEnd: Int,
    val textPrimary: Int,
    val textSecondary: Int,
    val glow: Int,
    val isFallback: Boolean,
)
