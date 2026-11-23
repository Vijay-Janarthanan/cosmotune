package com.cosmotune.music.data.innertube

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * A long playlist's later page as YouTube Music sends it now: rows appended by an `onResponseReceivedActions` action, with no
 * `continuationContents`. Read as nothing, the playlist stopped after its first 100 songs and Reorder showed only those (adapted
 * from the upstream test, GPL-3.0).
 */
class PlaylistContinuationTest {
    private fun row(videoId: String, setVideoId: String) = """
        { "musicResponsiveListItemRenderer": {
          "playlistItemData": { "videoId": "$videoId", "playlistSetVideoId": "$setVideoId" },
          "flexColumns": [
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Song $videoId" } ] } } },
            { "musicResponsiveListItemFlexColumnRenderer": { "text": { "runs": [ { "text": "Artist" } ] } } }
          ]
        } }"""

    private fun appendedPage(rows: List<String>, next: String?) = Json.parseToJsonElement(
        """
        { "onResponseReceivedActions": [ { "appendContinuationItemsAction": { "continuationItems": [
          ${(rows + listOfNotNull(next?.let { """{ "continuationItemRenderer": { "continuationEndpoint": { "continuationCommand": { "token": "$it" } } } }""" })).joinToString(",")}
        ] } } ] }
        """,
    )

    @Test
    fun anAppendedPageReadsAsThePlaylistsNextRows() {
        val page = assertNotNull(InnertubeParser.parsePlaylistShelf(appendedPage(listOf(row("aaaaaaaaaaa", "SET1"), row("bbbbbbbbbbb", "SET2")), "PAGE3")))
        assertEquals(listOf("SET1", "SET2"), page.songs.map { it.setVideoId })
        assertEquals("PAGE3", page.continuation)
    }

    @Test
    fun aLongPlaylistIsReadToItsEndSoAllOfItCanBeReordered() {
        // 250 songs over three pages: the first under the page's own shelf, the rest appended.
        val ids = (0 until 250).map { "v" + it.toString().padStart(10, '0') }
        val pages = ids.chunked(100).mapIndexed { index, chunk ->
            appendedPage(chunk.map { row(it, "S$it") }, if (index < 2) "P${index + 1}" else null)
        }
        val read = pages.flatMap { assertNotNull(InnertubeParser.parsePlaylistShelf(it)).songs }
        assertEquals(250, read.size)
        assertEquals(ids, read.map { it.videoId })
        assertEquals(null, InnertubeParser.parsePlaylistShelf(pages.last())?.continuation)
    }
}
