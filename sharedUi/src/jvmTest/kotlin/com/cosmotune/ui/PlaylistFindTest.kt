package com.cosmotune.ui

import com.cosmotune.ui.sheets.findPlaylists
import kotlin.test.Test
import kotlin.test.assertEquals

/** Add to playlist's "Find a playlist": every word, in any order, ignoring case and accents (upstream 1.10's rule, GPL-3.0). */
class PlaylistFindTest {
    private val names = listOf("Nepali Rock", "Road Trip", "rock classics", "Beyoncé mix", "தமிழ் Hits")
    private fun find(query: String) = findPlaylists(names, query) { it }

    @Test fun everyPlaylistForABlankSearch() = assertEquals(names, find("  "))

    @Test fun everyWordInAnyOrderAndCase() {
        assertEquals(listOf("Nepali Rock", "rock classics"), find("ROCK"))
        assertEquals(listOf("Nepali Rock"), find("rock nep"))
        assertEquals(emptyList(), find("jazz"))
    }

    @Test fun accentsDoNotMatter() {
        assertEquals(listOf("Beyoncé mix"), find("beyonce"))
        assertEquals(listOf("Beyoncé mix"), find("BEYONCÉ"))
    }

    @Test fun otherScriptsAreFoundAsTyped() = assertEquals(listOf("தமிழ் Hits"), find("தமிழ்"))
}
