package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The polish pass of the new look (task P4-19), pinned by reading the sources (`SourceContract`): what cannot be seen without a window.
 * One section per item of the pass.
 */
class PolishContractTest {
    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    private fun code(path: String) = SourceContract.code(SourceContract.read(path))

    // ---- the colour intensity reaches Now Playing's own palette --------------------------------------------------------------------

    @Test fun nowPlayingsOwnPaletteLoadsAtTheIntensityOfTheSetting() {
        val src = SourceContract.read("$ui/nowplaying/NowPlayingColors.kt")
        val body = SourceContract.functionBody(src, "rememberLightThemeColors")
        assertTrue("shell.settings.intensity" in body && "Intensity.multiplier(" in body, "it reads the setting")
        assertTrue(Regex("""produceState\(DarkLookup\(false, null\),[^{]*\bintensity\)""").containsMatchIn(body), "the dark palette is looked up again when the setting changes")
        assertTrue(Regex("""CoverPaletteLoader\.load\([^)]*\bintensity = intensity\)""").containsMatchIn(body), "and loaded at it")
    }

    @Test fun theLoaderBuildsTheSpecOfTheSetting() {
        val body = SourceContract.code(SourceContract.functionBody(SourceContract.read("$ui/theme/CoverPaletteLoader.kt"), "load"))
        assertTrue("CoverPaletteSpec.forIntensity(intensity)" in body, "the ceilings move with the setting, not only the budget")
        assertTrue("CoverPaletteSpec(intensity = intensity)" !in body)
    }
}
