package com.cosmotune.ui

import com.cosmotune.theme.CoverColors
import com.cosmotune.theme.CoverField
import com.cosmotune.theme.CoverPalette
import com.cosmotune.theme.CoverPaletteSpec
import com.cosmotune.theme.CoverWash
import com.cosmotune.theme.layered
import com.cosmotune.ui.orbit.coverCardTint
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.coverWash
import androidx.compose.ui.graphics.toArgb
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The page's wash and the song card come from one colour (the owner's report: "Vizhigale", a mostly white cover with two dark figures
 * and vivid red blood at the foot, had a deep red card on a muddy brown page). For synthetic covers of the kinds that matter, through
 * the real engine (the layered palette the app loads), this checks that the wash keeps the card's OKLCH hue, keeps its chroma where
 * the card has some, stays neutral for a grey cover, and lets every text colour of the page read at 4.5:1, in both themes.
 */
class CoverWashTest {

    /** A cover drawn on a 64 x 64 grid. */
    class Cover(val name: String, val pixels: IntArray) {
        fun palette(dark: Boolean): CoverColors = CoverPalette.fromPixels(pixels, SIDE, SIDE, dark = dark, spec = CoverPaletteSpec().layered())
    }

    companion object {
        const val SIDE = 64
        private fun rgb(r: Int, g: Int, b: Int) = (0xFF shl 24) or (r.coerceIn(0, 255) shl 16) or (g.coerceIn(0, 255) shl 8) or b.coerceIn(0, 255)

        private fun cover(name: String, paint: (x: Int, y: Int, noise: Random) -> Int): Cover {
            val noise = Random(name.hashCode())
            return Cover(name, IntArray(SIDE * SIDE) { paint(it % SIDE, it / SIDE, noise) })
        }

        /** Mostly white, two dark figures with skin-toned faces, and a band of vivid red blood at the foot (the "DC" cover). */
        val whiteWithRed = cover("white with a red patch") { x, y, n ->
            val jitter = n.nextInt(-6, 7)
            when {
                y >= 53 && x in 6..57 -> rgb(178 + jitter, 14, 22)                      // the blood
                (x in 15..23 || x in 39..47) && y in 12..16 -> rgb(214 + jitter, 166, 138) // the faces
                (x in 13..25 || x in 37..49) && y in 17..46 -> rgb(28 + jitter, 26, 30)   // the figures
                else -> rgb(244 + jitter / 2, 242 + jitter / 2, 238)                      // the white
            }
        }

        /** A warm brown photograph: sepia browns with grain and a lighter middle. */
        val warmBrown = cover("warm brown photo") { x, y, n ->
            val d = abs(x - 32) + abs(y - 30)
            val lift = (60 - d).coerceAtLeast(0)
            val g = n.nextInt(-10, 11)
            rgb(112 + lift + g, 76 + lift * 3 / 4 + g, 48 + lift / 2 + g)
        }

        /** Blue neon: a near-black night with bright blue neon tubes. */
        val blueNeon = cover("blue neon") { x, y, n ->
            val tube = (y in 18..21 || y in 40..43) && x in 8..55 || (x in 30..33 && y in 18..43)
            val glow = (y in 16..23 || y in 38..45) && x in 6..57
            when {
                tube -> rgb(40 + n.nextInt(0, 20), 150 + n.nextInt(0, 30), 255)
                glow -> rgb(14, 40, 120 + n.nextInt(0, 20))
                else -> rgb(8 + n.nextInt(0, 6), 8 + n.nextInt(0, 6), 18 + n.nextInt(0, 8))
            }
        }

        /** A black and white photograph: greys only, with grain. */
        val greyPhoto = cover("grey black and white") { x, y, n ->
            val v = (40 + (x + y) * 2 + n.nextInt(-12, 13)).coerceIn(0, 255)
            rgb(v, v, v)
        }

        val all = listOf(whiteWithRed, warmBrown, blueNeon, greyPhoto)

        fun colors(palette: CoverColors, dark: Boolean): CosmoColors = CosmoColors(CosmoPaletteState(palette, dark))

        fun hueDistance(a: Double, b: Double) = abs(((b - a + 540.0) % 360.0) - 180.0)
    }

    /** The card's fill (what `coverFill` paints, always from the dark palette) and the wash of [dark]'s palette. */
    private fun cardAndWash(cover: Cover, dark: Boolean): Triple<Int, Int, CoverColors> {
        val card = coverCardTint(cover.palette(dark = true)).fill.toArgb()
        val palette = cover.palette(dark)
        val wash = coverWash(colors(palette, dark), if (palette.isFallback) 0f else 1f).toArgb()
        return Triple(card, wash, palette)
    }

    private fun describe(argb: Int) = CoverWash.oklch(argb).let { "#%06X (L %.3f C %.3f h %.0f)".format(argb and 0xFFFFFF, it[0], it[1], it[2]) }

    @Test fun theWashKeepsTheCardsHue() {
        for (cover in all) for (dark in listOf(true, false)) {
            val (card, wash) = cardAndWash(cover, dark)
            val c = CoverWash.oklch(card)
            val w = CoverWash.oklch(wash)
            println("${cover.name}, ${if (dark) "dark" else "light"}: card ${describe(card)}, wash ${describe(wash)}")
            if (c[1] < 0.03 || w[1] < 0.02) continue // no hue to keep: a charcoal card
            assertTrue(hueDistance(c[2], w[2]) <= 15.0, "${cover.name}, ${if (dark) "dark" else "light"}: card ${describe(card)}, wash ${describe(wash)}")
        }
    }

