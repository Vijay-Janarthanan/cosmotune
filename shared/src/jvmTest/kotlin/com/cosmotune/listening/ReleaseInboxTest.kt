package com.cosmotune.listening

import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import org.junit.Test
import kotlin.test.*

class ReleaseInboxTest {
    @Test fun onlyStaleOrUncheckedArtistsAreDueOldestFirst() {
        val day = 24L * 60 * 60 * 1000
        val now = 100 * day
        val artists = listOf(WatchedArtist("UCfresh000000", "Fresh", checkedAt = now - day / 2), WatchedArtist("UCold0000000", "Old", checkedAt = now - 3 * day),
            WatchedArtist("UCnever00000", "Never"), WatchedArtist("UCday0000000", "Exactly a day", checkedAt = now - day))
        assertEquals(listOf("Never", "Old", "Exactly a day"), dueForReleaseCheck(artists, now).map { it.name })
    }

    @Test fun libraryArtistsUseCatalogueIdentitiesCountsAndSkipFollowed() {
        val songs = listOf(
            Song("1", "A", "Ilaiyaraaja", null, artistId = "UCilaiyaraaja01"),
            Song("2", "B", "Ilaiyaraaja", null, artistId = "UCilaiyaraaja01"),
            Song("2", "B again", "Ilaiyaraaja", null, artistId = "UCilaiyaraaja01"), // same recording counts once
            Song("3", "C", "Duet", null, artists = listOf(ArtistRef("Chitra", "UCchitra000001"), ArtistRef("Ilaiyaraaja", "UCilaiyaraaja01"))),
            Song("4", "D", "Local band", null, artistId = "local:artist:9"),
            Song("5", "E", "Followed", null, artistId = "UCfollowed0001"),
        )
        val result = libraryArtists(songs, listOf(WatchedArtist("UCfollowed0001", "Followed")))
        assertEquals(listOf("Ilaiyaraaja" to 3, "Chitra" to 1), result.map { it.first.name to it.second })
        assertEquals(1, libraryArtists(songs, emptyList(), limit = 1).size)
    }
}
