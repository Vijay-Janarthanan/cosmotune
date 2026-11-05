package com.cosmotune.ui

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import com.cosmotune.design.ColorMix
import com.cosmotune.design.GlassPolicy
import com.cosmotune.theme.CoverPalette
import com.cosmotune.ui.components.MorphPath
import com.cosmotune.ui.components.floatingPlayFill
import com.cosmotune.ui.components.floatingPrimaryFill
import com.cosmotune.ui.components.glass
import com.cosmotune.ui.components.heroFill
import com.cosmotune.ui.components.innerRing
import com.cosmotune.ui.components.primaryFill
import com.cosmotune.ui.components.softShadow
import com.cosmotune.ui.components.tonalFill
import com.cosmotune.ui.components.topLine
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import kotlinx.coroutines.runBlocking
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The colour recipes of the morphing controls, drawn for real on an offscreen Skia canvas (no window): each gradient starts
 * and ends on the colours the spec names, a control follows the palette that is current when it draws (the designer's
 * "every button apricot" bug was a brush made once), and the edges stay inside the outline.
 */
class ControlFillsTest {
    private fun palette(argb: Int, dark: Boolean = true) = CoverPalette.fromPixels(IntArray(64 * 64) { argb }, 64, 64, dark = dark)
    private val green = palette(0xFF466044.toInt())
    private val red = palette(0xFFD4552E.toInt())

    private class Rendered(val width: Int, val height: Int, val pixels: IntArray) {
        fun at(x: Int, y: Int) = pixels[y * width + x]
    }

    private fun render(width: Int, height: Int, draw: DrawScope.() -> Unit): Rendered {
        val bitmap = ImageBitmap(width, height)
        CanvasDrawScope().draw(Density(1f), LayoutDirection.Ltr, Canvas(bitmap), Size(width.toFloat(), height.toFloat())) { draw() }
        val pixels = IntArray(width * height)
        bitmap.readPixels(pixels, 0, 0, width, height)
        return Rendered(width, height, pixels)
    }

    private fun alpha(argb: Int) = (argb ushr 24) and 0xFF

    private fun assertClose(expected: Int, actual: Int, what: String, slack: Int = 4) {
        for (shift in intArrayOf(16, 8, 0)) {
            val e = (expected shr shift) and 0xFF
            val a = (actual shr shift) and 0xFF
            assertTrue(abs(e - a) <= slack, "$what: ${expected.toUInt().toString(16)} vs ${actual.toUInt().toString(16)} (channel at $shift)")
        }
    }

    @Test fun aPrimaryButtonRunsFromAccentToTheMixOfAccentAndTheGradientEnd() {
        val colors = CosmoColors(CosmoPaletteState(green, dark = true))
        val r = render(100, 100) { drawRect(primaryFill(colors)) }
        assertClose(green.accent, r.at(0, 0), "start corner")
        assertClose(ColorMix.mix(green.accent, green.buttonGradientEnd, 0.5f), r.at(99, 99), "end corner")
    }

    @Test fun aTonalButtonRunsFromTheMixedContainerToTheContainer() {
        val colors = CosmoColors(CosmoPaletteState(red, dark = true))
        val r = render(100, 100) { drawRect(tonalFill(colors)) }
        assertClose(ColorMix.mix(red.accentContainer, red.accent, 0.09f), r.at(0, 0), "start corner")
        assertClose(red.accentContainer, r.at(99, 99), "end corner")
    }

    @Test fun theHeroPlayButtonRunsFromTheGradientStartToTheGradientEnd() {
        val colors = CosmoColors(CosmoPaletteState(green, dark = true))
        val r = render(100, 100) { drawRect(heroFill(colors)) }
        assertClose(green.buttonGradientStart, r.at(0, 0), "start corner")
        assertClose(green.buttonGradientEnd, r.at(99, 99), "end corner")
    }

    @Test fun aControlFollowsThePaletteThatIsCurrentWhenItDrawsNotTheOneItWasMadeWith() = runBlocking {
        val state = CosmoPaletteState(green, dark = true)
        val colors = CosmoColors(state)
        assertClose(green.accent, render(100, 100) { drawRect(primaryFill(colors)) }.at(0, 0), "before: the green accent")
        assertClose(green.buttonGradientStart, render(100, 100) { drawRect(heroFill(colors)) }.at(0, 0), "before: the green hero start")
        state.show(red, dark = true, reducedMotion = false, animate = false)   // the cover changed: no fade frame is needed
        assertClose(red.accent, render(100, 100) { drawRect(primaryFill(colors)) }.at(0, 0), "after: the red accent")
        assertClose(red.buttonGradientStart, render(100, 100) { drawRect(heroFill(colors)) }.at(0, 0), "after: the red hero start")
        assertNotEquals(green.accent, red.accent, "the two covers really differ")
    }

