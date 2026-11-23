package com.cosmotune.ui

import com.cosmotune.ui.orbit.OrbitTooltipPosition
import com.cosmotune.ui.orbit.orbitShortcut
import com.cosmotune.ui.orbit.orbitTooltipText
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every icon-only button names itself: the shared Orbit buttons carry the one tooltip ([com.cosmotune.ui.orbit.OrbitTooltip]), and
 * no caller hands one of them an empty label (it would be a silent button to a screen reader and a tooltip with nothing in it).
 */
class OrbitTooltipContractTest {
    private fun code(path: String) = SourceContract.code(SourceContract.read(path)).replace(Regex("\\s+"), " ")

    private val ui = "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui"

    @Test fun theSharedIconButtonsCarryTheTooltip() {
        val buttons = mapOf(
            "$ui/orbit/OrbitControls.kt" to listOf("OrbitRoundButton", "OrbitPlayButton"),
            "$ui/orbit/OrbitDock.kt" to listOf("OrbitDockTab"),
            "$ui/shell/CosmoNavShell.kt" to listOf("CosmoSearchButton"),
            "$ui/kit/KitControls.kt" to listOf("KitPlayCircle"),
        )
        for ((path, names) in buttons) {
            val src = SourceContract.read(path)
            for (name in names) {
                val body = SourceContract.functionBody(src, name)
                assertTrue(".orbitTooltip(" in body && "OrbitTooltipPill(" in body, "$name wears the tooltip")
            }
        }
        val icon = SourceContract.functionBody(SourceContract.read("$ui/components/CosmoIconButton.kt"), "CosmoIconButton")
        assertTrue("OrbitRoundButton(" in icon && "shortcut = shortcut" in icon, "the old icon button is the round one, tooltip and all")
        assertTrue("TooltipBox(" !in code("$ui/components/CosmoIconButton.kt"), "the old Material tooltip is gone")
    }

    @Test fun noIconButtonIsGivenAnEmptyLabel() {
        val empty = listOf(
            Regex("""OrbitRoundButton\(\s*[^,()]+,\s*""\s*,"""),
            Regex("""OrbitRoundButton\([^)]*label\s*=\s*""\s*[,)]"""),
            Regex("""CosmoIconButton\([^)]*contentDescription\s*=\s*""\s*[,)]"""),
            Regex("""WatchRoundButton\(\s*[^,()]+,\s*""\s*,"""),
            Regex("""ReadIconButton\(\s*[^,()]+,\s*""\s*,"""),
            Regex("""OrbitAction\(\s*[^,()]+,\s*""\s*,"""),
        )
        val offenders = mutableListOf<String>()
        for (module in listOf("sharedUi", "video", "manga", "watch", "desktopApp", "app", "tv")) {
            File(SourceContract.root, module).walkTopDown()
                .filter { it.isFile && it.extension == "kt" && "${File.separator}build${File.separator}" !in it.path && "Test" !in it.name }
                .forEach { file ->
                    val text = SourceContract.code(file.readText())
                    empty.forEach { rule -> rule.find(text)?.let { offenders += "${file.name}: ${it.value.take(80)}" } }
                }
        }
        assertTrue(offenders.isEmpty(), "icon buttons without a label: $offenders")
    }

    @Test fun theTooltipNamesTheShortcutOnTheDesktopOnly() {
        assertEquals("Play (Space)", orbitTooltipText("Play", "Space", desktop = true))
        assertEquals("Play", orbitTooltipText("Play", "Space", desktop = false))
        assertEquals("Like", orbitTooltipText("Like", null, desktop = true))
        assertEquals("⌘1", orbitShortcut("1", mac = true))
        assertEquals("Ctrl+1", orbitShortcut("1", mac = false))
    }

    @Test fun thePillStaysOnScreenAndOffItsControl() {
        val window = IntSize(1000, 800)
        val pill = IntSize(120, 30)
        val under = OrbitTooltipPosition(gap = 8, margin = 8, above = false)
        assertEquals(IntOffset(440, 148), under.calculatePosition(IntRect(480, 100, 520, 140), window, LayoutDirection.Ltr, pill), "centred, 8 under")
        assertEquals(8, under.calculatePosition(IntRect(0, 100, 40, 140), window, LayoutDirection.Ltr, pill).x, "kept in from the start")
        assertEquals(window.width - pill.width - 8, under.calculatePosition(IntRect(960, 100, 1000, 140), window, LayoutDirection.Ltr, pill).x, "kept in from the end")
        assertEquals(752 - 8 - 30, under.calculatePosition(IntRect(480, 752, 520, 792), window, LayoutDirection.Ltr, pill).y, "over it at the window's foot")
        val over = OrbitTooltipPosition(gap = 8, margin = 8, above = true)
        assertEquals(100 - 8 - 30, over.calculatePosition(IntRect(480, 100, 520, 140), window, LayoutDirection.Ltr, pill).y, "a finger's: over the control")
        assertEquals(58, over.calculatePosition(IntRect(480, 10, 520, 50), window, LayoutDirection.Ltr, pill).y, "no room over: under it")
        val lifted = OrbitTooltipPosition(gap = 8, margin = 8, above = true, lift = 72)
        assertEquals(700 - 72 - 8 - 30, lifted.calculatePosition(IntRect(480, 700, 520, 740), window, LayoutDirection.Ltr, pill).y, "a dock tab's: over the small player above it")
    }
}
