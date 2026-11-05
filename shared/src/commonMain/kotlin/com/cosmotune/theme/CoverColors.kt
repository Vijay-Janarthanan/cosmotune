package com.cosmotune.theme

/**
 * Every colour a screen needs for one song, derived from its cover art by [CoverPalette] with the designer's formulas
 * (`ui/v2/01-dynamic-colour.md`). All values are ARGB integers (`0xAARRGGBB`), the form both Android's `Color(Int)`
 * and Compose's `Color(Long)` take. Everything is opaque except [shadow], [track], [outline] and [glass], which carry
 * their alpha in the top byte (the design draws them translucent).
 *
 * Nothing here is chosen by hand: the backgrounds, buttons, text and glow all come from the cover, and the rails in
 * [CoverPaletteSpec] keep them calm (never too bright in the dark theme) and readable (contrast). The one exception by
 * design is the danger family ([danger], [onDanger], [dangerContainer], [onDangerContainer]): a fixed red that does not
 * follow the cover.
 *
 * @property seed the one colour picked from the cover; the others are derived from its hue and chroma.
 * @property ambient the radial light at the top of the screen near the cover: saturated but still dark (dark theme).
 * @property backgroundTop the three stops of the screen's vertical gradient, top to bottom. The bottom stop takes
 *   the hue of the cover's second colour when it has one.
 * @property surface cards and tiles laid over the background.
 * @property surfaceHigh sheets, menus, the tint under glass.
 * @property surfaceVariant chips and quiet buttons: the brightest ground in the dark theme.
 * @property accent the highlight colour: the progress bar, the active icon. At least 3:1 on the surfaces.
 * @property accentStrong a stronger accent for accent-coloured text: at least 4.5:1 on every ground.
 * @property onAccent text and icons drawn on the button gradient (ink or white, whichever reads better on both ends).
 * @property buttonGradientStart the two ends of the main button's gradient (hue turned one way, then the other;
 *   drawn at 135 degrees in OKLab).
 * @property accentContainer the fill of tonal buttons, selected chips and segments, the playing row.
 * @property onAccentContainer text on [accentContainer], at least 4.5:1.
 * @property textPrimary titles; at least 4.5:1 on every background stop, the ambient light and every surface tier.
 * @property textSecondary captions; at least 4.5:1 on the same grounds.
 * @property textTertiary hints and disabled-looking text; at least 4.5:1 on the same grounds.
 * @property glow the soft light behind the artwork and the active button.
 * @property shadow the tinted half of every elevation (translucent).
 * @property scrim the colour of the scrim over video (very dark, tinted).
 * @property track the progress track (neutral, translucent).
 * @property outline chip and field edges (neutral, translucent).
 * @property isFallback true when the cover gave no usable colour (white, black, grey, empty, transparent) and the
 *   palette comes from the fallback seed, the brand apricot, instead.
 * @property glass the tint under blur on floating surfaces (the tab bar, sheets, menus, the desktop player): the
 *   ground hue at the lightness of [surfaceHigh] in the dark theme (0.30) and a near-white in the light one (0.975),
 *   translucent: alpha 0.52 dark, 0.62 light (v3 delta, `ui/v3/02-colour-delta.md`).
 * @property skelBase the fill of a skeleton (a placeholder while content loads).
 * @property skelShine the shimmer highlight that sweeps across a skeleton, a little lighter than [skelBase] and at the
 *   seed's hue.
 * @property danger the red of destructive icons and outlines, independent of the cover: at least 3:1 on the bottom
 *   background stop, [surface] and [surfaceVariant].
 * @property onDanger text and icons on [danger]: ink or white, whichever reads better; at least 4.5:1.
 * @property dangerContainer the fill of a destructive button in the page (family B).
 * @property onDangerContainer text on [dangerContainer], at least 4.5:1.
 * @property dangerClash true when the cover's own hue is within 30 degrees of the danger red, so a destructive button
 *   cannot be told from the cover's colour by hue: it renders outlined instead (no fill, a 2 dp [danger] border, a
 *   [danger] label). Never true for a grey cover.
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
    // Added with the designer's formulas: the tokens the v2 screens need beyond the first fourteen.
    val ambient: Int,
    val surfaceHigh: Int,
    val textTertiary: Int,
    val accentContainer: Int,
    val onAccentContainer: Int,
    val shadow: Int,
    val scrim: Int,
    val track: Int,
    val outline: Int,
    // Added with the v3 delta (ui/v3/02-colour-delta.md): glass, the skeleton pair and the danger family.
    val glass: Int,
    val skelBase: Int,
    val skelShine: Int,
    val danger: Int,
    val onDanger: Int,
    val dangerContainer: Int,
    val onDangerContainer: Int,
    val dangerClash: Boolean,
)
