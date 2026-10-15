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
}