    @Test fun theWashKeepsTheCardsChroma() {
        for (cover in listOf(whiteWithRed, warmBrown, blueNeon)) {
            val (card, darkWash) = cardAndWash(cover, dark = true)
            val c = CoverWash.oklch(card)[1]
            assertTrue(c >= 0.05, "${cover.name}: the card has colour (${describe(card)})")
            // Dark: the wash sits just under the card's lightness, so it can carry nearly all of the card's chroma.
            assertTrue(CoverWash.oklch(darkWash)[1] >= 0.75 * c, "${cover.name}, dark: the wash ${describe(darkWash)} kept the chroma of the card ${describe(card)}")
            // Light: a pale tint, whose chroma is what sRGB has at that lightness, but a tint, not a grey.
            val (_, lightWash) = cardAndWash(cover, dark = false)
            assertTrue(CoverWash.oklch(lightWash)[1] >= minOf(0.03, 0.75 * c), "${cover.name}, light: the wash ${describe(lightWash)} is a tint of ${describe(card)}")
        }
    }

    @Test fun theRedOfTheWhiteCoverIsNotABrown() {
        // The reported cover: the card is a deep red, and the page is that red, not the brown of the palette's accent container.
        val (card, wash, palette) = cardAndWash(whiteWithRed, dark = true)
        val old = CoverWash.oklch(palette.accentContainer)
        val now = CoverWash.oklch(wash)
        val red = CoverWash.oklch(card)
        assertTrue(red[2] < 40.0 || red[2] > 350.0, "the card is red: ${describe(card)}")
        assertTrue(now[1] > old[1] + 0.02, "the wash ${describe(wash)} is more chromatic than the accent container ${describe(palette.accentContainer)} it replaces")
    }

    @Test fun aGreyCoverHasANeutralWash() {
        for (dark in listOf(true, false)) {
            val (card, wash, palette) = cardAndWash(greyPhoto, dark)
            assertTrue(palette.isFallback, "a grey cover has no colour of its own")
            assertTrue(CoverWash.oklch(card)[1] < 0.01, "its card is a charcoal, not the brand apricot made brown: ${describe(card)}")
            assertTrue(CoverWash.oklch(wash)[1] < 0.02, "${if (dark) "dark" else "light"}: its wash is neutral: ${describe(wash)}")
        }
    }

    @Test fun everyTextColourReadsOnTheWash() {
        for (cover in all) for (dark in listOf(true, false)) {
            val (_, wash, palette) = cardAndWash(cover, dark)
            for ((what, ink) in listOf("primary" to palette.textPrimary, "secondary" to palette.textSecondary, "tertiary" to palette.textTertiary)) {
                val ratio = CoverWash.contrast(wash, ink)
                assertTrue(ratio >= 4.5 - 1e-6, "${cover.name}, ${if (dark) "dark" else "light"}: $what text on ${describe(wash)} is ${"%.2f".format(ratio)}:1")
            }
        }
    }

    @Test fun theWashFadesWithThePalette() {
        // Between no cover (0) and a cover (1) the wash blends; it never leaves the range between the neutral and the colour.
        val palette = whiteWithRed.palette(dark = true)
        val colors = colors(palette, dark = true)
        val neutral = CoverWash.oklch(coverWash(colors, 0f).toArgb())
        val half = CoverWash.oklch(coverWash(colors, 0.5f).toArgb())
        val full = CoverWash.oklch(coverWash(colors, 1f).toArgb())
        assertTrue(half[1] > neutral[1] && half[1] < full[1], "half way the chroma is between: ${neutral[1]} < ${half[1]} < ${full[1]}")
        assertTrue(CoverWash.contrast(coverWash(colors, 0.5f).toArgb(), palette.textPrimary) >= 4.5)
    }

    @Test fun theCardAndEveryGroundShareOneSource() {
        val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"
        val tint = SourceContract.functionBody(SourceContract.read("$ui/orbit/OrbitKit.kt"), "rememberCoverTint")
        assertTrue("?.let(::coverCardTint)" in tint, "the card is filled from coverCardTint")
        val ground = SourceContract.code(SourceContract.read("$ui/theme/AmbientBackground.kt"))
        val brushes = SourceContract.functionBody(SourceContract.read("$ui/theme/AmbientBackground.kt"), "groundBrushes")
        assertTrue("val tint = coverWash(colors, wash)" in brushes, "the player's ground, the page's wash and the top bar's band are the one wash")
        assertTrue("CoverField.ACCENT_CONTAINER" !in ground && "CoverField.ACCENT)" !in ground, "no ground is taken from the accent family any more")
        assertTrue("CoverWash.ground(vivid(seed).fill.toArgb()" in ground, "the wash is made from the card's fill")
    }

    @Test fun theLiveSeedIsTheCardsSeed() {
        // The wash reads the palette's live seed; the card reads the dark palette's seed. The seed does not depend on the theme.
        for (cover in all) assertTrue(cover.palette(dark = true).seed == cover.palette(dark = false).seed, cover.name)
        val colors = colors(whiteWithRed.palette(dark = false), dark = false)
        assertTrue(colors.live(CoverField.SEED).toArgb() == whiteWithRed.palette(dark = true).seed)
    }
}
