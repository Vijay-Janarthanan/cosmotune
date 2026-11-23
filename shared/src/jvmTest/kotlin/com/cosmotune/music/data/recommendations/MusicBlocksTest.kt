package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MusicBlocksTest {
    @AfterTest fun clear() = MusicBlocks.update(emptySet(), hideExplicit = false)

    private fun song(artist: String, explicit: Boolean? = null, refs: List<String> = emptyList()) =
        Song("id", "Title", artist, null, artists = refs.map { ArtistRef(it) }, isExplicit = explicit)

    @Test fun nothingIsBlockedUntilTheListenerSaysSo() {
        assertTrue(MusicBlocks.allows(song("Anyone", explicit = true)))
    }

    @Test fun aBlockedArtistIsLeftOutEvenAsAGuest() {
        MusicBlocks.update(setOf("Beyoncé"), hideExplicit = false)
        assertFalse(MusicBlocks.allows(song("BEYONCE")))
        assertFalse(MusicBlocks.allows(song("Jay-Z feat. Beyonce")))
        assertFalse(MusicBlocks.allows(song("Someone", refs = listOf("Jay-Z", "Beyoncé"))))
        assertTrue(MusicBlocks.allows(song("Jay-Z")))
    }

    @Test fun explicitSongsGoOnlyWhenHidden() {
        MusicBlocks.update(emptySet(), hideExplicit = true)
        assertFalse(MusicBlocks.allows(song("A", explicit = true)))
        assertTrue(MusicBlocks.allows(song("A", explicit = null)))
        assertTrue(MusicBlocks.allows(song("A", explicit = false)))
    }

    @Test fun bylinesSplitAtTheUsualJoins() {
        assertEquals(listOf("A", "B", "C", "D"), MusicBlocks.artistsOf(song("A, B & C ft. D")))
        assertEquals(listOf("Simon & Garfunkel"), MusicBlocks.artistsOf(song("x", refs = listOf("Simon & Garfunkel"))))
    }
}
