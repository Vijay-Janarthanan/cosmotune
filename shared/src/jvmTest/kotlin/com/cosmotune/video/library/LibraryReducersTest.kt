package com.cosmotune.video.library

import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The pure rules of the library (plan task Y-7, step 1; spec 8.1): one test per rule, every cap, every merge. */
class LibraryReducersTest {

    private val r = LibraryReducers
    private val minute = 60_000L

    private fun video(id: String, title: String = "Title $id", thumb: String? = "https://example.com/$id.jpg") = VideoItem(
        id = id, title = title, channelId = "UCchannel", channelName = "Channel", channelAvatarUrl = null, thumbnailUrl = thumb,
        durationSec = 600, viewCount = 1_000, uploadedAtMs = 1_000, uploadedText = null,
    )

    private fun channel(id: String, name: String = "Name $id", avatar: String? = "https://example.com/$id.png") =
        ChannelItem(id, name, avatar, subscriberCount = 10, videoCount = 5, description = null)

    private fun watch(id: String, last: Long, position: Long = 0, duration: Long = 0, count: Int = 1) =
        HistoryEntry(video(id), last, position, duration, count)

    private fun playlist(id: String, name: String, vararg ids: String, created: Long = 1, updated: Long = 1) =
        LocalPlaylist(id, name, ids.map { video(it) }, created, updated)

    private fun saved(id: String, title: String = "List $id") = PlaylistItem(id, title, null, "Owner", "UCowner", 12)

    // ------------------------------------------------------------------ subscriptions

    @Test
    fun subscribingAddsTheChannelWithTheTimeItWasFollowed() {
        val list = r.subscribe(emptyList(), channel("UC1"), nowMs = 5_000)
        assertEquals(listOf(Subscription("UC1", "Name UC1", "https://example.com/UC1.png", 5_000, null)), list)
    }

    @Test
    fun subscribingTwiceKeepsOneEntryAndTheNewerNameAndAvatarWin() {
        val first = r.subscribe(emptyList(), channel("UC1", "Old name", "https://example.com/old.png"), nowMs = 1_000)
        val second = r.subscribe(first, channel("UC1", "New name", "https://example.com/new.png"), nowMs = 9_000)
        assertEquals(1, second.size)
        assertEquals("New name", second[0].name)
        assertEquals("https://example.com/new.png", second[0].avatarUrl)
        assertEquals(1_000, second[0].subscribedAtMs, "the day it was followed does not move")
    }

    @Test
    fun aMissingNewAvatarDoesNotEraseTheOldOne() {
        val first = r.subscribe(emptyList(), channel("UC1", avatar = "https://example.com/old.png"), nowMs = 1)
        val second = r.subscribe(first, channel("UC1", "Renamed", avatar = null), nowMs = 2)
        assertEquals("https://example.com/old.png", second[0].avatarUrl)
        assertEquals("Renamed", second[0].name)
    }

    @Test
    fun subscribingKeepsTheLastSeenUpload() {
        val first = listOf(Subscription("UC1", "A", null, 1, lastUploadMs = 777))
        assertEquals(777L, r.subscribe(first, channel("UC1"), nowMs = 2)[0].lastUploadMs)
    }

    @Test
    fun unsubscribingRemovesTheChannelAndAnUnknownOneChangesNothing() {
        val list = r.subscribe(r.subscribe(emptyList(), channel("UC1"), 1), channel("UC2"), 2)
        assertEquals(listOf("UC2"), r.unsubscribe(list, "UC1").map { it.channelId })
        assertSame(list, r.unsubscribe(list, "UCnone"))
    }

    // ------------------------------------------------------------------ history

    @Test
    fun aFirstWatchIsOneEntryWatchedOnceAtTheFront() {
        val list = r.recordWatch(emptyList(), video("a"), positionMs = 12_000, durationMs = 600_000, nowMs = 10_000)
        assertEquals(listOf(HistoryEntry(video("a"), 10_000, 12_000, 600_000, 1)), list)
    }

