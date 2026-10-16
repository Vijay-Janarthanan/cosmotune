package com.cosmotune.video.home

import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.PlaylistItem
import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** What Home's shelves hold and in which order (plan task Y-17, step 1; spec 5.2). Pure: no clock, no source. */
class HomePlannerTest {
    private fun video(
        id: String,
        durationSec: Long? = 600,
        short: Boolean = false,
        uploadedAtMs: Long? = null,
        thumbnail: String? = "https://media.example.invalid/thumb/$id.jpg",
    ) = VideoItem(
        id = id, title = "Title of $id", channelId = "UCfakechannel0000000001", channelName = "A channel", channelAvatarUrl = null,
        thumbnailUrl = thumbnail, durationSec = durationSec, viewCount = 100, uploadedAtMs = uploadedAtMs, uploadedText = null, isShort = short,
    )

    private fun short(id: String, uploadedAtMs: Long? = null) = video(id, durationSec = 30, short = true, uploadedAtMs = uploadedAtMs)

    private fun entry(
        id: String,
        positionMs: Long = 300_000,
        durationMs: Long = 600_000,
        lastWatchedMs: Long = 1_000,
        item: VideoItem = video(id),
    ) = HistoryEntry(item, lastWatchedMs, positionMs, durationMs, 1)

    private fun related(vararg items: VideoItem): List<ListItem> = items.map { ListItem.Video(it) }

    private fun ids(items: List<VideoItem>) = items.map { it.id }

    // ---- continueWatching -------------------------------------------------------------------------------------------------

    @Test fun continueWatchingKeepsWhatIsBetweenTenAndNinetyPercent() {
        val history = listOf(
            entry("under", positionMs = 59_999),
            entry("tenExactly", positionMs = 60_000),
            entry("half", positionMs = 300_000),
            entry("justUnderDone", positionMs = 539_999),
            entry("done", positionMs = 540_000),
            entry("over", positionMs = 700_000),
            entry("nothing", positionMs = 0),
        )
        assertEquals(setOf("tenExactly", "half", "justUnderDone"), HomePlanner.continueWatching(history).map { it.item.id }.toSet())
    }

    @Test fun continueWatchingNeedsAKnownDuration() {
        val history = listOf(
            entry("unknown", positionMs = 5_000, durationMs = 0, item = video("unknown", durationSec = null)),
            entry("fromItem", positionMs = 300_000, durationMs = 0, item = video("fromItem", durationSec = 600)),
            entry("fromEntry", positionMs = 300_000, durationMs = 600_000, item = video("fromEntry", durationSec = null)),
        )
        assertEquals(setOf("fromItem", "fromEntry"), HomePlanner.continueWatching(history).map { it.item.id }.toSet())
    }

    @Test fun continueWatchingLeavesShortsOut() {
        val history = listOf(
            entry("marked", item = short("marked"), durationMs = 30_000, positionMs = 15_000),
            entry("byPicture", positionMs = 15_000, durationMs = 30_000, item = video("byPicture", durationSec = 30, thumbnail = "https://i.ytimg.com/vi/x/oar2.jpg")),
            entry("video", positionMs = 300_000),
        )
        assertEquals(listOf("video"), HomePlanner.continueWatching(history).map { it.item.id })
    }

    @Test fun continueWatchingIsNewestFirstWhateverTheOrderOfTheHistory() {
        val history = listOf(entry("old", lastWatchedMs = 10), entry("newest", lastWatchedMs = 300), entry("middle", lastWatchedMs = 200))
        assertEquals(listOf("newest", "middle", "old"), HomePlanner.continueWatching(history).map { it.item.id })
    }

    @Test fun continueWatchingHoldsAtMostTen() {
        val history = (1..25).map { entry("v$it", lastWatchedMs = it.toLong()) }
        val shelf = HomePlanner.continueWatching(history)
        assertEquals(10, shelf.size)
        assertEquals((25 downTo 16).map { "v$it" }, shelf.map { it.item.id }, "the ten newest")
    }

    @Test fun continueWatchingOfNothingIsNothing() {
        assertTrue(HomePlanner.continueWatching(emptyList()).isEmpty())
    }

