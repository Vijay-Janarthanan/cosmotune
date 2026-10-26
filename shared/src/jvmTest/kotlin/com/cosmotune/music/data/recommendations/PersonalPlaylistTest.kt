package com.cosmotune.music.data.recommendations

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class PersonalPlaylistTest {
    @Test fun aSmallOfflineLibraryGivesRealDistinctSongsWithoutInventingOrFetching() = runBlocking {
        fun song(id: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null)
        val seed = song("seed")
        val result = LocalRecommendations.playlist(seed, listOf(seed, song("one"), song("one"), song("two")),
            limit = 30, discover = false) { error("Offline preview must not search") }
        assertEquals(listOf("seed", "one", "two"), result.map { it.videoId })
    }
}