    @Test
    fun theMostRecentWatchIsFirst() {
        var list = r.recordWatch(emptyList(), video("a"), 0, 0, nowMs = 1)
        list = r.recordWatch(list, video("b"), 0, 0, nowMs = 2)
        list = r.recordWatch(list, video("c"), 0, 0, nowMs = 3)
        assertEquals(listOf("c", "b", "a"), list.map { it.item.id })
    }

    @Test
    fun watchingAgainKeepsOneEntryMovesItToTheFrontAndUpdatesPositionAndDuration() {
        var list = r.recordWatch(emptyList(), video("a"), 1_000, 100_000, nowMs = 1_000)
        list = r.recordWatch(list, video("b"), 0, 0, nowMs = 2_000)
        list = r.recordWatch(list, video("a"), 55_000, 120_000, nowMs = 3_000)
        assertEquals(listOf("a", "b"), list.map { it.item.id })
        val a = list[0]
        assertEquals(55_000, a.positionMs)
        assertEquals(120_000, a.durationMs)
        assertEquals(3_000, a.lastWatchedMs)
    }

    @Test
    fun theWatchCountRisesOnlyWhenThePreviousWatchWasMoreThanThirtyMinutesAgo() {
        var list = r.recordWatch(emptyList(), video("a"), 0, 0, nowMs = 0)
        // ten seconds later (a position update): the same watch
        list = r.recordWatch(list, video("a"), 10_000, 0, nowMs = 10_000)
        assertEquals(1, list[0].watchCount)
        // exactly thirty minutes after the previous one: still the same watch (the rule is "more than")
        list = r.recordWatch(list, video("a"), 20_000, 0, nowMs = 10_000 + 30 * minute)
        assertEquals(1, list[0].watchCount)
        // one millisecond over thirty minutes after that one: a new watch
        list = r.recordWatch(list, video("a"), 30_000, 0, nowMs = 10_000 + 30 * minute + 30 * minute + 1)
        assertEquals(2, list[0].watchCount)
        // and the count is not lost by the next position update
        list = r.recordWatch(list, video("a"), 40_000, 0, nowMs = 10_000 + 60 * minute + 11_000)
        assertEquals(2, list[0].watchCount)
    }

    @Test
    fun theHistoryCapDropsTheOldestEntry() {
        assertEquals(10_000, LibraryReducers.HISTORY_CAP)
        val full = (1..10_000).map { watch("v$it", last = (10_001 - it).toLong()) } // v1 is the newest
        val next = r.recordWatch(full, video("fresh"), 0, 0, nowMs = 99_999)
        assertEquals(10_000, next.size)
        assertEquals("fresh", next.first().item.id)
        assertFalse(next.any { it.item.id == "v10000" }, "the oldest entry is dropped")
        assertTrue(next.any { it.item.id == "v9999" })
    }

    @Test
    fun recordingTheNewestWatchAgainAtTheCapDropsNothing() {
        val full = (1..10_000).map { watch("v$it", last = (10_001 - it).toLong()) }
        val next = r.recordWatch(full, video("v500"), 5, 6, nowMs = 99_999)
        assertEquals(10_000, next.size)
        assertEquals("v500", next.first().item.id)
        assertTrue(next.any { it.item.id == "v10000" })
    }

    @Test
    fun removingFromTheHistoryRemovesOnlyThatVideo() {
        val list = listOf(watch("a", 3), watch("b", 2), watch("c", 1))
        assertEquals(listOf("a", "c"), r.removeFromHistory(list, "b").map { it.item.id })
        assertSame(list, r.removeFromHistory(list, "none"))
    }

    // ------------------------------------------------------------------ watch later

    @Test
    fun watchLaterAppendsInOrderWithoutDuplicates() {
        var list = r.addToList(emptyList(), video("a"))
        list = r.addToList(list, video("b"))
        val again = r.addToList(list, video("a"))
        assertEquals(listOf("a", "b"), again.map { it.id })
        assertSame(list, again)
    }

    @Test
    fun watchLaterStopsTakingNewItemsAtFiveThousand() {
        assertEquals(5_000, LibraryReducers.LIST_CAP)
        val full = (1..5_000).map { video("v$it") }
        val next = r.addToList(full, video("one-too-many"))
        assertSame(full, next)
        assertFalse(next.any { it.id == "one-too-many" })
    }

