package com.cosmotune.video.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The patterns the design system takes out of Video (DESIGN_SYSTEM.md 3.2, 3.4, 7): read from every source file of the module's screens, so
 * none of them comes back with a later change.
 */
class OneRulesStructureTest {
    private val sources: List<Pair<String, String>> by lazy {
        val ui = File(VideoSourceContract.root, "video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui")
        ui.walkTopDown().filter { it.extension == "kt" }.map { it.name to VideoSourceContract.code(it.readText()) }.toList().also {
            assertTrue(it.size > 100, "the check found the module's files")
        }
    }

    @Test fun everyCornerIsOnTheRadiusTable() {
        val literal = Regex("""RoundedCornerShape\(\d+(\.\d+)?\.dp\)""")
        for ((name, code) in sources) {
            assertFalse(literal.containsMatchIn(code), "$name has a literal radius; use a CosmoRadii token, CircleShape or RoundedCornerShape(percent = 50)")
            assertFalse("SuperellipseShape" in code, "$name draws a superellipse")
        }
    }

    @Test fun noTitleIsCondensed() {
        val banned = Regex("""\btype\.(display|title)\(|\bdisplayFamily\b""")
        for ((name, code) in sources) assertFalse(banned.containsMatchIn(code), "$name reaches past the One tokens of LocalCosmoType")
    }

    @Test fun theWatchPageHasNoWrappedActionsAndNoDescriptionTile() {
        val meta = sources.single { it.first == "WatchMeta.kt" }.second
        assertTrue("KitActionRow(" in meta && "FlowRow" !in meta)
        val page = sources.single { it.first == "WatchPage.kt" }.second
        assertFalse("DescriptionTile(" in page)
    }

    @Test fun theLibraryTabIsCalledLibrary() {
        val strings = VideoSourceContract.read("video/src/commonMain/composeResources/values/strings.xml")
        assertTrue("<string name=\"video_tab_you\">Library</string>" in strings)
        val tabs = sources.single { it.first == "VideoTabs.kt" }.second
        assertTrue("SharedRes.string.library" in tabs, "the app's own word, which every locale translates")
    }
}
