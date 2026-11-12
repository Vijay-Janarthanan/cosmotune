package com.cosmotune.desktop.update

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/** Where the desktop's update lives in the app: inside the theme, re-checked while running, and reachable from the top bar. */
class DesktopUpdateContractTest {
    private fun source(path: String) = File(path).readText().replace(Regex("\\s+"), " ")
    private val app = source("src/main/kotlin/com/cosmotune/music/desktop/DesktopApp.kt")
    private val chrome = source("src/main/kotlin/com/cosmotune/desktop/CosmoDesktopChrome.kt")

    @Test fun theOfferStandsInsideTheTheme() {
        val theme = app.indexOf("themeChoice = DesktopThemeChoice, ) {")
        val offer = app.indexOf("updateOffer?.takeIf { !updatePostponed }")
        assertTrue(theme in 0 until offer, "the update card is drawn inside CosmoLookTheme")
    }

    @Test fun itLooksAgainWhileRunning() {
        assertTrue("updates.refresh(com.cosmotune.update.UpdateTrigger.PERIODIC)" in app)
        assertTrue("updates.refresh(com.cosmotune.update.UpdateTrigger.RETURNED)" in app)
    }

    @Test fun thePutOffUpdateIsInTheTopBarOfEveryMode() {
        assertTrue("com.cosmotune.desktop.update.DesktopUpdatePill(" in chrome)
    }
}
