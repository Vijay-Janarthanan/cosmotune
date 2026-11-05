package com.cosmotune.music.data.model

import kotlin.test.*

class MusicCollectionPagesTest {
    private fun song(id: String, entry: String? = null) = Song(id, id, "Artist", null, setVideoId = entry)
    @Test fun aRecordingMayRepeatButTheSameProviderOccurrenceIsNotAppendedTwice() {
        val a = song("same", "slot-1")
        val b = song("same", "slot-2")
        val result = MusicCollectionPages.append(listOf(a), listOf(a, b), "page-2", "page-3", emptySet())
        assertEquals(listOf(a, b), result.songs)
        assertEquals("page-3", result.continuation)
        assertNull(result.error)
    }
    @Test fun sourcesWithoutEntryIdsDoNotLoseLegitimateRepeatedTracks() {
        val a = song("same")
        assertEquals(listOf(a, a, a), MusicCollectionPages.append(listOf(a), listOf(a, a), "p2", null, emptySet()).songs)
    }
    @Test fun repeatedTokensAndPageLimitsStopWithoutLosingAlreadyLoadedSongs() {
        val songs = listOf(song("a"))
        val loop = MusicCollectionPages.append(songs, listOf(song("b")), "p2", "p1", setOf("p1"))
        assertEquals(listOf("a", "b"), loop.songs.map { it.videoId })
        assertNull(loop.continuation); assertNotNull(loop.error)
        val repeated = MusicCollectionPages.append(songs, listOf(song("b")), "p1", "p2", setOf("p1"))
        assertEquals(songs, repeated.songs); assertNull(repeated.continuation)
    }
}