    @Test
    fun removingFromAListRemovesThatItem() {
        val list = listOf(video("a"), video("b"), video("c"))
        assertEquals(listOf("a", "c"), r.removeFromList(list, "b").map { it.id })
        assertSame(list, r.removeFromList(list, "none"))
    }

    @Test
    fun movingAnItemPutsItAtTheTargetIndex() {
        val list = listOf(video("a"), video("b"), video("c"), video("d"))
        assertEquals(listOf("b", "c", "d", "a"), r.moveInList(list, 0, 3).map { it.id })
        assertEquals(listOf("d", "a", "b", "c"), r.moveInList(list, 3, 0).map { it.id })
        assertEquals(listOf("a", "c", "b", "d"), r.moveInList(list, 1, 2).map { it.id })
    }

    @Test
    fun aMoveOutOfRangeOrToTheSamePlaceChangesNothing() {
        val list = listOf(video("a"), video("b"))
        assertSame(list, r.moveInList(list, -1, 0))
        assertSame(list, r.moveInList(list, 0, 2))
        assertSame(list, r.moveInList(list, 5, 0))
        assertSame(list, r.moveInList(list, 1, 1))
    }

    // ------------------------------------------------------------------ liked

    @Test
    fun likingPutsTheVideoAtTheFrontAndLikingAgainMovesItThereWithoutADuplicate() {
        var list = r.like(emptyList(), video("a"))
        list = r.like(list, video("b"))
        assertEquals(listOf("b", "a"), list.map { it.id })
        list = r.like(list, video("a"))
        assertEquals(listOf("a", "b"), list.map { it.id })
    }

    @Test
    fun theLikedCapDropsTheOldest() {
        val full = (1..5_000).map { video("v$it") } // v1 is the newest
        val next = r.like(full, video("fresh"))
        assertEquals(5_000, next.size)
        assertEquals("fresh", next.first().id)
        assertFalse(next.any { it.id == "v5000" })
    }

    // ------------------------------------------------------------------ your playlists

    @Test
    fun creatingAPlaylistStoresItsIdNameAndTimes() {
        val list = r.createPlaylist(emptyList(), id = "p1", name = "  Road trip ", nowMs = 77)
        assertEquals(listOf(LocalPlaylist("p1", "Road trip", emptyList(), 77, 77)), list)
    }

    @Test
    fun thereAreAtMostFiveHundredPlaylists() {
        assertEquals(500, LibraryReducers.PLAYLIST_CAP)
        val full = (1..500).map { playlist("p$it", "List $it") }
        assertSame(full, r.createPlaylist(full, "p501", "Too many", 5))
    }

    @Test
    fun renamingChangesTheNameAndTheUpdatedTimeAndIgnoresABlankName() {
        val list = listOf(playlist("p1", "Old", updated = 10))
        val renamed = r.renamePlaylist(list, "p1", " New ", nowMs = 50)
        assertEquals("New", renamed[0].name)
        assertEquals(50, renamed[0].updatedMs)
        assertEquals(1, renamed[0].createdMs)
        assertSame(list, r.renamePlaylist(list, "p1", "   ", nowMs = 60))
        assertSame(list, r.renamePlaylist(list, "none", "X", nowMs = 60))
    }

    @Test
    fun deletingAPlaylistRemovesIt() {
        val list = listOf(playlist("p1", "A"), playlist("p2", "B"))
        assertEquals(listOf("p2"), r.deletePlaylist(list, "p1").map { it.id })
        assertSame(list, r.deletePlaylist(list, "none"))
    }

    @Test
    fun addingToAPlaylistAppendsWithoutDuplicatesAndTouchesTheUpdatedTimeOnlyWhenItChanged() {
        val list = listOf(playlist("p1", "A", "a", updated = 10))
        val added = r.addToPlaylist(list, "p1", video("b"), nowMs = 20)
        assertEquals(listOf("a", "b"), added[0].items.map { it.id })
        assertEquals(20, added[0].updatedMs)
        val duplicate = r.addToPlaylist(added, "p1", video("a"), nowMs = 30)
        assertSame(added, duplicate)
        assertSame(list, r.addToPlaylist(list, "none", video("z"), nowMs = 30))
    }

