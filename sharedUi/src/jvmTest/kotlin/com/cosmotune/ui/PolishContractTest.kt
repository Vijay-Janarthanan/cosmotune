package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The behaviour fixes of the polish task (P4-20), pinned by reading the source: what cannot be seen without a window. One section
 * per item; each pins the literal text of the upstream site it changed (so a merge that undoes the fix shows up here) and the
 * look-off rule (with the look off the old line runs as before).
 */
class PolishContractTest {
    private val app = "app/src/main/java/com/music/bitchord/"
    private val desktop = "desktopApp/src/main/kotlin/com/music/bitchord/desktop/"
    private val cosmoDesktop = "desktopApp/src/main/kotlin/com/cosmotune/desktop/"

    // ---- 1. the equaliser toggle ticks once -----------------------------------------------------------------------------------

    @Test fun theEqualizerScreensOwnToggleHapticPlaysOnlyWithTheLookOff() {
        val src = SourceContract.code(SourceContract.read("${app}ui/screens/EqualizerScreen.kt"))
        val flat = src.replace(Regex("\\s+"), " ")
        // read in composition (a callback cannot read a composition local), then the switch's callback asks it: CosmoSwitch
        // (through the bridge) already ticks with the look on
        assertTrue(
            flat.contains("val cosmoLookOff = com.cosmotune.ui.theme.LocalCosmoShell.current == null"),
            "the screen reads whether the look is off in composition",
        )
        assertTrue(
            flat.contains("onCheckedChange = { if (cosmoLookOff) haptics.play(if (it) Haptic.ToggleOn else Haptic.ToggleOff)"),
            "the switch's callback plays its haptic only with the look off",
        )
        assertFalse(
            Regex("""onCheckedChange = \{\s*haptics\.play""").containsMatchIn(src),
            "no unconditional haptic in the switch's callback",
        )
        // the row's own tap (not the switch) still ticks once in both looks
        assertTrue(
            Regex("""onClick = \{\s*haptics\.play\(if \(!enabled\) Haptic\.ToggleOn else Haptic\.ToggleOff\)""").containsMatchIn(src),
            "the row's tap keeps its haptic",
        )
    }

    // ---- 2. the playing bars of the desktop rows move ---------------------------------------------------------------------------

    @Test fun theDesktopRowsAndPagesReadWhetherAudioIsPlayingFromOneLocalOfOurs() {
        val row = SourceContract.code(SourceContract.read("${cosmoDesktop}CosmoDesktopSongRow.kt"))
        assertTrue(
            Regex("""internal val LocalCosmoPlaying = compositionLocalOf \{ false \}""").containsMatchIn(row),
            "the local is declared in our file, false by default",
        )
        for ((file, function) in listOf(
            "CosmoDesktopSongRow.kt" to "CosmoDesktopSongRow",
            "CosmoDesktopCollectionPage.kt" to "CosmoDesktopCollectionPage",
            "CosmoDesktopArtistPage.kt" to "CosmoDesktopArtistPage",
        )) {
            val parameters = SourceContract.parameters(SourceContract.read("$cosmoDesktop$file"), function)
            assertTrue(
                "isPlaying: Boolean = LocalCosmoPlaying.current" in parameters,
                "$function's isPlaying starts from the local, not from a constant false: $parameters",
            )
        }
    }

    @Test fun theDesktopRootProvidesThePlayersFlagNextToTheNowPlayingSong() {
        val src = SourceContract.code(SourceContract.read("${desktop}DesktopApp.kt")).replace(Regex("\\s+"), " ")
        assertTrue(
            src.contains("LocalNowPlaying provides selectedSong, com.cosmotune.desktop.LocalCosmoPlaying provides playback.isPlaying,"),
            "the root provides playback.isPlaying beside the playing song",
        )
        // nobody sets it to a constant behind the root's back
        assertFalse(Regex("""LocalCosmoPlaying provides (true|false)""").containsMatchIn(src), "no constant is provided")
    }
}
