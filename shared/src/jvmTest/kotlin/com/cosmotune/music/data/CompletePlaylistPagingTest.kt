package com.cosmotune.music.data

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Test
import kotlin.test.*

class CompletePlaylistPagingTest {
    private fun page(vararg ids: String, next: String? = null) = YtMusicRepository.SongPage(ids.map { Song(it, it, "Singer", null) }, next)
    @Test fun fullWalkReadsLaterPagesAndDeduplicatesRecordingsWithoutBorrowingSuggestions() = runBlocking<Unit> {
        val visited = mutableListOf<String>()
        val rows = YtMusicRepository.collectCompleteSongPages(page("first", next = "page2"), { token ->
            visited += token
            Result.success(if (token == "page2") page("first", "middle", next = "page3") else page("last"))
        })
        assertEquals(listOf("page2", "page3"), visited)
        assertEquals(listOf("first", "middle", "last"), rows.map { it.videoId })
    }
    @Test fun importingKeepsRepeatedSongsInTheCloudPlaylistSnapshot() = runBlocking<Unit> {
        val rows = YtMusicRepository.collectCompleteSongPages(
            page("first", "second", next = "more"), { Result.success(page("first", "third")) }, keepRepeated = true,
        )
        val saved = com.cosmotune.cloud.SyncPlaylists.data("Shared playlist", rows)
        assertEquals("Shared playlist", com.cosmotune.cloud.SyncPlaylists.title(saved))
        assertEquals(listOf("first", "second", "first", "third"), com.cosmotune.cloud.SyncPlaylists.songs(saved).map { it.videoId })
    }
    @Test fun aFailedContinuationRepeatedTokenOrPageCapNeverReturnsAPartialSuccess() = runBlocking<Unit> {
        assertFailsWith<IllegalStateException> { YtMusicRepository.collectCompleteSongPages(page("first", next = "page2"), { Result.failure(IllegalStateException("failed")) }) }
        assertFailsWith<IllegalStateException> { YtMusicRepository.collectCompleteSongPages(page("first", next = "same"), { Result.success(page("other", next = "same")) }) }
        assertFailsWith<IllegalStateException> { YtMusicRepository.collectCompleteSongPages(page("first", next = "page2"), { Result.success(page("middle", next = "page3")) }, maxPages = 1) }
    }
}