    // ---- recentWatches ----------------------------------------------------------------------------------------------------

    @Test fun recentWatchesAreTheThreeNewestDistinctVideos() {
        val history = listOf(
            entry("a", lastWatchedMs = 50), entry("d", lastWatchedMs = 400), entry("b", lastWatchedMs = 100),
            entry("e", lastWatchedMs = 500), entry("c", lastWatchedMs = 300), entry("f", lastWatchedMs = 10),
        )
        assertEquals(listOf("e", "d", "c"), HomePlanner.recentWatches(history).map { it.item.id })
    }

    @Test fun aVideoListedTwiceCountsOnceAtItsNewestTime() {
        val history = listOf(
            entry("a", lastWatchedMs = 100), entry("b", lastWatchedMs = 90), entry("a", lastWatchedMs = 400),
            entry("c", lastWatchedMs = 80), entry("d", lastWatchedMs = 70), entry("b", lastWatchedMs = 30),
        )
        val recent = HomePlanner.recentWatches(history)
        assertEquals(listOf("a", "b", "c"), recent.map { it.item.id })
        assertEquals(400, recent.first().lastWatchedMs)
        assertEquals(90, recent[1].lastWatchedMs)
    }

    @Test fun recentWatchesOfAShortHistoryAreAllOfIt() {
        assertEquals(listOf("b", "a"), HomePlanner.recentWatches(listOf(entry("a", lastWatchedMs = 1), entry("b", lastWatchedMs = 2))).map { it.item.id })
        assertTrue(HomePlanner.recentWatches(emptyList()).isEmpty())
    }

    @Test fun recentWatchesCanBeAskedForAnotherCount() {
        val history = (1..8).map { entry("v$it", lastWatchedMs = it.toLong()) }
        assertEquals(listOf("v8", "v7"), HomePlanner.recentWatches(history, 2).map { it.item.id })
        assertTrue(HomePlanner.recentWatches(history, 0).isEmpty())
    }

    // ---- forYou -----------------------------------------------------------------------------------------------------------

    private val a1 = video("a1"); private val a2 = video("a2"); private val a3 = video("a3")
    private val b1 = video("b1"); private val b2 = video("b2")
    private val c1 = video("c1"); private val c2 = video("c2"); private val c3 = video("c3")

    private val watchedA = entry("wa", lastWatchedMs = 300)
    private val watchedB = entry("wb", lastWatchedMs = 200)
    private val watchedC = entry("wc", lastWatchedMs = 100)

    @Test fun forYouTakesOneFromEachWatchedVideoInTurn() {
        val relatedByVideo = mapOf("wa" to related(a1, a2, a3), "wb" to related(b1, b2), "wc" to related(c1, c2, c3))
        val picked = HomePlanner.forYou(listOf(watchedC, watchedA, watchedB), relatedByVideo, listOf(watchedA, watchedB, watchedC), hideShorts = false)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2", "c2", "a3", "c3"), ids(picked))
    }

    @Test fun forYouUsesOnlyTheLastThreeDistinctWatches() {
        val fourth = entry("wd", lastWatchedMs = 50)
        val relatedByVideo = mapOf("wa" to related(a1), "wb" to related(b1), "wc" to related(c1), "wd" to related(video("d1")))
        val picked = HomePlanner.forYou(listOf(watchedA, watchedB, watchedC, fourth), relatedByVideo, listOf(watchedA, watchedB, watchedC, fourth), false)
        assertEquals(listOf("a1", "b1", "c1"), ids(picked))
    }

    @Test fun forYouLeavesOutAnythingInTheHistory() {
        val relatedByVideo = mapOf("wa" to related(a1, a2), "wb" to related(b1))
        val history = listOf(watchedA, watchedB, entry("a1", positionMs = 1_000), entry("b1", positionMs = 590_000))
        val picked = HomePlanner.forYou(listOf(watchedA, watchedB), relatedByVideo, history, hideShorts = false)
        assertEquals(listOf("a2"), ids(picked), "a started video and a finished one are both out")
    }

