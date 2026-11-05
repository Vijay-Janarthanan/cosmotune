package com.cosmotune.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * What no screenshot harness can check, read from the source of the morphing controls: they never keep a brush, every one
 * reserves a 48 dp touch target, takes the `floating` parameter of the C look, and carries the role the spec names. The
 * look itself is on the phone (Task 7's preview).
 */
class ControlsStructureTest {
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
    private val dir = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/components"

    private fun source(name: String): String {
        val file = File(root, "$dir/$name.kt")
        if (!file.isFile) fail("$dir/$name.kt is not where this test expects it")
        return file.readText()
    }

    private val controls = listOf("CosmoButton", "CosmoIconButton", "PlayMorphButton", "CosmoChip", "CosmoSegmented", "CosmoSwitch")
    private val everyFile = controls + listOf("MorphBox", "ControlFills")

    private fun signature(text: String, function: String): String {
        val start = text.indexOf("fun $function(")
        if (start < 0) fail("no `fun $function(` found")
        var depth = 0
        for (i in start until text.length) {
            if (text[i] == '(') depth++
            if (text[i] == ')' && --depth == 0) return text.substring(start, i + 1)
        }
        fail("unbalanced signature of $function")
    }

    @Test fun noBrushOrLiveColourIsRememberedAtCreation() {
        // The designer's round-1 bug: a brush made once at theme creation makes every control the fallback apricot.
        val remembering = Regex("""remember\s*(\([^)]*\))?\s*\{[^}]*(Brush\.|SolidColor|\.live\(|cssGradient|Fill\()""")
        for (name in everyFile) {
            val bad = remembering.find(source(name).replace("\r\n", "\n"))
            assertTrue(bad == null, "$name remembers a brush or a live colour: ${bad?.value}")
        }
    }

    @Test fun everyControlTakesTheFloatingParameterOfTheCLook() {
        for (name in controls) assertTrue(Regex("""floating:\s*Boolean\s*=\s*false""").containsMatchIn(signature(source(name), name)), "$name has no `floating: Boolean = false`")
    }

    @Test fun everyControlReservesA48DpTouchTarget() {
        for (name in controls) assertTrue("minimumInteractiveComponentSize()" in source(name), "$name does not call minimumInteractiveComponentSize()")
    }

    @Test fun everyControlCarriesItsRoleAndAName() {
        assertTrue("Role.Button" in source("MorphBox"), "buttons are Role.Button by default")
        assertTrue("Role.Checkbox" in source("CosmoIconButton") && "contentDescription" in signature(source("CosmoIconButton"), "CosmoIconButton"), "an icon button toggles as a checkbox and needs a description")
        assertTrue("Res.string.play" in source("PlayMorphButton") && "Res.string.pause" in source("PlayMorphButton"), "the play button says Play or Pause")
        assertTrue("Role.Checkbox" in source("CosmoChip"), "a chip is a checkbox")
        assertTrue("selectableGroup()" in source("CosmoSegmented") && "Role.Tab" in source("CosmoSegmented"), "a segmented control is a group of tabs")
        assertTrue("Role.Switch" in source("CosmoSwitch"), "a switch is a switch")
    }

    @Test fun everyControlMorphsOnThePressSpringAndFallsBackToAFadeUnderReducedMotion() {
        assertTrue("CosmoMotion.press(reduced)" in source("MorphBox"), "MorphBox animates on the press spring, or the 150 ms fade")
        assertTrue("if (reduced) 1f" in source("MorphBox"), "no scale under reduced motion")
        assertTrue("CosmoMotion.move(reduced)" in source("CosmoSwitch"), "the switch thumb travels on the move spring")
        assertTrue("CosmoMotion.move(reduced)" in source("CosmoSegmented"), "the floating segmented thumb slides on the move spring")
    }

    @Test fun everyControlHasAFocusRing() {
        for (name in listOf("MorphBox", "CosmoSegmented", "CosmoSwitch")) assertTrue("cosmoFocusRing(" in source(name), "$name has no focus ring")
    }

    @Test fun controlsReadTheirColoursFromTheLivePaletteWhileTheyDraw() {
        for (name in listOf("ControlFills", "CosmoSwitch")) assertTrue("live(" in source(name), "$name does not read live colours")
    }
}
