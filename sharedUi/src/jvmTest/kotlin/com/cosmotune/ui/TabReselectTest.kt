package com.cosmotune.ui

import com.cosmotune.ui.shell.isTabReselect
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** A tap on the chosen tab, on its own page, scrolls it back to the top, on the phone and the desktop (upstream 1.10). */
class TabReselectTest {
    @Test fun onlyTheChosenTabAtItsRootScrolls() {
        assertTrue(isTabReselect(tapped = 2, selected = 2, atRoot = true))
        assertFalse(isTabReselect(tapped = 2, selected = 2, atRoot = false), "with a page open over it, the tap goes back to the tab first")
        assertFalse(isTabReselect(tapped = 1, selected = 2, atRoot = true))
    }

    @Test fun bothAppsScrollTheirPagesToTheTop() {
        val phone = SourceContract.read("app/src/main/java/com/cosmotune/music/MainActivity.kt")
        assertTrue("isTabReselect(" in phone && "scrollToTopGently(it)" in phone, "the phone's tab handler")
        val desktop = SourceContract.read("desktopApp/src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
        assertTrue("isTabReselect(next.ordinal, destination.ordinal, atRoot)" in desktop && "scrollToTopGently(state)" in desktop, "the desktop's")
    }
}