    @Test fun glassIsTheGlassFieldOfThePalette() {
        for ((dark, expected) in listOf(true to GlassPolicy.TINT_ALPHA, false to GlassPolicy.TINT_ALPHA_LIGHT)) {
            val p = palette(0xFF466044.toInt(), dark)
            val glass = CosmoColors(CosmoPaletteState(p, dark)).glass()
            assertEquals(p.glass and 0xFFFFFF, glass and 0xFFFFFF, "colour (dark = $dark): the engine's glass")
            assertEquals(p.glass ushr 24, alpha(glass), "opacity (dark = $dark): the engine's own alpha")
            assertEquals(Math.round(expected * 255f), alpha(glass), "and it is the glass opacity of the theme (dark = $dark)")
            // The designer's formulas give the dark glass the very colour of surfaceHigh (L 0.30, the same chroma rule), so only
            // the light glass differs from it: more tinted (chroma 0.32 c against 0.10 c) at the same lightness.
            if (dark) assertEquals(p.surfaceHigh and 0xFFFFFF, glass and 0xFFFFFF, "the dark glass is surfaceHigh's colour")
            else assertNotEquals(p.surfaceHigh and 0xFFFFFF, glass and 0xFFFFFF, "the light glass is its own, more tinted colour")
        }
    }

    @Test fun glassFollowsThePaletteThatIsOnScreen() = runBlocking {
        val state = CosmoPaletteState(green, dark = true)
        val colors = CosmoColors(state)
        assertEquals(green.glass, colors.glass(), "before the cover changes")
        state.show(red, dark = true, reducedMotion = false, animate = false)
        assertEquals(red.glass, colors.glass(), "after the cover changed")
        assertNotEquals(green.glass, red.glass, "the two covers really have different glass")
    }

    @Test fun theGlassSurfaceUsesTheGlassFieldOverABlurAndSurfaceHighAtNinetyFourPercentWithout() {
        // Glass.kt is a composable (no window here), so what it fills with is pinned by reading it, as the media contract test does.
        val root = generateSequence(java.io.File("").absoluteFile) { it.parentFile }.first { java.io.File(it, "settings.gradle.kts").isFile }
        val glass = java.io.File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components/Glass.kt").readText().replace("\r\n", "\n")
        val cache = glass.substringAfter("return glass.drawWithCache")
        assertTrue(cache.contains("if (blurred) colors.live(CoverField.GLASS)"), "over a blur the fill is the glass field, with its own alpha")
        assertTrue(cache.contains("colors.live(CoverField.SURFACE_HIGH).copy(alpha = GlassPolicy.SOLID_ALPHA)"), "without a blur it is surfaceHigh at the solid opacity")
        assertEquals(0.94f, GlassPolicy.SOLID_ALPHA)
    }

    @Test fun aFloatingPrimaryIsTranslucentTintedGlassAndAFloatingPlayButtonIsDenser() {
        val colors = CosmoColors(CosmoPaletteState(green, dark = true))
        val primary = render(100, 100) { drawRect(floatingPrimaryFill(colors)) }.at(0, 0)
        val play = render(100, 100) { drawRect(floatingPlayFill(colors)) }.at(0, 0)
        // 46 % opaque colour over 52 % glass leaves 0.46 + 0.54 x 0.52 = 74 % opacity; 72 % colour leaves 87 %
        assertEquals(189, alpha(primary), "C primary opacity")
        assertEquals(221, alpha(play), "C play opacity")
        assertTrue(alpha(play) > alpha(primary))
    }

    private fun outline(width: Float, height: Float, radius: Float) = MorphPath().update(width, height, radius, 4f)

    @Test fun theTopHairlineStaysInsideTheOutlineAndIsOneDpThick() {
        val path = outline(100f, 40f, 14f)
        val r = render(100, 40) { topLine(path, Color.White) }
        assertTrue(alpha(r.at(50, 0)) > 200, "drawn along the top")
        assertEquals(0, alpha(r.at(50, 3)), "and no thicker than a dp")
        assertEquals(0, alpha(r.at(0, 0)), "nothing outside the rounded corner")
        assertEquals(0, alpha(r.at(99, 0)), "nothing outside the rounded corner")
    }

    @Test fun anInnerRingNeverLeavesTheOutline() {
        val path = outline(100f, 40f, 14f)
        val r = render(100, 40) { innerRing(path, Color.White, 1f) }
        assertTrue(alpha(r.at(0, 20)) > 200, "the ring is on the edge")
        assertTrue(alpha(r.at(50, 0)) > 200, "the ring is on the top edge")
        assertEquals(0, alpha(r.at(50, 20)), "the middle is untouched")
        assertEquals(0, alpha(r.at(0, 0)), "outside the corner")
    }

    @Test fun aSoftShadowFallsBelowTheOutlineAndNowhereFar() {
        val path = outline(60f, 30f, 10f)
        val r = render(80, 70) { softShadow(path, Color.Black.copy(alpha = 0.22f), 1f, 2f) }
        assertTrue(alpha(r.at(30, 31)) > 0, "just under the bottom edge")
        assertEquals(0, alpha(r.at(30, 50)), "far below nothing")
        assertEquals(0, alpha(r.at(70, 15)), "to the right of the shape and beyond its reach nothing")
        assertTrue(alpha(r.at(30, 31)) <= Math.round(0.22f * 255f), "never darker than the shadow colour")
    }
}
