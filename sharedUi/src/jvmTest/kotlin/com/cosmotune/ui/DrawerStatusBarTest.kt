package com.cosmotune.ui

import androidx.compose.ui.graphics.Color
import com.cosmotune.music.ui.theme.groundWantsDarkGlyphs
import kotlin.test.Test
import kotlin.test.assertTrue

/** The drawer's own window sets the status bar glyphs from the panel's ground, so the clock reads on a light panel. */
class DrawerStatusBarTest {
    @Test fun lightPanelWantsDarkGlyphsAndDarkPanelLightOnes() {
        assertTrue(groundWantsDarkGlyphs(Color(0xFFF7EFE9)), "the light panel's ground")
        assertTrue(!groundWantsDarkGlyphs(Color(0xFF1A1215)), "the dark panel's ground")
    }

    @Test fun theDrawerLayerSetsTheGlyphsFromThePanelGround() {
        val src = SourceContract.read("sharedUi/src/jvmSharedMain/kotlin/com/cosmotune/ui/chrome/CosmoProfileDrawer.kt")
        assertTrue("StatusBarIcons(" in src && "groundWantsDarkGlyphs(com.cosmotune.ui.orbit.orbitSheetGround(" in src, "the glyphs follow the drawer's ground")
    }
}