    @Test
    fun aPlaylistHoldsAtMostFiveThousandItems() {
        assertEquals(5_000, LibraryReducers.PLAYLIST_ITEM_CAP)
        val full = LocalPlaylist("p1", "Full", (1..5_000).map { video("v$it") }, 1, 1)
        val list = listOf(full)
        assertSame(list, r.addToPlaylist(list, "p1", video("one-too-many"), nowMs = 9))
    }

    @Test
    fun removingFromAPlaylistRemovesTheVideo() {
        val list = listOf(playlist("p1", "A", "a", "b", "c", updated = 10))
        val next = r.removeFromPlaylist(list, "p1", "b", nowMs = 40)
        assertEquals(listOf("a", "c"), next[0].items.map { it.id })
        assertEquals(40, next[0].updatedMs)
        assertSame(list, r.removeFromPlaylist(list, "p1", "none", nowMs = 50))
    }

    @Test
    fun movingInAPlaylistReordersIt() {
        val list = listOf(playlist("p1", "A", "a", "b", "c", updated = 10))
        val next = r.moveInPlaylist(list, "p1", 2, 0, nowMs = 40)
        assertEquals(listOf("c", "a", "b"), next[0].items.map { it.id })
        assertEquals(40, next[0].updatedMs)
        assertSame(list, r.moveInPlaylist(list, "p1", 0, 9, nowMs = 50))
    }

    // ------------------------------------------------------------------ saved playlists

    @Test
    fun savingAPlaylistPutsItFirstAndSavingItAgainReplacesItWithoutADuplicate() {
        var list = r.savePlaylist(emptyList(), saved("PL1", "First"))
        list = r.savePlaylist(list, saved("PL2"))
        list = r.savePlaylist(list, saved("PL1", "Renamed"))
        assertEquals(listOf("PL1", "PL2"), list.map { it.id })
        assertEquals("Renamed", list[0].title)
    }

    @Test
    fun theSavedPlaylistsCapIsOneThousandAndTheOldestGoes() {
        assertEquals(1_000, LibraryReducers.SAVED_CAP)
        val full = (1..1_000).map { saved("PL$it") } // PL1 is the newest
        val next = r.savePlaylist(full, saved("fresh"))
        assertEquals(1_000, next.size)
        assertEquals("fresh", next.first().id)
        assertFalse(next.any { it.id == "PL1000" })
    }

    @Test
    fun unsavingRemovesThePlaylist() {
        val list = listOf(saved("PL1"), saved("PL2"))
        assertEquals(listOf("PL2"), r.unsavePlaylist(list, "PL1").map { it.id })
        assertSame(list, r.unsavePlaylist(list, "none"))
    }

    // ------------------------------------------------------------------ searches

    @Test
    fun searchesAreMostRecentFirstAndTrimmed() {
        var list = r.addSearch(emptyList(), "  tape echo ")
        list = r.addSearch(list, "rain")
        assertEquals(listOf("rain", "tape echo"), list)
    }

    @Test
    fun aRepeatedSearchMovesToTheFrontIgnoringCaseAndKeepsTheNewSpelling() {
        var list = r.addSearch(emptyList(), "Tape Echo")
        list = r.addSearch(list, "rain")
        list = r.addSearch(list, "tape echo")
        assertEquals(listOf("tape echo", "rain"), list)
    }

    @Test
    fun aBlankSearchIsIgnored() {
        val list = listOf("rain")
        assertSame(list, r.addSearch(list, "   "))
    }

    @Test
    fun onlyFiftySearchesAreKept() {
        assertEquals(50, LibraryReducers.SEARCH_CAP)
        var list = emptyList<String>()
        for (n in 1..60) list = r.addSearch(list, "query $n")
        assertEquals(50, list.size)
        assertEquals("query 60", list.first())
        assertEquals("query 11", list.last())
    }

    @Test
    fun removingASearchRemovesIt() {
        val list = listOf("b", "a")
        assertEquals(listOf("b"), r.removeSearch(list, "a"))
        assertSame(list, r.removeSearch(list, "none"))
    }

    // ------------------------------------------------------------------ merging (import)

