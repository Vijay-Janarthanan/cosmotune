package com.cosmotune.ui

import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.collapseFraction
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The mode-agnostic kit (`com.cosmotune.ui.kit`): what every mode is rebuilt on, pinned where a window is not needed. */
class KitContractTest {
    private val kitDir = File(SourceContract.root, "sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/kit")
    private val sources get() = kitDir.listFiles { f -> f.extension == "kt" }!!.associate { it.name to SourceContract.code(it.readText()) }

    @Test fun theLargeTitleCollapsesOverTheFirstItemAndStaysCollapsedPastIt() {
        assertEquals(0f, collapseFraction(0, 0, 100f))
        assertEquals(0.5f, collapseFraction(0, 50, 100f))
        assertEquals(1f, collapseFraction(0, 500, 100f))
        assertEquals(1f, collapseFraction(3, 0, 100f))
    }

    @Test fun theArtShapesKeepTheirRatios() {
        assertEquals(1f, KitArt.SQUARE.ratio)
        assertEquals(16f / 9f, KitArt.WIDE.ratio)
        assertEquals(2f / 3f, KitArt.POSTER.ratio)
    }

    @Test fun everyPieceOfTheKitIsThere() {
        val all = sources.values.joinToString("\n")
        for (name in listOf("KitLargeTopBar", "KitSectionHeader", "KitIconButton", "KitChip", "KitChipRow", "KitPill", "KitTextButton", "KitButton",
            "KitArtwork", "KitMediaRow", "KitGroup", "KitDivider", "KitSettingsRow", "KitCard", "KitVideoCard", "KitShelf", "KitQuickTile",
            "KitEmptyState", "KitErrorState", "KitOfflineBanner", "KitSkeletonRows", "KitNavRail", "KitSheet")) {
            assertTrue(Regex("""\nfun $name\(""").containsMatchIn(all), "fun $name( is in the kit")
        }
        val doc = SourceContract.read("docs/design/REVAMP_DIRECTION.md")
        for (name in listOf("KitLargeTopBar", "KitMediaRow", "KitCard", "KitChip", "KitSheet", "KitNavRail", "CosmoBottomChrome")) assertTrue(name in doc, "$name is documented")
    }

    @Test fun theKitTakesItsColoursFromThePaletteAndDrawsNoCondensedType() {
        for ((file, code) in sources) {
            assertTrue("LocalCosmoColors.current" in code, "$file reads the palette")
            assertFalse("displayFamily" in code, "$file draws no condensed display type")
            assertFalse(Regex("""FontWeight\((7[5-9]\d|8\d\d|9\d\d)\)""").containsMatchIn(code), "$file asks for no black weight")
        }
    }

    @Test fun theLargeTitleReadsItsCollapseOnlyInLayerAndDrawBlocks() {
        val bar = SourceContract.functionBody(sources.getValue("KitHeaders.kt"), "KitLargeTopBar")
        val reads = Regex("""collapse\(\)""").findAll(bar).map { it.range.first }.toList()
        assertTrue(reads.isNotEmpty())
        for (at in reads) {
            val before = bar.substring(0, at)
            val opener = maxOf(before.lastIndexOf("graphicsLayer {"), before.lastIndexOf("drawBehind {"))
            assertTrue(opener >= 0 && before.substring(opener).count { it == '{' } > before.substring(opener).count { it == '}' }, "collapse() is read inside a layer or draw block")
        }
    }
}
