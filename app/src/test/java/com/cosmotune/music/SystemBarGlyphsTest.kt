package com.cosmotune.music

import com.cosmotune.music.ui.theme.themeSystemBarGlyphs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The status and navigation bar glyphs follow the ground under them: the theme's, or the player's and a Replay story's own. */
class SystemBarGlyphsTest {
    @Test fun pagesOnTheThemeGroundGetDarkGlyphsInLightAndLightGlyphsInDark() {
        assertEquals(true, themeSystemBarGlyphs(darkTheme = false, playerOpen = false, storyOpen = false))
        assertEquals(false, themeSystemBarGlyphs(darkTheme = true, playerOpen = false, storyOpen = false))
    }

    @Test fun thePlayerAndAReplayStorySetTheirOwnSoTheThemeStepsAside() {
        for (dark in listOf(false, true)) {
            assertNull(themeSystemBarGlyphs(dark, playerOpen = true, storyOpen = false))
            assertNull(themeSystemBarGlyphs(dark, playerOpen = false, storyOpen = true))
        }
    }

    @Test fun replaysPageIsNotForcedToLightGlyphs() {
        val main = File("src/main/java/com/cosmotune/music/MainActivity.kt").readText()
        val line = main.lines().first { "themeSystemBarGlyphs(" in it && "fun " !in it }
        assertFalse("Replay's page follows the theme: $line", "showReplay" in line)
        assertFalse("no second, unconditional caller", Regex("""\n\s*SystemBarIcons\(dark = !darkTheme""").containsMatchIn(main))
    }

    @Test fun aReplayStorySetsTheGlyphsFromItsCardColour() {
        val stories = File("src/main/java/com/cosmotune/music/ui/replay/ReplayStories.kt").readText()
        assertTrue("SystemBarGlyphsOn(ground)" in stories)
    }
}
