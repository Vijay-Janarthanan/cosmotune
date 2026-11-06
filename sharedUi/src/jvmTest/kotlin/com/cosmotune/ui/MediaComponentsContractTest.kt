package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * There is no Compose UI test harness here (and none is to be added), so what cannot be seen without a window is pinned by
 * reading the source: the rules the media components were written under (Review Focus 5, the performance budget, the Haze
 * decision). A rule that needs a screen is on the task's device checklist instead.
 */
class MediaComponentsContractTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .first { File(it, "settings.gradle.kts").isFile }

    private fun source(path: String): String =
        File(root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/$path").readText().replace("\r\n", "\n")

    private fun code(path: String): String = source(path).lines().filterNot { it.trimStart().startsWith("*") || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") }.joinToString("\n")

    // ---- Glass: Review Focus 5, and the Haze decision ----

    @Test fun glassBlursOnlyWhenThePolicyAllowsIt() {
        val glass = code("components/Glass.kt")
        assertEquals(1, Regex("""optimizedHazeEffect\(""").findAll(glass).count(), "one Haze effect, and only one")
        val block = glass.substringAfter("if (blurred) {").substringBefore("return glass.drawWithCache")
        assertTrue(block.contains("optimizedHazeEffect("), "the Haze effect is inside `if (blurred)`")
        assertTrue(glass.contains("val blurred = rememberGlassMode("), "`blurred` comes from the policy")
        assertTrue(glass.contains("GlassPolicy.mode(platformSdkInt, reduceBlur, lowRam, overVideo)"), "the policy gets every input of Review Focus 5")
        assertTrue(glass.contains("hazeState != null"), "no backdrop to sample means solid")
    }

    @Test fun glassUsesOnlyTheHazeApiThatBothVersionsShare() {
        // sharedUi compiles against Haze 1.3.1 (Android runs it); the Windows desktop runs 1.7.2 and Linux 1.7.3. HazeState,
        // HazeStyle(backgroundColor, tints, blurRadius, noiseFactor, fallbackTint), HazeTint(color) and hazeEffect(state, style)
        // have the same signatures in both jars (checked with javap); these have not, or are newer than 1.3.1.
        val glass = source("components/Glass.kt")
        for (banned in listOf("rememberHazeState", "HazeMaterials", "HazeProgressive", "hazeChild", "blurredEdgeTreatment", "drawContentBehind", "colorEffect")) {
            assertFalse(glass.contains(banned), "Glass.kt uses $banned, which the two Haze versions do not share")
        }
        assertTrue(glass.contains("import dev.chrisbanes.haze.HazeState"))
        assertTrue(glass.contains("import dev.chrisbanes.haze.HazeStyle"))
        assertTrue(glass.contains("import dev.chrisbanes.haze.HazeTint"))
    }

    @Test fun glassReadsItsColoursWhileDrawing() {
        val glass = code("components/Glass.kt")
        // Read inside drawWithCache (observed, so a fade rebuilds the cache) and never at theme creation.
        val cache = glass.substringAfter("return glass.drawWithCache")
        assertTrue(cache.contains("colors.live(CoverField.SURFACE_HIGH)"))
        assertFalse(cache.contains("CoverField.ACCENT_CONTAINER"), "the glass is neutral: no tint from the accent (DESIGN_SYSTEM.md 2.2, 2.6)")
    }

    // ---- The cover ----

    @Test fun theCoverShadowIsInTheCoversOwnColourAndReadWhileDrawing() {
        val cover = code("components/CoverImage.kt")
        assertTrue(cover.contains("spot = { colors.live(CoverField.SHADOW)"), "the key shadow is the palette's shadow, resolved in the layer block")
        assertFalse(cover.contains("coverShadowArgb"), "no colour is worked out at composition and kept")
        // dropShadow cuts its blur at one radius outside the shape and leaves a visible rectangle on a light ground.
        assertFalse(cover.contains("dropShadow"))
        assertFalse(code("components/Glass.kt").contains("dropShadow"))
        assertTrue(code("components/Shadows.kt").contains("spotShadowColor = spot()"))
    }

    @Test fun theCoverAsksForARungOfTheCacheLadder() {
        assertTrue(code("components/CoverImage.kt").contains("CoverArt.px(size.value, density)"))
    }

    // ---- The scrubber and the slider ----

    @Test fun theSeekBarAllocatesNothingWhileDrawing() {
        val bar = code("components/CosmoSeekBar.kt")
        assertFalse(Regex("""\bPath\(\)""").containsMatchIn(bar), "two lines and a circle: no path to make")
        assertFalse(bar.contains("FloatArray("), "and no buffer")
        assertTrue(bar.contains("fraction().coerceIn(0f, 1f)"), "the position is read while drawing")
        assertTrue(bar.contains("if (dragging) drawCircle("), "the thumb shows on touch only")
    }

    @Test fun theSeekBarHasNoFrameLoop() {
        // DESIGN_SYSTEM.md R7: a straight seek bar, so there is no frame loop left to stop: it draws when the position moves.
        val bar = code("components/CosmoSeekBar.kt")
        assertFalse(bar.contains("withInfiniteAnimationFrameNanos"), "no frame loop")
        assertFalse(bar.contains("Wave."), "no wave")
    }

    @Test fun scrubbersAndSlidersHaveAFortyEightDpTouchAreaAHandleForReadersAndKeys() {
        for (file in listOf("components/CosmoSeekBar.kt", "components/CosmoSlider.kt")) {
            val src = code(file)
            assertTrue(src.contains("SliderMetrics.touchHeightDp("), "$file: 48 dp touch height")
            assertTrue(src.contains("progressBarRangeInfo"), "$file: range for screen readers")
            assertTrue(src.contains("setProgress"), "$file: progress action for screen readers")
            assertTrue(src.contains(".scrubKeys("), "$file: arrow keys")
            assertTrue(src.contains(".cosmoFocusRing("), "$file: visible focus")
            assertTrue(src.contains(".scrubGestures("), "$file: tap and drag")
        }
    }

    @Test fun theSeekBarIsNamedForScreenReadersFromAStringResource() {
        assertTrue(code("components/CosmoSeekBar.kt").contains("stringResource(Res.string.cosmo_seek_bar)"))
    }

    // ---- The preview ----

    @Test fun thePreviewOwnsItsPaletteAndNeverTouchesTheSongs() {
        val preview = source("settings/LookPreview.kt")
        assertFalse(preview.contains("LocalCosmoShell"), "the preview works with the New look switch off")
        assertTrue(preview.contains("CosmoPaletteState("))
        assertTrue(preview.contains("LocalCosmoColors provides colors"))
        assertTrue(preview.contains("Dispatchers.Default"), "the engine runs off the main thread")
    }

    @Test fun thePreviewShowsTheControlsOfTheLookInOrbitAndNoGlass() {
        val preview = source("settings/LookPreview.kt")
        for (control in listOf("CosmoButton(", "CosmoIconButton(", "OrbitPlayButton(", "CosmoChip(", "OrbitSelectChip(", "CosmoSwitch(", "OrbitProgressLine(", "OrbitRing(", "OrbitBiteCard(")) {
            assertTrue(preview.contains(control), "$control is shown")
        }
        for (old in listOf("cosmoGlass", "hazeSource", "PlayMorphButton(", "CosmoSegmented(", "floating = true")) assertFalse(preview.contains(old), "Orbit has no $old")
    }
}
