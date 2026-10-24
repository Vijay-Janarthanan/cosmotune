package com.cosmotune.music.playback

import com.cosmotune.music.data.model.BrowseType
import com.cosmotune.music.data.model.DetailPage
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BC-05's UI entry point: which page taps start a bounded context, from which snapshot, and the Cast and party guards. */
class ReleasePagePlaybackTest {
    private fun song(id: String, slot: String? = null) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, setVideoId = slot)
    private fun page(type: BrowseType, songs: List<Song>, continuation: String? = "page-2") =
        DetailPage(browseId = "MPREb_release", title = "Release", subtitle = "", thumbnailUrl = null, songs = UiState.Success(songs), type = type, continuation = continuation)

    private val rows = listOf(song("a"), song("b"), song("c"))

    @Test fun anAlbumRowStartsABoundedContextFromTheNewestSnapshotWithItsContinuation() {
        val shown = page(BrowseType.ALBUM, rows)
        val grown = page(BrowseType.ALBUM, rows + song("d"), continuation = "page-3")
        val asked = mutableListOf<String>()
        val plan = ReleasePagePlayback.plan(shown, rows, 1, casting = false, latest = { asked += it; grown })!!
        assertTrue(plan.bounded)
        assertEquals(listOf("MPREb_release"), asked, "the page's newest snapshot is taken once")
        assertEquals(listOf("a", "b", "c", "d"), plan.songs.map { it.videoId })
        assertEquals(1, plan.index)
        assertEquals("page-3", plan.continuationToken)
    }

    @Test fun aPlaylistTapInAFilteredListFindsItsRowInTheWholeSnapshot() {
        val all = listOf(song("x", "s1"), song("y", "s2"), song("x", "s3"))
        val shown = page(BrowseType.PLAYLIST, all)
        // The filter left the second copy of "x" alone on screen.
        val plan = ReleasePagePlayback.plan(shown, listOf(all[2]), 0, casting = false, latest = { shown })!!
        assertTrue(plan.bounded)
        assertEquals(3, plan.songs.size)
        assertEquals(2, plan.index, "the slot that was tapped, not the first copy of the id")
    }

    @Test fun withoutASnapshotThePageItselfIsTheContext() {
        val plan = ReleasePagePlayback.plan(page(BrowseType.ALBUM, rows), rows, 2, casting = false, latest = { null })!!
        assertTrue(plan.bounded)
        assertEquals(rows, plan.songs)
        assertEquals("page-2", plan.continuationToken)
    }

    @Test fun aCastSessionAnArtistPageAndASuggestedRowPlayTheWholeList() {
        var asked = 0
        val latest: (String) -> DetailPage? = { asked++; null }
        assertFalse(ReleasePagePlayback.plan(page(BrowseType.ALBUM, rows), rows, 0, casting = true, latest = latest)!!.bounded)
        assertFalse(ReleasePagePlayback.plan(page(BrowseType.ARTIST, rows), rows, 0, casting = false, latest = latest)!!.bounded)
        val suggested = listOf(song("s"))
        assertFalse(ReleasePagePlayback.plan(page(BrowseType.PLAYLIST, rows), suggested, 0, casting = false, latest = latest)!!.bounded)
        assertEquals(0, asked, "only a bounded start takes the snapshot")
    }

    @Test fun theQueueIsPreparedAndAnEmptyListPlaysNothing() {
        val plan = ReleasePagePlayback.plan(page(BrowseType.ALBUM, rows), rows, 0, casting = false, latest = { null }, prepare = { it.copy(albumName = "Release") })!!
        assertTrue(plan.songs.all { it.albumName == "Release" })
        assertNull(ReleasePagePlayback.plan(page(BrowseType.ALBUM, emptyList()), emptyList(), 0, casting = false, latest = { null }))
    }

    @Test fun theRouteKeepsPartiesAndCastOffTheBoundedWindow() {
        assertEquals(ReleasePagePlayback.Route.BOUNDED, ReleasePagePlayback.route(bounded = true, inParty = false, casting = false))
        assertEquals(ReleasePagePlayback.Route.WHOLE, ReleasePagePlayback.route(bounded = true, inParty = false, casting = true), "a receiver connected after the tap")
        assertEquals(ReleasePagePlayback.Route.PARTY, ReleasePagePlayback.route(bounded = true, inParty = true, casting = false))
        assertEquals(ReleasePagePlayback.Route.WHOLE, ReleasePagePlayback.route(bounded = false, inParty = false, casting = false))
    }
}
