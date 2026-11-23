package com.cosmotune.music.data.model

import kotlin.test.*
import kotlinx.coroutines.launch

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

class MusicCollectionPagesLoadAllTest {
    private fun song(id: String) = Song(id, id, "Artist", null)
    private val pages = mapOf(
        "p2" to (listOf(song("c"), song("d")) to "p3"),
        "p3" to (listOf(song("e")) to null),
    )

    @Test fun walksEveryPageAndReportsTheCountAfterEach() = kotlinx.coroutines.runBlocking<Unit> {
        val counts = mutableListOf<Int>()
        val all = MusicCollectionPages.loadAll(listOf(song("a"), song("b")), "p2", emptySet(),
            fetch = { Result.success(pages.getValue(it)) }, onProgress = { counts += it })
        assertEquals(listOf("a", "b", "c", "d", "e"), all.songs.map { it.videoId })
        assertNull(all.continuation); assertNull(all.error)
        assertEquals(listOf(4, 5), counts)
    }

    @Test fun nothingMoreToLoadReturnsTheSongsAsTheyAre() = kotlinx.coroutines.runBlocking<Unit> {
        val all = MusicCollectionPages.loadAll(listOf(song("a")), null, emptySet(), fetch = { error("not asked") })
        assertEquals(listOf("a"), all.songs.map { it.videoId }); assertNull(all.error)
    }

    @Test fun aFailedPageKeepsWhatArrivedAndTheToken() = kotlinx.coroutines.runBlocking<Unit> {
        val all = MusicCollectionPages.loadAll(listOf(song("a")), "p2", emptySet(), fetch = { token ->
            if (token == "p2") Result.success(pages.getValue(token)) else Result.failure(java.io.IOException("offline"))
        })
        assertEquals(listOf("a", "c", "d"), all.songs.map { it.videoId })
        assertEquals("p3", all.continuation); assertNotNull(all.error)
    }

    @Test fun aLoopingSourceStops() = kotlinx.coroutines.runBlocking<Unit> {
        val all = MusicCollectionPages.loadAll(listOf(song("a")), "p2", emptySet(),
            fetch = { Result.success(listOf(song("x")) to "p2") })
        assertEquals(listOf("a", "x"), all.songs.map { it.videoId })
        assertNull(all.continuation); assertNotNull(all.error)
    }

    @Test fun cancellingStopsTheWalk() = kotlinx.coroutines.runBlocking<Unit> {
        var asked = 0
        val job = launch {
            MusicCollectionPages.loadAll(listOf(song("a")), "p", emptySet(), fetch = {
                asked++
                kotlinx.coroutines.delay(10)
                Result.success(listOf(song("n$asked")) to "p$asked")
            })
        }
        kotlinx.coroutines.delay(35)
        job.cancel(); job.join()
        val stoppedAt = asked
        kotlinx.coroutines.delay(30)
        assertEquals(stoppedAt, asked)
        assertTrue(job.isCancelled)
    }
}
