package com.cosmotune.design

import com.music.bitchord.data.YtMusicRepository
import com.music.bitchord.data.model.HomeShelf
import com.music.bitchord.data.model.ShelfItem
import com.music.bitchord.data.settings.LibrarySort
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure rules of the new Library page: which shelf a segment shows, the liked and pinned tiles, the pin and sort orders. */
class LibrarySegmentsTest {
    private fun item(title: String, id: String? = title, art: String? = "art:$title") =
        ShelfItem(title = title, subtitle = "", thumbnailUrl = art, videoId = null, browseId = id)

    private fun shelf(title: String, vararg items: ShelfItem) = HomeShelf(title, items.toList())

    private fun titles(items: List<ShelfItem>) = items.map { it.title }

    // ---- isLiked -------------------------------------------------------------------------------------------------------

    @Test
    fun theLikedPlaylistIsVLLMOrLM() {
        assertTrue(LibrarySegments.isLiked(item("Liked", "VLLM")))
        assertTrue(LibrarySegments.isLiked(item("Liked", "LM")))
    }

    @Test
    fun anythingElseIsNotLiked() {
        assertFalse(LibrarySegments.isLiked(item("Road", "VLPL1234")))
        assertFalse(LibrarySegments.isLiked(item("No id", null)))
        assertFalse(LibrarySegments.isLiked(item("Near miss", "vllm")))
        assertFalse(LibrarySegments.isLiked(item("Near miss", "VLLM2")))
    }

    @Test
    fun theLikedIdIsTheRepositorysOwn() {
        assertEquals("VLLM", YtMusicRepository.LIKED_MUSIC)
        assertTrue(LibrarySegments.isLiked(item("Liked", YtMusicRepository.LIKED_MUSIC)))
    }

    // ---- shelfFor ------------------------------------------------------------------------------------------------------

    private val feed = listOf(
        shelf(YtMusicRepository.PLAYLISTS_SHELF, item("p1"), item("p2")),
        shelf("Albums", item("a1"), item("a2")),
        shelf("Artists", item("r1"), item("r2")),
        shelf("Subscriptions", item("s1"), item("r2")),
        shelf("Podcasts", item("c1")),
    )

