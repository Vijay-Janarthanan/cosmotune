package com.cosmotune.music.data.covers

import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Which cover a playlist shows: the one picked for it, or the one YouTube Music makes. */
class PlaylistCoverRulesTest {
    private val picked = mapOf("PLmine" to "file:/covers/PLmine-ab.jpg")

    @Test fun aPageIdAndAPlaylistIdNameTheSamePlaylist() {
        assertEquals("PLmine", PlaylistCoverRules.key("VLPLmine"))
        assertEquals("PLmine", PlaylistCoverRules.key("PLmine"))
        assertNull(PlaylistCoverRules.key(null))
        assertNull(PlaylistCoverRules.key(""))
        assertNull(PlaylistCoverRules.key("VL"))
        // Only YouTube's own playlist ids: never a device playlist, a file or anything a file name could not hold.
        assertNull(PlaylistCoverRules.key("local:downloads"))
        assertNull(PlaylistCoverRules.key("../x"))
        assertNull(PlaylistCoverRules.key("P".repeat(201)))
    }

    @Test fun thePickedCoverWinsAndTheAutomaticOneIsKeptOtherwise() {
        assertEquals("file:/covers/PLmine-ab.jpg", PlaylistCoverRules.coverFor(picked, "VLPLmine", "https://yt/auto.jpg"))
        assertEquals("https://yt/other.jpg", PlaylistCoverRules.coverFor(picked, "VLPLother", "https://yt/other.jpg"))
        assertEquals("https://yt/auto.jpg", PlaylistCoverRules.coverFor(picked, null, "https://yt/auto.jpg"))
        assertNull(PlaylistCoverRules.coverFor(emptyMap(), "VLPLmine", null))
        assertTrue(PlaylistCoverRules.hasCustom(picked, "VLPLmine"))
        assertFalse(PlaylistCoverRules.hasCustom(picked, "VLPLother"))
    }

    @Test fun libraryCardsOfAPlaylistShowThePickedCover() {
        val shelves = listOf(
            HomeShelf(
                "Playlists",
                listOf(
                    ShelfItem("Mine", "Playlist", "https://yt/auto.jpg", null, "VLPLmine"),
                    ShelfItem("Other", "Playlist", "https://yt/other.jpg", null, "VLPLother"),
                    // A song card that happens to carry the same id is a song, not the playlist.
                    ShelfItem("Song", "Band", "https://yt/song.jpg", "PLmine", "VLPLmine"),
                ),
            ),
        )
        val shown = PlaylistCoverRules.shelvesWithCovers(shelves, picked)
        assertEquals(listOf("file:/covers/PLmine-ab.jpg", "https://yt/other.jpg", "https://yt/song.jpg"), shown.single().items.map { it.thumbnailUrl })
        assertTrue(PlaylistCoverRules.shelvesWithCovers(shelves, emptyMap()) === shelves, "nothing picked: the same list, nothing redrawn")
    }

    @Test fun aPictureKeptOnThisDeviceNeverLeavesThePage() {
        assertEquals(null, PlaylistCoverRules.portable("file:/data/covers/PLmine-ab.jpg"))
        assertEquals("https://yt/auto.jpg", PlaylistCoverRules.portable("https://yt/auto.jpg"))
        assertEquals(null, PlaylistCoverRules.portable(null))
    }

    @Test fun thePickerOffersEachSongCoverOnce() {
        val songs = listOf(
            Song("a", "A", "Band", "https://yt/a.jpg"),
            Song("b", "B", "Band", "https://yt/a.jpg"),
            Song("c", "C", "Band", " "),
            Song("d", "D", "Band", null),
            Song("e", "E", "Band", "https://yt/e.jpg"),
        )
        assertEquals(listOf("https://yt/a.jpg", "https://yt/e.jpg"), PlaylistCoverRules.songChoices(songs))
        assertEquals(1, PlaylistCoverRules.songChoices(songs, limit = 1).size)
    }
}