    @Test fun forYouShowsAVideoOnlyOnce() {
        val relatedByVideo = mapOf("wa" to related(a1, b1, a2), "wb" to related(b1, b2), "wc" to related(a1, b1, c1))
        val picked = HomePlanner.forYou(listOf(watchedA, watchedB, watchedC), relatedByVideo, emptyList(), hideShorts = false)
        assertEquals(listOf("a1", "b1", "c1", "a2", "b2"), ids(picked))
    }

    @Test fun forYouDropsShortsOnlyWhileTheyAreHidden() {
        val s = short("s1")
        val relatedByVideo = mapOf("wa" to related(s, a1))
        assertEquals(listOf("s1", "a1"), ids(HomePlanner.forYou(listOf(watchedA), relatedByVideo, emptyList(), hideShorts = false)))
        assertEquals(listOf("a1"), ids(HomePlanner.forYou(listOf(watchedA), relatedByVideo, emptyList(), hideShorts = true)))
    }

    @Test fun forYouKeepsOnlyVideosOfTheRelatedLists() {
        val channel = ListItem.Channel(ChannelItem("UCfakechannel0000000002", "Another", null, null, null, null))
        val playlist = ListItem.Playlist(PlaylistItem("PLfake", "A playlist", null, null, null, 3))
        val relatedByVideo = mapOf("wa" to listOf(channel, ListItem.Video(a1), playlist, ListItem.Video(a2)))
        assertEquals(listOf("a1", "a2"), ids(HomePlanner.forYou(listOf(watchedA), relatedByVideo, emptyList(), false)))
    }

    @Test fun forYouCarriesOnWhenOneWatchHasNoRelatedVideosYet() {
        val relatedByVideo = mapOf("wb" to related(b1, b2))
        assertEquals(listOf("b1", "b2"), ids(HomePlanner.forYou(listOf(watchedA, watchedB, watchedC), relatedByVideo, emptyList(), false)))
    }

    @Test fun forYouHoldsAtMostTwelve() {
        val many = (1..20).map { video("m$it") }
        val picked = HomePlanner.forYou(listOf(watchedA), mapOf("wa" to related(*many.toTypedArray())), emptyList(), false)
        assertEquals((1..12).map { "m$it" }, ids(picked))
    }

    @Test fun forYouOfNothingIsNothing() {
        assertTrue(HomePlanner.forYou(emptyList(), emptyMap(), emptyList(), false).isEmpty())
        assertTrue(HomePlanner.forYou(listOf(watchedA), emptyMap(), emptyList(), false).isEmpty())
    }

    // ---- shorts -----------------------------------------------------------------------------------------------------------

    @Test fun shortsComeFromTheFeedThenTheRelatedListsThenTheKiosk() {
        val feed = listOf(short("f1", 300), short("f2", 200))
        val fromRelated = listOf(short("r1"), short("r2"))
        val fromKiosk = listOf(short("k1"))
        assertEquals(listOf("f1", "f2", "r1", "r2", "k1"), ids(HomePlanner.shorts(feed, fromRelated, fromKiosk)))
    }

    @Test fun theFeedsShortsAreNewestFirst() {
        val feed = listOf(short("old", 100), short("undated"), short("new", 900), short("mid", 500))
        assertEquals(listOf("new", "mid", "old", "undated"), ids(HomePlanner.shorts(feed, emptyList(), emptyList())))
    }

    @Test fun aShortInTwoListsIsShownOnceAtItsFirstPlace() {
        val feed = listOf(short("x", 300), short("y", 200))
        val fromRelated = listOf(short("y"), short("z"))
        val fromKiosk = listOf(short("z"), short("x"), short("w"))
        assertEquals(listOf("x", "y", "z", "w"), ids(HomePlanner.shorts(feed, fromRelated, fromKiosk)))
    }

    @Test fun shortsHoldAtMostTwelve() {
        val feed = (1..8).map { short("f$it", 1_000L - it) }
        val fromRelated = (1..8).map { short("r$it") }
        val picked = HomePlanner.shorts(feed, fromRelated, emptyList())
        assertEquals(12, picked.size)
        assertEquals((1..8).map { "f$it" } + (1..4).map { "r$it" }, ids(picked))
    }

