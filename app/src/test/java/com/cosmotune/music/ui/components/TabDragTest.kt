package com.cosmotune.music.ui.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** BC-34: the classic tab bars' dragged indicator stays between the first and last tab and counts from where the drag began. */
class TabDragTest {
    private val step = 100f
    private val last = 3

    @Test fun aDragPastEitherEndStopsOnTheEndTab() {
        assertEquals(3f, TabDrag.position(2, 900f, step, last), 0f)
        assertEquals(0f, TabDrag.position(1, -900f, step, last), 0f)
        assertEquals(3, TabDrag.target(3, 40f, step, last))
        assertEquals(0, TabDrag.target(0, -40f, step, last))
    }

    @Test fun theIndicatorFollowsTheFingerFromTheTabTheDragStartedOn() {
        assertEquals(1.5f, TabDrag.position(1, 50f, step, last), 0.0001f)
        assertEquals(2, TabDrag.target(1, 60f, step, last))
        assertEquals(1, TabDrag.target(1, 40f, step, last))
        // The selection moving to 2 during the drag does not move the start: 160 px from tab 1 is tab 2.6, not 3.6 clamped.
        assertEquals(2.6f, TabDrag.position(1, 160f, step, last), 0.0001f)
    }

    @Test fun anUnmeasuredRowLeavesTheIndicatorWhereItIs() {
        val position = TabDrag.position(2, 120f, 0f, last)
        assertEquals(2f, position, 0f)
        assertFalse(position.isNaN())
    }

    @Test fun bothBarsUseItAndReadTheCurrentStateNotTheFirstComposition() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val bottom = File(root, "app/src/main/java/com/cosmotune/music/ui/components/FloatingBottomBar.kt").readText()
        assertTrue("TabDrag.target(dragStartIndex, totalDrag, currentStepPx, currentLastIndex)" in bottom)
        assertTrue("dragStartIndex = currentSelectedIndex" in bottom && "var dragStartIndex = selectedIndex" !in bottom)
        val glass = File(root, "app/src/main/java/com/cosmotune/music/ui/components/floatingtabbar/FloatingTabBar.kt").readText()
        assertTrue("re-installed once the row is measured", ".pointerInput(tabCount, tabStepPx, isRtl) {" in glass)
        assertTrue("TabDrag.target(dragStartIndex, totalDrag, tabStepPx, scope.tabs.lastIndex)" in glass)
    }
}
