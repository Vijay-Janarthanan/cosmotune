package com.cosmotune.music

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.spotify.LocalPlaylist
import com.cosmotune.music.data.spotify.LocalPlaylistStore
import org.junit.Assert.assertEquals
import org.junit.Test

/** A playlist imported onto the device loses a song from its song menu, by its own id or its page id (upstream 1.10). */
class LocalPlaylistRemoveSongTest {
    private fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)
    private val lists = listOf(
        LocalPlaylist("sp_local_1", "Road trip", listOf(song("a"), song("b"), song("c"))),
        LocalPlaylist("sp_local_2", "Gym", listOf(song("a"))),
    )

    @Test fun theSongLeavesOnlyThatPlaylist() {
        val after = LocalPlaylistStore.removing(lists, "sp_local_1", "b")
        assertEquals(listOf("a", "c"), after[0].songs.map { it.videoId })
        assertEquals(listOf("a"), after[1].songs.map { it.videoId })
    }

    @Test fun thePagesOwnIdNamesThePlaylistToo() {
        val after = LocalPlaylistStore.removing(lists, "local:playlist:sp_local_2", "a")
        assertEquals(emptyList<String>(), after[1].songs.map { it.videoId })
        assertEquals(3, after[0].songs.size)
    }

    @Test fun aSongNotInItChangesNothing() = assertEquals(lists, LocalPlaylistStore.removing(lists, "sp_local_2", "zzz"))
}