    @Test
    fun playlistsIsTheShelfTitledPlaylists() {
        val found = LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, feed)
        assertEquals(YtMusicRepository.PLAYLISTS_SHELF, found?.title)
        assertEquals(listOf("p1", "p2"), titles(found!!.items))
    }

    @Test
    fun albumsIsTheShelfTitledAlbums() {
        assertEquals("Albums", LibrarySegments.ALBUMS_SHELF)
        assertEquals(listOf("a1", "a2"), titles(LibrarySegments.shelfFor(LibrarySegment.ALBUMS, feed)!!.items))
    }

    @Test
    fun artistsAreTheArtistsAndSubscriptionsShelvesJoinedWithoutRepeats() {
        val found = LibrarySegments.shelfFor(LibrarySegment.ARTISTS, feed)!!
        assertEquals(LibrarySegments.ARTISTS_SHELF, found.title)
        // r2 is in both shelves and is listed once, where it first appears
        assertEquals(listOf("r1", "r2", "s1"), titles(found.items))
    }

    @Test
    fun anArtistWithoutAnIdIsNeverTakenForARepeat() {
        val noIds = listOf(shelf("Artists", item("x", null), item("y", null)), shelf("Subscriptions", item("z", null)))
        assertEquals(listOf("x", "y", "z"), titles(LibrarySegments.shelfFor(LibrarySegment.ARTISTS, noIds)!!.items))
    }

    @Test
    fun artistsWorkWithOnlyOneOfTheTwoShelves() {
        val onlySubscriptions = listOf(shelf("Subscriptions", item("s1")))
        assertEquals(listOf("s1"), titles(LibrarySegments.shelfFor(LibrarySegment.ARTISTS, onlySubscriptions)!!.items))
    }

    @Test
    fun aSegmentWithoutItsShelfIsNull() {
        assertNull(LibrarySegments.shelfFor(LibrarySegment.PLAYLISTS, emptyList()))
        assertNull(LibrarySegments.shelfFor(LibrarySegment.ALBUMS, listOf(shelf("Podcasts", item("c1")))))
        assertNull(LibrarySegments.shelfFor(LibrarySegment.ARTISTS, listOf(shelf("Albums", item("a1")))))
    }

    @Test
    fun downloadedIsNotAShelfOfTheFeed() {
        assertNull(LibrarySegments.shelfFor(LibrarySegment.DOWNLOADED, feed))
    }

    @Test
    fun theFourSegmentsAreInTheOrderOfTheControl() {
        assertEquals(
            listOf(LibrarySegment.PLAYLISTS, LibrarySegment.ALBUMS, LibrarySegment.ARTISTS, LibrarySegment.DOWNLOADED),
            LibrarySegment.entries.toList(),
        )
    }

    @Test
    fun otherShelvesAreEverythingThatIsNotPlaylistsAlbumsOrArtists() {
        val others = LibrarySegments.otherShelves(feed)
        assertEquals(listOf("Podcasts"), others.map { it.title })
    }

    @Test
    fun otherShelvesKeepTheOrderOfTheFeed() {
        val shelves = listOf(shelf("Zeta", item("z")), shelf("Albums", item("a")), shelf("Alpha", item("b")), shelf("Subscriptions", item("s")))
        assertEquals(listOf("Zeta", "Alpha"), LibrarySegments.otherShelves(shelves).map { it.title })
    }

    @Test
    fun aRailIsCappedAtFiveAsTheOldPageWas() {
        assertEquals(5, LibrarySegments.RAIL_MAX_ITEMS)
    }

    // ---- pinnedFirst (the old private HomeShelf.pinnedFirst of LibraryScreen.kt) ---------------------------------------

    @Test
    fun pinnedFirstWithNothingPinnedIsTheSameList() {
        val items = listOf(item("a"), item("b"))
        assertEquals(items, LibrarySegments.pinnedFirst(items, emptyList()))
    }

    @Test
    fun pinnedFirstMovesPinsToTheFrontInTheOrderTheyWerePinned() {
        val items = listOf(item("a"), item("b"), item("c"), item("d"))
        assertEquals(listOf("d", "b", "a", "c"), titles(LibrarySegments.pinnedFirst(items, listOf("d", "b"))))
    }

    @Test
    fun pinnedFirstIgnoresAPinThatIsNotInTheList() {
        val items = listOf(item("a"), item("b"))
        assertEquals(items, LibrarySegments.pinnedFirst(items, listOf("gone")))
        assertEquals(listOf("b", "a"), titles(LibrarySegments.pinnedFirst(items, listOf("gone", "b"))))
    }

    @Test
    fun pinnedFirstIgnoresItemsWithoutAnId() {
        val items = listOf(item("a", null), item("b"))
        assertEquals(listOf("b", "a"), titles(LibrarySegments.pinnedFirst(items, listOf("b"))))
    }

    @Test
    fun aPinListedTwiceMovesTheItemOnce() {
        val items = listOf(item("a"), item("b"), item("c"))
        assertEquals(listOf("c", "a", "b"), titles(LibrarySegments.pinnedFirst(items, listOf("c", "c"))))
    }

    // ---- sorted (the old private HomeShelf.sortedForLibrary) -----------------------------------------------------------

    @Test
    fun theDefaultSortKeepsTheOrderOfTheFeed() {
        val items = listOf(item("b"), item("a"), item("c"))
        assertEquals(items, LibrarySegments.sorted(items, LibrarySort.DEFAULT))
    }

    @Test
    fun titleAscendingIgnoresCase() {
        val items = listOf(item("banana"), item("Apple"), item("cherry"), item("apricot"))
        assertEquals(listOf("Apple", "apricot", "banana", "cherry"), titles(LibrarySegments.sorted(items, LibrarySort.TITLE_ASC)))
    }

    @Test
    fun titleDescendingIsTheReverseOfAscendingIgnoringCase() {
        val items = listOf(item("banana"), item("Apple"), item("cherry"), item("apricot"))
        assertEquals(listOf("cherry", "banana", "apricot", "Apple"), titles(LibrarySegments.sorted(items, LibrarySort.TITLE_DESC)))
    }

    @Test
    fun everySortOrderIsHandled() {
        val items = listOf(item("b"), item("a"))
        for (sort in LibrarySort.entries) assertEquals(items.size, LibrarySegments.sorted(items, sort).size)
    }

    // ---- playlistLayout: the two tiles and the rows under them ---------------------------------------------------------

    @Test
    fun theLikedPlaylistTakesTheLikedTileAndIsNotListedAgain() {
        val items = listOf(item("Road", "PL1"), item("Liked songs", "VLLM"), item("Desk", "PL2"))
        val layout = LibrarySegments.playlistLayout(items, emptyList())
        assertEquals("Liked songs", layout.liked?.title)
        assertFalse(layout.rows.any { it.title == "Liked songs" })
        assertFalse(layout.mix?.title == "Liked songs")
    }

    @Test
    fun theMixTileIsTheFirstPinnedPlaylist() {
        val items = listOf(item("Road", "PL1"), item("Desk", "PL2"), item("Chai", "PL3"))
        val layout = LibrarySegments.playlistLayout(items, listOf("PL3", "PL2"))
        assertEquals("Chai", layout.mix?.title)
        // the other pin leads the rows, then the rest in the feed's order
        assertEquals(listOf("Desk", "Road"), titles(layout.rows))
    }

    @Test
    fun withNothingPinnedTheMixTileIsTheNextPlaylistWithArt() {
        val items = listOf(item("Bare", "PL0", art = null), item("Road", "PL1"), item("Desk", "PL2"))
        val layout = LibrarySegments.playlistLayout(items, emptyList())
        assertEquals("Road", layout.mix?.title)
        assertEquals(listOf("Bare", "Desk"), titles(layout.rows))
    }

    @Test
    fun noPlaylistWithArtMeansNoMixTile() {
        val items = listOf(item("Bare", "PL0", art = null))
        val layout = LibrarySegments.playlistLayout(items, emptyList())
        assertNull(layout.mix)
        assertEquals(listOf("Bare"), titles(layout.rows))
    }

    @Test
    fun aPinnedPlaylistWithoutArtStillTakesTheMixTile() {
        val items = listOf(item("Road", "PL1"), item("Bare", "PL0", art = null))
        val layout = LibrarySegments.playlistLayout(items, listOf("PL0"))
        assertEquals("Bare", layout.mix?.title)
        assertEquals(listOf("Road"), titles(layout.rows))
    }

    @Test
    fun aPinnedLikedPlaylistDoesNotTakeTheMixTile() {
        val items = listOf(item("Road", "PL1"), item("Liked songs", "VLLM"))
        val layout = LibrarySegments.playlistLayout(items, listOf("VLLM"))
        assertEquals("Liked songs", layout.liked?.title)
        assertEquals("Road", layout.mix?.title)
        assertTrue(layout.rows.isEmpty())
    }

    @Test
    fun noLikedPlaylistMeansNoLikedTile() {
        val layout = LibrarySegments.playlistLayout(listOf(item("Road", "PL1")), emptyList())
        assertNull(layout.liked)
        assertEquals("Road", layout.mix?.title)
    }

    @Test
    fun anEmptyShelfLaysOutNothing() {
        val layout = LibrarySegments.playlistLayout(emptyList(), listOf("PL1"))
        assertNull(layout.liked)
        assertNull(layout.mix)
        assertTrue(layout.rows.isEmpty())
    }

    @Test
    fun everyPlaylistIsShownExactlyOnce() {
        val items = listOf(item("A", "PL1"), item("B", "VLLM"), item("C", "PL3"), item("D", "PL4", art = null), item("E", "PL5"))
        val layout = LibrarySegments.playlistLayout(items, listOf("PL5", "PL3"))
        val shown = listOfNotNull(layout.liked, layout.mix) + layout.rows
        assertEquals(items.map { it.title }.sorted(), titles(shown).sorted())
    }

    @Test
    fun aSecondLikedItemStaysInTheRows() {
        val items = listOf(item("Liked songs", "VLLM"), item("Liked again", "LM"))
        val layout = LibrarySegments.playlistLayout(items, emptyList())
        assertEquals("Liked songs", layout.liked?.title)
        assertEquals(listOf("Liked again"), titles(layout.rows).filter { it == "Liked again" })
    }
}