    @Test fun shortsAreShownFromThreeAndNotBelow() {
        assertTrue(HomePlanner.shorts(emptyList(), emptyList(), emptyList()).isEmpty())
        assertTrue(HomePlanner.shorts(listOf(short("a", 1), short("b", 2)), emptyList(), emptyList()).isEmpty(), "two are not enough")
        assertEquals(3, HomePlanner.shorts(listOf(short("a", 1), short("b", 2)), listOf(short("c")), emptyList()).size)
        assertTrue(HomePlanner.shorts(listOf(short("a", 1), short("b", 2)), listOf(short("a")), listOf(short("b"))).isEmpty(), "repeats do not count twice")
    }

    @Test fun onlyShortsMakeTheShelf() {
        val feed = listOf(video("v1", uploadedAtMs = 900), short("s1", 800), video("v2", uploadedAtMs = 700), short("s2", 600), short("s3", 500))
        assertEquals(listOf("s1", "s2", "s3"), ids(HomePlanner.shorts(feed, listOf(video("v3")), listOf(video("v4")))))
    }

    // ---- the subscriptions shelf ------------------------------------------------------------------------------------------

    @Test fun theSubscriptionsShelfHoldsTheTenNewestFeedItems() {
        val feed = (1..14).map { video("f$it", uploadedAtMs = 10_000L - it) }
        assertEquals((1..10).map { "f$it" }, ids(HomePlanner.subscriptionsShelf(feed, hideShorts = false)))
    }

    @Test fun theSubscriptionsShelfIsNewestFirstWithTheUndatedLast() {
        val feed = listOf(video("old", uploadedAtMs = 100), video("undated"), video("new", uploadedAtMs = 900))
        assertEquals(listOf("new", "old", "undated"), ids(HomePlanner.subscriptionsShelf(feed, hideShorts = false)))
    }

    @Test fun theSubscriptionsShelfDropsShortsOnlyWhileTheyAreHidden() {
        val feed = listOf(video("v1", uploadedAtMs = 900), short("s1", 800), video("v2", uploadedAtMs = 700))
        assertEquals(listOf("v1", "s1", "v2"), ids(HomePlanner.subscriptionsShelf(feed, hideShorts = false)))
        assertEquals(listOf("v1", "v2"), ids(HomePlanner.subscriptionsShelf(feed, hideShorts = true)))
    }

    @Test fun theSubscriptionsShelfShowsAVideoOnce() {
        val feed = listOf(video("v1", uploadedAtMs = 900), video("v1", uploadedAtMs = 900), video("v2", uploadedAtMs = 100))
        assertEquals(listOf("v1", "v2"), ids(HomePlanner.subscriptionsShelf(feed, hideShorts = false)))
    }

    // ---- the order of the shelves -----------------------------------------------------------------------------------------

    private fun order(c: Boolean, s: Boolean, f: Boolean, sh: Boolean) = HomePlanner.shelfOrder(c, s, f, sh)

    @Test fun theShortsShelfStandsAfterTheFirstShelf() {
        val k = HomeShelfKind.entries
        assertEquals(listOf(HomeShelfKind.CONTINUE, HomeShelfKind.SHORTS, HomeShelfKind.SUBSCRIPTIONS, HomeShelfKind.FOR_YOU), order(true, true, true, true))
        assertEquals(listOf(HomeShelfKind.SUBSCRIPTIONS, HomeShelfKind.SHORTS, HomeShelfKind.FOR_YOU), order(false, true, true, true))
        assertEquals(listOf(HomeShelfKind.FOR_YOU, HomeShelfKind.SHORTS), order(false, false, true, true))
        assertEquals(4, k.size, "four kinds of shelf")
    }

    @Test fun withNoOtherShelfTheShortsShelfIsFirst() {
        assertEquals(listOf(HomeShelfKind.SHORTS), order(false, false, false, true))
    }

    @Test fun shelvesWithNothingInThemAreLeftOut() {
        assertEquals(listOf(HomeShelfKind.CONTINUE, HomeShelfKind.FOR_YOU), order(true, false, true, false))
        assertTrue(order(false, false, false, false).isEmpty(), "a first launch shows only the kiosk")
    }
}
