package com.cosmotune.design

import com.cosmotune.theme.CoverColors

/**
 * What lifts a B tile off the page (`screens-v3.css` `.tile2`, `.mix2`, `.mtile`, `.pin2`: `box-shadow: var(--hl), var(--e1)`, with the
 * numbers of `tokens-v3bc.css`). The palette alone cannot: a tile's fill sits within 1.05 to 1.25:1 of the ground under it (the design
 * note of `CoverPaletteSpec`), so without these a card is a flat patch, in the light theme most of all.
 *
 * - `--e1` (`0 1px 2px rgb(0 0 0 / .28), 0 6px 18px -4px var(--c-shadow)`): a lift of [ELEVATION_DP] whose key shadow is the palette's
 *   `shadow` colour at full strength (the platform scales a key shadow down to about a fifth, as for the cover), over a black ambient
 *   one at [AMBIENT_ALPHA];
 * - `--hl` (`inset 0 1px 0 rgb(255 255 255 / .10), inset 0 0 0 .5px rgb(255 255 255 / .07)`; light theme `.8` and `rgb(0 0 0 / .06)`): a
 *   1 dp top-lit rim in white at [rimAlpha], fading down the sides, and a [HAIRLINE_DP] hairline in the ink (`textPrimary`, the colour
 *   the screens draw the palette's `outline` with) at [hairlineAlpha]: the `outline` token halved, (L 0.965, 14 %) / 2 = the board's
 *   white 7 % in the dark theme and (L 0.20, 12 %) / 2 = its black 6 % in the light one.
 */
object TileDepth {
    /** The lift of a tile or card. */
    const val ELEVATION_DP = 6f

    /** The black of the ambient shadow (`0 1px 2px rgb(0 0 0 / .28)`). */
    const val AMBIENT_ALPHA = 0.28f

    /** The rim along the top, inside the outline. */
    const val RIM_DP = 1f

    /** The white of the rim: 10 % on the dark theme, 80 % on the light one (`--hl`). */
    fun rimAlpha(dark: Boolean): Float = if (dark) 0.10f else 0.80f

    /** The hairline round the inside of the outline. */
    const val HAIRLINE_DP = 0.5f

    /** The ink of the hairline: 7 % on the dark theme, 6 % on the light one (the palette's `outline` alpha halved). */
    fun hairlineAlpha(dark: Boolean): Float = if (dark) 0.07f else 0.06f

    /**
     * The text of every tile against the fill it is drawn on, measured with [Contrast.ratio], each against [ContrastReport.TEXT_RAIL]:
     * the primary and secondary text on the tile fill (`mix(surfaceHigh, surface, 0.4)` to `surface`) and on the ground of a mix and a
     * mood tile (`backgroundTop` to `backgroundBottom`, washed with `ambient`); `onAccent` on the liked tile's gradient
     * (`buttonGradientStart` to `buttonGradientEnd`); `onAccentContainer` on the plus of the "New playlist" cell (`accentContainer`).
     */
    fun textLines(c: CoverColors): List<ContrastLine> {
        val tileFill = listOf(ColorMix.mix(c.surfaceHigh, c.surface, 0.4f), c.surface)
        val ground = listOf(c.backgroundTop, c.backgroundBottom, c.ambient)
        fun worst(ink: Int, on: List<Int>) = on.minOf { Contrast.ratio(ink, it) }
        return listOf(
            ContrastLine("Primary text on the tile fill", worst(c.textPrimary, tileFill), ContrastReport.TEXT_RAIL),
            ContrastLine("Secondary text on the tile fill", worst(c.textSecondary, tileFill), ContrastReport.TEXT_RAIL),
            ContrastLine("Primary text on a mix or mood tile", worst(c.textPrimary, ground), ContrastReport.TEXT_RAIL),
            ContrastLine("Secondary text on a mix tile", worst(c.textSecondary, ground), ContrastReport.TEXT_RAIL),
            ContrastLine("On-accent on the liked tile", worst(c.onAccent, listOf(c.buttonGradientStart, c.buttonGradientEnd)), ContrastReport.TEXT_RAIL),
            ContrastLine("On-container on the new playlist plus", worst(c.onAccentContainer, listOf(c.accentContainer)), ContrastReport.TEXT_RAIL),
        )
    }
}
