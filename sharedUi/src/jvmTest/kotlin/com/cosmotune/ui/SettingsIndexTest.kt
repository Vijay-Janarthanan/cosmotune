package com.cosmotune.ui

import com.cosmotune.design.Contrast
import com.cosmotune.ui.settings.SettingsCategory
import com.cosmotune.ui.settings.SettingsCollector
import com.cosmotune.ui.settings.SettingsIndex
import com.cosmotune.ui.settings.SettingsItem
import com.cosmotune.ui.settings.tile
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The settings search: what it finds, in which order, and what it leaves out. */
class SettingsIndexTest {
    private val equaliser = SettingsItem(
        id = "PLAYBACK:Equalizer", title = "Equalizer", category = SettingsCategory.PLAYBACK,
        summary = "Tone, seven bands and balance", keywords = listOf("eq", "bass", "treble"), group = "Sound & effects",
    )
    private val crossfade = SettingsItem(
        id = "PLAYBACK:Crossfade", title = "Crossfade", category = SettingsCategory.PLAYBACK,
        summary = "Blend the end of a song into the next", keywords = listOf("fade", "gapless"), group = "Crossfade & Automix",
    )
    private val automix = SettingsItem(
        id = "PLAYBACK:Automix", title = "Automix [BETA]", category = SettingsCategory.PLAYBACK,
        keywords = listOf("crossfade", "smart fade", "mix"), group = "Crossfade & Automix",
    )
    private val theme = SettingsItem(id = "APPEARANCE:Theme", title = "Theme", category = SettingsCategory.APPEARANCE, keywords = listOf("dark mode", "light mode"))
    private val canvas = SettingsItem(
        id = "LYRICS:Animated cover art", title = "Animated cover art", category = SettingsCategory.LYRICS,
        summary = "Plays the motion cover when a song has one", keywords = listOf("canvas", "video", "spotify"),
    )
    private val cafe = SettingsItem(id = "ABOUT:Café", title = "Café mode", category = SettingsCategory.ABOUT)
    private val all = listOf(equaliser, crossfade, automix, theme, canvas, cafe)

    @Test fun aBlankQueryFindsNothing() {
        assertEquals(emptyList(), SettingsIndex.search(all, ""))
        assertEquals(emptyList(), SettingsIndex.search(all, "   "))
        assertFalse(SettingsIndex.matches(theme, ""))
    }

    @Test fun titlesKeywordsSummariesAndGroupsAreAllSearched() {
        assertEquals(listOf(equaliser), SettingsIndex.search(all, "equal"), "the title")
        assertEquals(listOf(equaliser), SettingsIndex.search(all, "bass"), "a keyword")
        assertEquals(listOf(equaliser), SettingsIndex.search(all, "seven bands"), "the summary")
        assertEquals(setOf(crossfade, automix), SettingsIndex.search(all, "automix").toSet(), "the group's name counts for its rows")
        assertEquals(listOf(theme), SettingsIndex.search(all, "dark"))
    }

    @Test fun caseAndAccentsDoNotCount() {
        assertEquals(listOf(theme), SettingsIndex.search(all, "THEME"))
        assertEquals(listOf(cafe), SettingsIndex.search(all, "cafe"))
        assertEquals(listOf(cafe), SettingsIndex.search(all, "CAFÉ"))
    }

    @Test fun everyWordMustBeFoundSomewhere() {
        assertEquals(listOf(canvas), SettingsIndex.search(all, "spotify cover"))
        assertEquals(emptyList(), SettingsIndex.search(all, "spotify bass"))
    }

    @Test fun aTitleMatchRanksAboveAKeywordMatch() {
        // "Crossfade" is the title of one and a keyword of the other
        val results = SettingsIndex.search(all, "crossfade")
        assertEquals(listOf(crossfade, automix), results)
        // a title that is the query outranks one that only starts with it
        val exact = SettingsItem(id = "x:Mix", title = "Mix", category = SettingsCategory.PLAYBACK)
        val longer = SettingsItem(id = "x:Mix tape", title = "Mix tape", category = SettingsCategory.PLAYBACK)
        assertEquals(listOf(exact, longer), SettingsIndex.search(listOf(longer, exact), "mix"))
    }

    @Test fun tiesKeepTheOrderOfThePages() {
        val first = SettingsItem(id = "a", title = "Show lyrics", category = SettingsCategory.LYRICS)
        val second = SettingsItem(id = "b", title = "Show stats", category = SettingsCategory.ABOUT)
        assertEquals(listOf(first, second), SettingsIndex.search(listOf(first, second), "show"))
    }

    @Test fun eachIdOnceAndAtMostTheLimit() {
        val twice = listOf(theme, theme.copy(summary = "again"))
        assertEquals(1, SettingsIndex.search(twice, "theme").size)
        val many = (1..100).map { SettingsItem(id = "row$it", title = "Row $it", category = SettingsCategory.ABOUT) }
        assertEquals(80, SettingsIndex.search(many, "row").size)
        assertEquals(5, SettingsIndex.search(many, "row", limit = 5).size)
    }

    @Test fun theCollectorKeepsTheLatestEntryOfARowInItsFirstPlace() {
        val collector = SettingsCollector()
        collector.add(theme)
        collector.add(canvas)
        collector.add(theme.copy(summary = "Dark"))
        assertEquals(listOf("APPEARANCE:Theme", "LYRICS:Animated cover art"), collector.items.map { it.id })
        assertEquals("Dark", collector.items.first().summary)
    }

    @Test fun theCategoryTilesKeepAWhiteIconReadableAndDiffer() {
        val white = 0xFFFFFFFF.toInt()
        for (page in SettingsCategory.pages) {
            // The kit's tile tints: the light step carries text-grade contrast, the dark step at least the 3:1 a glyph needs.
            assertTrue(Contrast.ratio(white, page.tile.fill(dark = false)) >= 4.5, "${page.name}'s light tile carries a white icon")
            assertTrue(Contrast.ratio(white, page.tile.fill(dark = true)) >= 3.0, "${page.name}'s dark tile carries a white icon")
        }
        // Neighbouring categories (the desktop's list, the settings home's cards) never share a colour.
        for ((above, below) in SettingsCategory.pages.zipWithNext()) {
            assertTrue(above.tile != below.tile, "${above.name} and ${below.name} have their own colours")
        }
        assertTrue(SettingsCategory.pages.map { it.tile }.distinct().size >= 6, "the tiles use the palette, not one colour")
    }
}
