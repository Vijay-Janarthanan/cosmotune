package com.cosmotune.ui.listening

import com.cosmotune.listening.ListeningLibrary
import com.cosmotune.music.data.model.Song
import com.cosmotune.remote.SyncPlaylist
import com.cosmotune.remote.SyncRecord
import com.cosmotune.remote.SyncStamp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LibraryExchangeTest {
    private fun song(id: String, path: String? = null) = Song(id, "Title $id", "Artist", null, localPath = path)
    private fun record(playlist: SyncPlaylist, deleted: Boolean = false) =
        if (deleted) SyncRecord("playlist:${playlist.id}", SyncStamp(1, "d"), deleted = true)
        else SyncRecord("playlist:${playlist.id}", SyncStamp(1, "d"), playlist = playlist)

    @Test fun likesAndRecentPlaysBecomeTwoSharedListsWithoutLocalFiles() {
        val library = ListeningLibrary(
            songs = listOf(song("aaaaaaaaaaa"), song("bbbbbbbbbbb"), song("local:1", path = "/music/x.mp3"), song("aaaaaaaaaaa")),
            likedIds = setOf("aaaaaaaaaaa", "local:1"), recentIds = setOf("bbbbbbbbbbb"),
        )
        val lists = LibraryExchange.playlists(library, "phone")
        assertEquals(listOf("cosmotune-liked-phone", "cosmotune-recent-phone"), lists.map { it.id })
        assertEquals(listOf("aaaaaaaaaaa"), lists[0].tracks.map { it.id })
        assertEquals(listOf("bbbbbbbbbbb"), lists[1].tracks.map { it.id })
        lists.forEach { com.cosmotune.remote.LibrarySyncStore.validate(record(it)) }
        assertTrue(LibraryExchange.playlists(ListeningLibrary(), "phone").isEmpty())
    }

    @Test fun receivedLikesAndPlaysAreKeptApartFromOrdinaryPlaylists() {
        val phone = LibraryExchange.playlists(ListeningLibrary(songs = listOf(song("aaaaaaaaaaa")),
            likedIds = setOf("aaaaaaaaaaa"), recentIds = setOf("aaaaaaaaaaa")), "phone")
        val desk = LibraryExchange.playlists(ListeningLibrary(songs = listOf(song("bbbbbbbbbbb")),
            likedIds = setOf("bbbbbbbbbbb")), "desk")
        val mine = SyncPlaylist("road", "Road trip", emptyList())
        val split = LibraryExchange.split((phone + desk).map { record(it) } + record(mine))
        assertEquals(listOf("aaaaaaaaaaa", "bbbbbbbbbbb"), split.liked.map { it.videoId })
        assertEquals(listOf("aaaaaaaaaaa"), split.recent.map { it.videoId })
        assertEquals("https://i.ytimg.com/vi/aaaaaaaaaaa/hqdefault.jpg", split.liked.first().thumbnailUrl)
        assertEquals(listOf("playlist:road"), split.playlists.map { it.id })
    }

    @Test fun aRemovedLikesListUnlikesNothing() {
        val split = LibraryExchange.split(listOf(record(SyncPlaylist(LibraryExchange.LIKED + "phone", "Liked songs", emptyList()), deleted = true)))
        assertTrue(split.liked.isEmpty()); assertTrue(split.playlists.isEmpty())
    }

    @Test fun otherDevicesListsAndThisDevicesWhileLoadingAreNeverRecordedAsRemoved() {
        val theirs = SyncPlaylist(LibraryExchange.LIKED + "desk", "Liked songs", emptyList())
        val minePlays = SyncPlaylist(LibraryExchange.RECENT + "phone", "Recently played", emptyList())
        val road = SyncPlaylist("road", "Road trip", emptyList())
        val library = ListeningLibrary(songs = listOf(song("aaaaaaaaaaa")), likedIds = setOf("aaaaaaaaaaa"))
        val published = LibraryExchange.published(listOf(road), library, "phone", listOf(record(theirs), record(minePlays)))
        assertEquals(listOf("road", "cosmotune-liked-phone", "cosmotune-liked-desk", "cosmotune-recent-phone"), published.map { it.id })
    }

    @Test fun historyFromElsewhereJoinsAfterThisDevicesOwn() {
        val merged = LibraryExchange.mergeHistory(listOf(song("a"), song("b")), listOf(song("b"), song("c")))
        assertEquals(listOf("a", "b", "c"), merged.map { it.videoId })
    }
}