    @Test
    fun mergingSubscriptionsAddsTheUnknownOnesAfterMineAndKeepsMyEntries() {
        val mine = listOf(Subscription("UC1", "Mine", null, 1, 5))
        val merged = r.mergeSubscriptions(mine, listOf(Subscription("UC1", "Theirs", "x", 99, null), Subscription("UC2", "New", null, 2, null)))
        assertEquals(listOf("UC1", "UC2"), merged.list.map { it.channelId })
        assertEquals("Mine", merged.list[0].name)
        assertEquals(1, merged.added)
        assertEquals(0, merged.skipped)
    }

    @Test
    fun mergingSubscriptionsSkipsABlankIdAndAnIdRepeatedInTheDocumentOnlyCountsOnce() {
        val merged = r.mergeSubscriptions(
            emptyList(),
            listOf(Subscription("UC1", "A", null, 1, null), Subscription("UC1", "A again", null, 1, null), Subscription("", "Nameless", null, 1, null)),
        )
        assertEquals(listOf("UC1"), merged.list.map { it.channelId })
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
    }

    @Test
    fun mergingHistoryTheNewerWatchWinsWithItsPosition() {
        val mine = listOf(watch("a", last = 1_000, position = 10_000, duration = 100_000, count = 2))
        val theirs = listOf(watch("a", last = 5_000, position = 80_000, duration = 100_000, count = 1))
        val merged = r.mergeHistory(mine, theirs)
        assertEquals(1, merged.list.size)
        assertEquals(80_000, merged.list[0].positionMs)
        assertEquals(5_000, merged.list[0].lastWatchedMs)
        assertEquals(2, merged.list[0].watchCount, "the higher count is kept")
        assertEquals(1, merged.added)
    }

    @Test
    fun mergingHistoryAnOlderWatchChangesNothing() {
        val mine = listOf(watch("a", last = 5_000, position = 80_000, duration = 100_000))
        val merged = r.mergeHistory(mine, listOf(watch("a", last = 1_000, position = 10_000, duration = 100_000)))
        assertSame(mine, merged.list)
        assertEquals(0, merged.added)
    }

    @Test
    fun mergingHistoryANewerWatchWithoutAPositionKeepsMyPositionAndItem() {
        val mine = listOf(watch("a", last = 1_000, position = 40_000, duration = 100_000, count = 1))
        val theirs = listOf(HistoryEntry(video("a", title = "Takeout title", thumb = null), 9_000, 0, 0, 1))
        val merged = r.mergeHistory(mine, theirs)
        assertEquals(40_000, merged.list[0].positionMs)
        assertEquals(100_000, merged.list[0].durationMs)
        assertEquals("Title a", merged.list[0].item.title)
        assertEquals(9_000, merged.list[0].lastWatchedMs)
        assertEquals(1, merged.added)
    }

    @Test
    fun mergingHistoryKeepsTheMostRecentFirstAndAddsTheNewVideos() {
        val mine = listOf(watch("a", 5_000), watch("c", 1_000))
        val merged = r.mergeHistory(mine, listOf(watch("b", 3_000), watch("d", 9_000)))
        assertEquals(listOf("d", "a", "b", "c"), merged.list.map { it.item.id })
        assertEquals(2, merged.added)
    }

