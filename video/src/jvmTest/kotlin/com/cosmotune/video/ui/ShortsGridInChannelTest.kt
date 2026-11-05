package com.cosmotune.video.ui

import com.cosmotune.video.ui.shorts.ShortsGridLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the channel page's Shorts tab (Y-20) relies on in Y-31's `ShortsGrid` (checked in Y-34): the grid is a plain scrollable
 * that hands its scroll to the nested-scroll connection of the page around it (the channel's head moves up by what the grid
 * consumed), and it takes the side margins from the page's padding, adding none of its own when the page gives sides.
 */
class ShortsGridInChannelTest {
    private val grid = VideoSourceContract.functionBody(
        VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/shorts/ShortsStubs.kt"), "ShortsGrid",
    )
    private val channel = VideoSourceContract.read("video/src/jvmSharedMain/kotlin/com/cosmotune/video/ui/channel/ChannelPage.kt")

    private fun flat(text: String) = text.replace(Regex("\\s+"), " ")

    @Test fun theGridIsAPlainScrollableThatPassesNestedScrollUp() {
        val code = VideoSourceContract.code(grid)
        assertEquals(1, Regex("""LazyVerticalGrid\(""").findAll(code).count(), "one lazy grid, which scrolls by itself")
        assertFalse("userScrollEnabled" in code, "user scrolling is left on")
        assertFalse("nestedScroll(" in code, "no connection of its own that could consume or cut the chain to the page's")
        assertFalse(Regex("""\.(verticalScroll|scrollable|draggable)\(""").containsMatchIn(code), "no second scrollable around the grid")
    }

    @Test fun theChannelsConnectionIsAroundTheGrid() {
        val tab = flat(channel)
        assertTrue("Box(Modifier.fillMaxSize().nestedScroll(connection)) { ShortsGrid(" in tab, "the grid sits inside the page's nested-scroll connection")
        assertTrue("contentPadding = gridPadding," in tab && "start = line.contentLine, end = line.contentLine," in tab, "the page passes its margins")
    }

    @Test fun theGridTakesThePagesSidesAndAddsNone() {
        val code = flat(VideoSourceContract.code(grid))
        assertTrue("val start = if (noSides) layout.sideDp.dp else contentPadding.calculateStartPadding(direction)" in code)
        assertTrue("val end = if (noSides) layout.sideDp.dp else contentPadding.calculateEndPadding(direction)" in code)
        assertTrue("contentPadding = PaddingValues(start = start, top = contentPadding.calculateTopPadding(), end = end, bottom = contentPadding.calculateBottomPadding())" in code)
        for (wide in listOf(false, true)) {
            val layout = ShortsGridLayout.of(if (wide) 1280f else 360f, wide)
            assertFalse(layout.keepsMargin(16f, 16f) || layout.keepsMargin(28f, 28f), "page sides given: no margin of its own (wide = $wide)")
            assertTrue(layout.keepsMargin(0f, 0f), "no sides given: the grid keeps the margin (wide = $wide)")
        }
    }
}
