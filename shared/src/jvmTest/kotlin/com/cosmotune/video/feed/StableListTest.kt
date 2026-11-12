package com.cosmotune.video.feed

import com.cosmotune.video.model.VideoItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

class StableListTest {
    private fun v(id: String, at: Long? = null) = VideoItem(id, id, null, null, null, null, null, null, at, null)

    @Test fun appendKeepsEveryShownItemWhereItIs() {
        val shown = listOf(v("a"), v("b"), v("c"))
        val fetched = listOf(v("c"), v("d"), v("a"), v("e"))
        assertEquals(listOf("a", "b", "c", "d", "e"), StableList.append(shown, fetched).map { it.id })
    }

    @Test fun appendDoesNotCountTheSameIdTwiceInOneBatch() {
        assertEquals(listOf("a", "b"), StableList.append(listOf(v("a")), listOf(v("b"), v("b"), v("a"))).map { it.id })
    }

    @Test fun appendReturnsTheSameListWhenNothingIsNew() {
        val shown = listOf(v("a"), v("b"))
        assertSame(shown, StableList.append(shown, listOf(v("b"), v("a"))))
    }

    @Test fun appendDropsNothingEvenWhenTheFreshListLostIt() {
        assertEquals(listOf("a", "b", "z"), StableList.append(listOf(v("a"), v("b")), listOf(v("z"))).map { it.id })
    }

    @Test fun byDateSlipsNewItemsInWithoutMovingTheOnesShown() {
        val shown = listOf(v("a", 90), v("b", 70), v("c", 50))
        val fresh = listOf(v("n", 95), v("c", 50), v("m", 60), v("o", 10))
        assertEquals(listOf("n", "a", "b", "m", "c", "o"), StableList.byDate(shown, fresh).map { it.id })
    }

    @Test fun byDateKeepsShownOrderEvenIfTheirTimesDisagree() {
        val shown = listOf(v("a", 50), v("b", 90))
        assertEquals(listOf("a", "b", "n"), StableList.byDate(shown, listOf(v("n", 10), v("a", 50))).map { it.id })
    }

    @Test fun byDatePutsUndatedItemsLast() {
        assertEquals(listOf("a", "x"), StableList.byDate(listOf(v("a", 5)), listOf(v("x"))).map { it.id })
    }

    @Test fun withoutHidesOnlyTheNamedOnes() {
        assertEquals(listOf("a", "c"), StableList.without(listOf(v("a"), v("b"), v("c"))) { it.id == "b" }.map { it.id })
    }
}

class StableFeedTest {
    private fun v(id: String, at: Long? = null) = VideoItem(id, id, null, null, null, null, null, null, at, null)

    @Test fun loadingMoreOnlyAppendsWhateverOrderTheSourcesNowGive() {
        val feed = StableFeed()
        assertEquals(listOf("a", "b", "c"), feed.update("all", listOf(v("a"), v("b"), v("c"))).map { it.id })
        // the sources are interleaved afresh once a page more has come: the old three must not move
        assertEquals(listOf("a", "b", "c", "x", "y"), feed.update("all", listOf(v("x"), v("a"), v("y"), v("b"), v("c"))).map { it.id })
    }

    @Test fun anotherModeStartsTheListAgain() {
        val feed = StableFeed()
        feed.update("all", listOf(v("a")))
        assertEquals(listOf("k"), feed.update("kiosk", listOf(v("k"))).map { it.id })
    }

    @Test fun resetShowsWhatIsGivenNext() {
        val feed = StableFeed()
        feed.update("all", listOf(v("a"), v("b")))
        feed.reset()
        assertEquals(listOf("b", "n"), feed.update("all", listOf(v("b"), v("n"))).map { it.id })
    }

    @Test fun aHiddenVideoLeavesWithoutMovingTheRest() {
        val feed = StableFeed()
        feed.update("all", listOf(v("a"), v("b"), v("c")))
        assertEquals(listOf("a", "c", "d"), feed.update("all", listOf(v("a"), v("c"), v("d"))) { it.id == "b" }.map { it.id })
    }

    @Test fun aNewestFirstFeedTakesNewUploadsAtTheTopAndOlderOnesAtTheFoot() {
        val feed = StableFeed(newestFirst = true)
        feed.update("subs", listOf(v("a", 90), v("b", 50)))
        assertEquals(listOf("n", "a", "b", "o"), feed.update("subs", listOf(v("o", 10), v("b", 50), v("n", 95), v("a", 90))).map { it.id })
    }
}
