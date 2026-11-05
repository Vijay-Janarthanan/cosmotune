package com.cosmotune.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * BC-32: every sideways carousel the app ships settles on a card (upstream #737), on both looks and both hosts. The behaviour is
 * one function, `rememberShelfFlingBehavior`, rendered in `ShelfSnapInteractionTest`; this pins who uses it.
 */
class ShelfSnapContractTest {
    private fun code(path: String) = SourceContract.code(SourceContract.read(path))
    private fun uses(path: String) = Regex("""flingBehavior = (com\.cosmotune\.music\.ui\.components\.)?rememberShelfFlingBehavior\(""").findAll(code(path)).count()

    @Test fun theOneBehaviourSnapsToTheLeadingEdge() {
        val shelf = code("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/ShelfRow.kt")
        assertTrue("rememberSnapFlingBehavior(lazyListState = state, snapPosition = SnapPosition.Start)" in shelf)
        assertEquals(1, uses("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/music/ui/components/ShelfRow.kt"), "the old shelves (Home, Library, the desktop's shared pages)")
    }

    @Test fun theNewLookShelvesAndRailsSnap() {
        assertEquals(1, uses("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/blocks/CosmoCards.kt"), "CosmoShelf: Home, Library, the artist pages on both hosts, moods")
        assertEquals(1, uses("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/search/CosmoSearchResults.kt"), "the wide search's artist circles")
    }

    @Test fun thePhonesArtistCarouselsSnap() {
        assertEquals(2, uses("app/src/main/java/com/cosmotune/music/ui/screens/DetailScreen.kt"), "top songs and the album/single carousels")
        assertTrue("flingBehavior = rememberSnapFlingBehavior(lazyListState = rowState)" in code("app/src/main/java/com/cosmotune/music/ui/replay/ReplayScreen.kt"), "Replay's cards, as before")
    }

    @Test fun theDesktopRowsSnapOnFlingDragReleaseAndPaging() {
        val desktop = code("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertEquals(1, uses("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt"), "DesktopScrollableRow (history shelves, Replay)")
        val chrome = SourceContract.functionBody(desktop.substringAfter("internal object DesktopShelfRowChrome"), "Wrap")
        assertTrue("val settle = com.cosmotune.music.ui.components.rememberShelfFlingBehavior(state)" in chrome)
        assertTrue("onDragStopped = { velocity -> state.scroll { with(settle) { performFling(-velocity) } } }" in chrome, "a pulled row is let go onto a card")
        assertTrue("state.scroll { with(settle) { performFling(0f) } }" in chrome, "a paged row ends on a card")
    }
}
