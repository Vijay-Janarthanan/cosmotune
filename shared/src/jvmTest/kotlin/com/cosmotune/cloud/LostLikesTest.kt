package com.cosmotune.cloud

import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

class LostLikesTest {
    @Test fun onlyYouTubeSongsTheAccountNoLongerListsAreLost() {
        val kept = mapOf(
            "aaaaaaaaaaa" to SyncLikes.data("First", "Singer", null),
            "bbbbbbbbbbb" to SyncLikes.data("Second", "Band", null),
            "ccccccccccc" to SyncLikes.data(null, null, null),
            "local:song.flac" to SyncLikes.data("File", "", null),
        )
        val lost = LostLikes.missing(kept, onYouTube = setOf("bbbbbbbbbbb"))
        assertEquals(listOf(LostLikes.Lost("aaaaaaaaaaa", "First", "Singer"), LostLikes.Lost("ccccccccccc", "A liked song", "")), lost)
        assertEquals("2 songs you liked are missing from YouTube's Liked music", LostLikes.summary(2, 4))
        assertEquals("Every like you made in Cosmotune is on YouTube (4 checked)", LostLikes.summary(0, 4))
    }

    @Test fun restoringCountsWhatYouTubeAccepted() = runBlocking {
        val lost = listOf(LostLikes.Lost("a", "A", ""), LostLikes.Lost("b", "B", ""), LostLikes.Lost("c", "C", ""))
        val progress = mutableListOf<Int>()
        val accepted = LostLikes.restore(lost, like = { it.videoId != "b" && (it.videoId != "c" || error("offline")) }, onProgress = { progress += it })
        assertEquals(1, accepted)
        assertEquals(listOf(1, 2, 3), progress)
    }
}