    @Test
    fun mergingHistoryRespectsTheCapAndCountsTheDroppedNewOnesAsSkipped() {
        val mine = (1..10_000).map { watch("v$it", last = 100_000L - it) }
        val theirs = listOf(watch("old-but-new-to-me", last = 5), watch("recent", last = 200_000))
        val merged = r.mergeHistory(mine, theirs)
        assertEquals(10_000, merged.list.size)
        assertEquals("recent", merged.list.first().item.id)
        assertFalse(merged.list.any { it.item.id == "old-but-new-to-me" })
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)
    }

    @Test
    fun mergingHistorySkipsAnEntryWithoutAVideoId() {
        val merged = r.mergeHistory(emptyList(), listOf(HistoryEntry(video(""), 1, 0, 0, 1), watch("a", 2)))
        assertEquals(listOf("a"), merged.list.map { it.item.id })
        assertEquals(1, merged.skipped)
    }

    @Test
    fun mergingPlaylistsAMatchingNameGetsTheNewItemsAppendedWithoutDuplicates() {
        val mine = listOf(playlist("p1", "Road trip", "a", "b", updated = 10))
        val theirs = listOf(playlist("other-id", "road trip ", "b", "c", "d"))
        val merged = r.mergePlaylists(mine, theirs, nowMs = 500)
        assertEquals(1, merged.list.size)
        assertEquals("p1", merged.list[0].id)
        assertEquals("Road trip", merged.list[0].name)
        assertEquals(listOf("a", "b", "c", "d"), merged.list[0].items.map { it.id })
        assertEquals(500, merged.list[0].updatedMs)
        assertEquals(1, merged.added)
    }

    @Test
    fun mergingPlaylistsTheSameIdIsTheSamePlaylistEvenAfterARename() {
        val mine = listOf(playlist("p1", "Renamed here", "a"))
        val theirs = listOf(playlist("p1", "Original name", "a", "z"))
        val merged = r.mergePlaylists(mine, theirs, nowMs = 500)
        assertEquals(1, merged.list.size)
        assertEquals("Renamed here", merged.list[0].name)
        assertEquals(listOf("a", "z"), merged.list[0].items.map { it.id })
    }

    @Test
    fun mergingPlaylistsAnUnknownOneIsAddedAndAnIdenticalOneChangesNothing() {
        val mine = listOf(playlist("p1", "A", "a"))
        val merged = r.mergePlaylists(mine, listOf(playlist("p1", "A", "a"), playlist("p2", "B", "x")), nowMs = 9)
        assertEquals(listOf("p1", "p2"), merged.list.map { it.id })
        assertEquals(1, merged.added, "only the new playlist counts")
        assertEquals(mine[0], merged.list[0], "the unchanged playlist keeps its updated time")
    }

    @Test
    fun mergingPlaylistsTwoNewOnesWithTheSameNameInOneDocumentBecomeOne() {
        val merged = r.mergePlaylists(emptyList(), listOf(playlist("p1", "Mix", "a"), playlist("p2", "mix", "b")), nowMs = 9)
        assertEquals(1, merged.list.size)
        assertEquals(listOf("a", "b"), merged.list[0].items.map { it.id })
    }

    @Test
    fun mergingPlaylistsStopsAtTheCapAndCountsTheRestAsSkipped() {
        val mine = (1..500).map { playlist("p$it", "List $it") }
        val merged = r.mergePlaylists(mine, listOf(playlist("new", "Brand new", "a")), nowMs = 9)
        assertEquals(500, merged.list.size)
        assertEquals(0, merged.added)
        assertEquals(1, merged.skipped)
    }

    @Test
    fun mergingItemListsAppendsTheUnknownOnesAndStopsAtTheCap() {
        val mine = listOf(video("a"), video("b"))
        val merged = r.mergeItems(mine, listOf(video("b"), video("c"), video("")))
        assertEquals(listOf("a", "b", "c"), merged.list.map { it.id })
        assertEquals(1, merged.added)
        assertEquals(1, merged.skipped)

        val full = (1..5_000).map { video("v$it") }
        val capped = r.mergeItems(full, listOf(video("extra")))
        assertEquals(5_000, capped.list.size)
        assertEquals(1, capped.skipped)
    }

    @Test
    fun mergingSavedPlaylistsAppendsTheUnknownOnes() {
        val mine = listOf(saved("PL1"))
        val merged = r.mergeSavedPlaylists(mine, listOf(saved("PL1", "Other title"), saved("PL2")))
        assertEquals(listOf("PL1", "PL2"), merged.list.map { it.id })
        assertEquals("List PL1", merged.list[0].title)
    }

    @Test
    fun mergingSearchesAppendsTheUnknownOnesIgnoringCaseAndStopsAtFifty() {
        val mine = listOf("rain", "Tape")
        val merged = r.mergeSearches(mine, listOf("RAIN", "tape", "drone", " ", "drone"))
        assertEquals(listOf("rain", "Tape", "drone"), merged.list)
        val many = (1..50).map { "q$it" }
        assertEquals(50, r.mergeSearches(many, listOf("one more")).list.size)
    }
}
