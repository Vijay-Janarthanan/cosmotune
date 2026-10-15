package com.cosmotune.design

import com.cosmotune.theme.CoverColors

/** One row of the contrast list: the worst ratio of a group of colour pairs, and the rail it has to reach. */
class ContrastLine(val label: String, val ratio: Double, val rail: Double) {
    val passes: Boolean get() = ratio >= rail
}

/**
 * The checks the palette engine promises (`CoverDerive`: text 4.5, icons and indicators 3.0), measured again with the app's
 * own [Contrast.ratio] on the colours the screens will paint. The look preview lists them under each sample cover.
 */
object ContrastReport {
    const val TEXT_RAIL = 4.5
    const val UI_RAIL = 3.0

    fun lines(c: CoverColors): List<ContrastLine> {
        val grounds = listOf(c.backgroundTop, c.backgroundMid, c.backgroundBottom)
        val groundsAndSurface = grounds + c.surface
        fun worst(ink: Int, on: List<Int>) = on.minOf { Contrast.ratio(ink, it) }
        return listOf(
            ContrastLine("Primary text on grounds and surface", worst(c.textPrimary, groundsAndSurface), TEXT_RAIL),
            ContrastLine("Secondary text on grounds and surface", worst(c.textSecondary, groundsAndSurface), TEXT_RAIL),
            ContrastLine("Strong accent text on grounds", worst(c.accentStrong, grounds), TEXT_RAIL),
            ContrastLine("Accent icons on bottom ground and surfaces", worst(c.accent, listOf(c.backgroundBottom, c.surface, c.surfaceHigh, c.surfaceVariant)), UI_RAIL),
            ContrastLine("On-accent on both button stops", worst(c.onAccent, listOf(c.buttonGradientStart, c.buttonGradientEnd)), TEXT_RAIL),
            ContrastLine("On-container on the accent container", worst(c.onAccentContainer, listOf(c.accentContainer)), TEXT_RAIL),
        )
    }
}
