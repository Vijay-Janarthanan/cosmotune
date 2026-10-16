package com.cosmotune.video.comments

import com.cosmotune.video.model.Comment
import com.cosmotune.video.model.CommentPage
import com.cosmotune.video.model.CommentSort
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The comments panel's paging (plan task Y-23, spec 5.9), against the fake source: the first page, loading more, the order, a page
 * that fails and "Try again", comments that come twice, the first-page cache that the tile and the panel share, and the replies.
 */
class CommentsFeedTest {
    private val video = "fakevideo01"
    private var now = 1_000_000L
    private val source = FakeVideoSource()
    private val cache = CommentsPageCache(clock = { now })

    private fun TestScope.feedFor(videoId: String = video, sort: CommentSort = CommentSort.TOP) =
        CommentsFeed(CachedCommentsLoader(source, videoId, cache), this, sort)

    private fun comment(id: String, text: String = "text $id") = Comment(
        id = id, authorName = "A", authorChannelId = null, authorAvatarUrl = null, text = text, likeCount = 1, publishedText = null,
        pinned = false, heartedByCreator = false, byCreator = false, edited = false, replyCount = 0, replies = null,
    )

    private fun page(vararg ids: String, next: String? = null, total: Long? = null, sortable: Boolean = true, disabled: Boolean = false) =
        CommentPage(ids.map { comment(it) }, next?.let { PageToken(it) }, total, disabled = disabled, sortable = sortable)

    // ---- the first page and the pages after it ------------------------------------------------------------------------

    @Test fun beforeStartTheStateIsLoadingAndEmpty() = runTest {
        val feed = feedFor()
        val s = feed.state.value
        assertTrue(s.loading)
        assertTrue(s.items.isEmpty())
        assertFalse(s.empty, "loading is not empty")
        assertTrue(source.calls.isEmpty(), "nothing is asked before start()")
    }

    @Test fun startLoadsTheFirstPage() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        val s = feed.state.value
        assertFalse(s.loading)
        assertEquals(FakeVideoSource.PAGE_SIZE, s.items.size)
        assertTrue(s.hasMore)
        assertTrue(s.sortable)
        assertEquals(25L, s.total)
        assertNull(s.failure)
        assertEquals(listOf("comments($video,TOP)"), source.calls)
    }

    @Test fun startTwiceAsksOnce() = runTest {
        val feed = feedFor()
        feed.start()
        feed.start()
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test fun loadMoreAppendsThePagesInOrderUntilTheEnd() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(20, feed.state.value.items.size)
        assertTrue(feed.state.value.hasMore)
        feed.loadMore()
        advanceUntilIdle()
        val s = feed.state.value
        assertEquals(25, s.items.size)
        assertFalse(s.hasMore)
        assertEquals((1..25).map { "$video-c${it.toString().padStart(2, '0')}" }, s.items.map { it.id })
        val asked = source.calls.size
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(asked, source.calls.size, "nothing is asked past the last page")
    }

    @Test fun loadMoreWhileAPageIsOnItsWayAsksOnce() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        feed.loadMore()
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(2, source.calls.size, "the first page and one more")
        assertEquals(20, feed.state.value.items.size)
    }

    @Test fun loadMoreBeforeTheFirstPageIsInDoesNothing() = runTest {
        val feed = feedFor()
        feed.loadMore()
        feed.start()
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test fun commentsTurnedOffGiveAnEmptyDisabledState() = runTest {
        val feed = feedFor(FakeVideoSource.COMMENTS_OFF_VIDEO)
        feed.start()
        advanceUntilIdle()
        val s = feed.state.value
        assertTrue(s.disabled)
        assertTrue(s.items.isEmpty())
        assertFalse(s.hasMore)
        assertFalse(s.sortable)
        assertFalse(s.loading)
        assertFalse(s.empty, "turned off is its own state, not 'no comments yet'")
    }

    @Test fun aVideoWithNoCommentsIsEmpty() = runTest {
        val feed = CommentsFeed({ _, _, _ -> page() }, this)
        feed.start()
        advanceUntilIdle()
        assertTrue(feed.state.value.empty)
    }

    @Test fun theTotalAndSortableOfTheFirstPageSurviveLaterPagesThatDoNotCarryThem() = runTest {
        val pages = mapOf<String?, CommentPage>(
            null to page("a", "b", next = "2", total = 40, sortable = true),
            "2" to page("c", "d", next = null, total = null, sortable = false),
        )
        val feed = CommentsFeed({ _, token, _ -> pages.getValue(token?.value) }, this)
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        val s = feed.state.value
        assertEquals(40L, s.total)
        assertTrue(s.sortable)
        assertEquals(listOf("a", "b", "c", "d"), s.items.map { it.id })
    }

    // ---- the order ----------------------------------------------------------------------------------------------------

    @Test fun anotherOrderStartsAgainFromTheFirstPage() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        feed.setSort(CommentSort.NEWEST)
        advanceUntilIdle()
        val s = feed.state.value
        assertEquals(CommentSort.NEWEST, s.sort)
        assertEquals(FakeVideoSource.PAGE_SIZE, s.items.size, "page one of the new order")
        assertEquals("$video-c01", s.items.first().id, "the pinned comment stays first")
        assertEquals("$video-c25", s.items[1].id, "then the newest")
        assertTrue(s.sortable, "the chips stay while the other order loads")
        assertEquals(25L, s.total)
        assertEquals("comments($video,NEWEST)", source.calls.last())
    }

    @Test fun theSameOrderAgainChangesNothing() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        feed.setSort(CommentSort.TOP)
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test fun whileAnotherOrderLoadsTheOldItemsAreGoneAndTheChipsStay() = runTest {
        val gate = CompletableDeferred<CommentPage>()
        var asked = 0
        val feed = CommentsFeed(
            { sort, _, _ -> if (++asked == 1) page("a", "b", next = "2", total = 9) else if (sort == CommentSort.NEWEST) gate.await() else page("z") },
            this,
        )
        feed.start()
        advanceUntilIdle()
        feed.setSort(CommentSort.NEWEST)
        advanceUntilIdle()
        val s = feed.state.value
        assertTrue(s.loading)
        assertTrue(s.items.isEmpty())
        assertTrue(s.sortable)
        gate.complete(page("n1", "n2"))
        advanceUntilIdle()
        assertEquals(listOf("n1", "n2"), feed.state.value.items.map { it.id })
    }

    @Test fun anAnswerForTheOldOrderThatArrivesLateIsIgnored() = runTest {
        val slowTop = CompletableDeferred<CommentPage>()
        // a source that does not stop when asked to (an HTTP call already in flight): the guard on the order is what keeps its answer out
        val feed = CommentsFeed(
            { sort, _, _ -> if (sort == CommentSort.TOP) withContext(NonCancellable) { slowTop.await() } else page("newest") },
            this,
        )
        feed.start()
        advanceUntilIdle()
        feed.setSort(CommentSort.NEWEST)
        advanceUntilIdle()
        slowTop.complete(page("top"))
        advanceUntilIdle()
        assertEquals(listOf("newest"), feed.state.value.items.map { it.id })
    }

    // ---- failures -----------------------------------------------------------------------------------------------------

    @Test fun aFirstPageThatFailsIsAFailureAndTryAgainLoadsIt() = runTest {
        source.failNext(VideoSourceError.Network("offline", null))
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        var s = feed.state.value
        assertEquals(CommentsFailure.NETWORK, s.failure)
        assertFalse(s.loading)
        assertTrue(s.items.isEmpty())
        assertFalse(s.empty, "a failure is not 'no comments yet'")
        feed.retry()
        advanceUntilIdle()
        s = feed.state.value
        assertNull(s.failure)
        assertEquals(FakeVideoSource.PAGE_SIZE, s.items.size)
    }

    @Test fun aFailedFirstPageLeavesNothingInTheCacheAndTryAgainAsksAgain() = runTest {
        source.failNext(VideoSourceError.Parsing("changed", null))
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        assertEquals(CommentsFailure.SOURCE_CHANGED, feed.state.value.failure)
        assertNull(cache.get(video, CommentSort.TOP), "a failure is not remembered")
        feed.retry()
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
        assertNull(feed.state.value.failure)
        assertNotNull(cache.get(video, CommentSort.TOP))
    }

    @Test fun tryAgainAsksForAFreshFirstPage() = runTest {
        val flags = mutableListOf<Boolean>()
        var fail = true
        val feed = CommentsFeed(
            { _, _, fresh ->
                flags += fresh
                if (fail) { fail = false; throw VideoSourceError.Network("x", null) }
                page("a")
            },
            this,
        )
        feed.start()
        advanceUntilIdle()
        feed.retry()
        advanceUntilIdle()
        assertEquals(listOf(false, true), flags, "the first ask may use the cache, the retry must not")
        assertEquals(listOf("a"), feed.state.value.items.map { it.id })
    }

    @Test fun tryAgainWithNothingFailedDoesNothing() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        feed.retry()
        advanceUntilIdle()
        assertEquals(1, source.calls.size)
    }

    @Test fun aLaterPageThatFailsKeepsTheCommentsAndStopsTheLoading() = runTest {
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        source.failNext(VideoSourceError.BotCheck("bot"))
        feed.loadMore()
        advanceUntilIdle()
        var s = feed.state.value
        assertEquals(CommentsFailure.BOT_CHECK, s.moreFailure)
        assertNull(s.failure)
        assertEquals(FakeVideoSource.PAGE_SIZE, s.items.size)
        assertFalse(s.loadingMore)
        val asked = source.calls.size
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(asked, source.calls.size, "no loop of retries: the next ask waits for 'Try again'")
        feed.retry()
        advanceUntilIdle()
        s = feed.state.value
        assertNull(s.moreFailure)
        assertEquals(20, s.items.size)
    }

    @Test fun anEmptyFirstPageFollowedByAPageThatFailsIsAFailureOfTheList() = runTest {
        val feed = CommentsFeed(
            { _, token, _ -> if (token == null) page(next = "2") else throw VideoSourceError.Network("offline", null) },
            this,
        )
        feed.start()
        advanceUntilIdle()
        val s = feed.state.value
        assertEquals(CommentsFailure.NETWORK, s.failure, "nothing to show and the next page failed: say so, not 'no comments yet'")
        assertNull(s.moreFailure)
        assertFalse(s.empty)
        assertFalse(s.loading)
    }

    @Test fun theFailureKindsAreTold() {
        assertEquals(CommentsFailure.NETWORK, CommentsFailure.of(VideoSourceError.Network("x", null)))
        assertEquals(CommentsFailure.BOT_CHECK, CommentsFailure.of(VideoSourceError.BotCheck("x")))
        assertEquals(CommentsFailure.SOURCE_CHANGED, CommentsFailure.of(VideoSourceError.Parsing("x", null)))
        assertEquals(CommentsFailure.OTHER, CommentsFailure.of(VideoSourceError.NotAvailable("x")))
        assertEquals(CommentsFailure.OTHER, CommentsFailure.of(IllegalStateException("x")))
    }

    // ---- comments that come twice, and pages that bring nothing ----------------------------------------------------

    @Test fun aCommentThatComesTwiceIsShownOnce() = runTest {
        val pages = mapOf<String?, CommentPage>(
            null to page("a", "b", next = "2"),
            "2" to page("b", "c", next = null),
        )
        val feed = CommentsFeed({ _, token, _ -> pages.getValue(token?.value) }, this)
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), feed.state.value.items.map { it.id }, "a repeated id would be a repeated list key")
    }

    @Test fun aPageOfOnlyRepeatsIsFollowedAtOnceByTheNextOne() = runTest {
        val asked = mutableListOf<String?>()
        val pages = mapOf<String?, CommentPage>(
            null to page("a", "b", next = "2"),
            "2" to page("a", "b", next = "3"),
            "3" to page("c", next = null),
        )
        val feed = CommentsFeed({ _, token, _ -> asked += token?.value; pages.getValue(token?.value) }, this)
        feed.start()
        advanceUntilIdle()
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(listOf("a", "b", "c"), feed.state.value.items.map { it.id })
        assertEquals(listOf(null, "2", "3"), asked)
        assertFalse(feed.state.value.hasMore)
    }

    @Test fun anEmptyFirstPageWithAnotherAfterItShowsLoadingNotEmpty() = runTest {
        val gate = CompletableDeferred<CommentPage>()
        val feed = CommentsFeed(
            { _, token, _ -> if (token == null) page(next = "2") else gate.await() },
            this,
        )
        feed.start()
        advanceUntilIdle()
        assertTrue(feed.state.value.loading, "the next page is already on its way")
        assertFalse(feed.state.value.empty)
        gate.complete(page("x"))
        advanceUntilIdle()
        assertEquals(listOf("x"), feed.state.value.items.map { it.id })
        assertFalse(feed.state.value.loading)
    }

    @Test fun pagesOfNothingAreNotFollowedForever() = runTest {
        var asked = 0
        val feed = CommentsFeed({ _, _, _ -> asked++; page(next = "again") }, this)
        feed.start()
        advanceUntilIdle()
        assertTrue(asked <= CommentsFeed.MAX_EMPTY_PAGES, "asked $asked times")
        assertFalse(feed.state.value.loading)
    }

    // ---- closing ------------------------------------------------------------------------------------------------------

    @Test fun anAnswerThatArrivesAfterCloseChangesNothing() = runTest {
        val gate = CompletableDeferred<CommentPage>()
        val feed = CommentsFeed({ _, _, _ -> withContext(NonCancellable) { gate.await() } }, this)
        feed.start()
        advanceUntilIdle()
        val before = feed.state.value
        feed.close()
        gate.complete(page("late"))
        advanceUntilIdle()
        assertEquals(before, feed.state.value)
    }

    // ---- the cache the tile and the panel share --------------------------------------------------------------------

    @Test fun thePanelReusesWhatTheTileLoaded() = runTest {
        val tile = CachedCommentsLoader(source, video, cache)
        assertTrue(tile.preview() is CommentsPreview.Ready)
        val feed = feedFor()
        feed.start()
        advanceUntilIdle()
        assertEquals(1, source.calls.size, "one request served both")
        assertEquals(FakeVideoSource.PAGE_SIZE, feed.state.value.items.size)
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(2, source.calls.size, "the second page is the panel's own")
        assertEquals("comments($video,TOP)", source.calls[0])
    }

    @Test fun theCacheHoldsAnOrderApartFromAnother() = runTest {
        feedFor().also { it.start() }
        advanceUntilIdle()
        feedFor(sort = CommentSort.NEWEST).also { it.start() }
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
        feedFor().also { it.start() }
        feedFor(sort = CommentSort.NEWEST).also { it.start() }
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
    }

    @Test fun aCachedPageIsGoodForTenMinutes() = runTest {
        feedFor().also { it.start() }
        advanceUntilIdle()
        now += CommentsPageCache.TTL_MS - 1
        assertNotNull(cache.get(video, CommentSort.TOP))
        now += 1
        assertNull(cache.get(video, CommentSort.TOP))
        assertEquals(10 * 60_000L, CommentsPageCache.TTL_MS)
        feedFor().also { it.start() }
        advanceUntilIdle()
        assertEquals(2, source.calls.size)
    }

    @Test fun aClockThatWentBackDropsTheEntry() {
        cache.put(video, CommentSort.TOP, page("a"))
        now -= 5_000
        assertNull(cache.get(video, CommentSort.TOP))
    }

    @Test fun theCacheKeepsTheLastEightVideos() {
        for (n in 1..9) cache.put("v$n", CommentSort.TOP, page("a"))
        assertNull(cache.get("v1", CommentSort.TOP), "the oldest went")
        for (n in 2..9) assertNotNull(cache.get("v$n", CommentSort.TOP), "v$n stays")
        assertEquals(8, CommentsPageCache.CAPACITY)
    }

    @Test fun readingAnEntryKeepsItFromBeingTheOldest() {
        for (n in 1..8) cache.put("v$n", CommentSort.TOP, page("a"))
        cache.get("v1", CommentSort.TOP)
        cache.put("v9", CommentSort.TOP, page("a"))
        assertNotNull(cache.get("v1", CommentSort.TOP))
        assertNull(cache.get("v2", CommentSort.TOP))
    }

    @Test fun aRefreshAsksTheSourceAndReplacesTheEntry() = runTest {
        val loader = CachedCommentsLoader(source, video, cache)
        loader.page(CommentSort.TOP, null, fresh = false)
        loader.page(CommentSort.TOP, null, fresh = false)
        assertEquals(1, source.calls.size)
        loader.page(CommentSort.TOP, null, fresh = true)
        assertEquals(2, source.calls.size)
    }

    @Test fun laterPagesNeverGoThroughTheCache() = runTest {
        val loader = CachedCommentsLoader(source, video, cache)
        val first = loader.page(CommentSort.TOP, null, fresh = false)
        loader.page(CommentSort.TOP, first.next, fresh = false)
        loader.page(CommentSort.TOP, first.next, fresh = false)
        assertEquals(3, source.calls.size)
    }

    // ---- the replies --------------------------------------------------------------------------------------------------

    @Test fun repliesPageThroughTheTokenOfTheirComment() = runTest {
        val parent = source.comments(video).items.first { it.replyCount == 12 }
        val feed = CommentsFeed(RepliesLoader(source, requireNotNull(parent.replies)), this)
        feed.start()
        advanceUntilIdle()
        assertEquals(10, feed.state.value.items.size)
        assertTrue(feed.state.value.hasMore)
        assertFalse(feed.state.value.sortable)
        feed.loadMore()
        advanceUntilIdle()
        assertEquals(12, feed.state.value.items.size)
        assertFalse(feed.state.value.hasMore)
        assertEquals(2, source.calls.count { it.startsWith("replies") }, "one call per page of replies")
    }

    // ---- the tile's preview ----------------------------------------------------------------------------------------

    @Test fun thePreviewIsTheFirstCommentAndTheTotal() = runTest {
        val preview = CachedCommentsLoader(source, video, cache).preview()
        assertTrue(preview is CommentsPreview.Ready)
        assertEquals("$video-c01", preview.first?.id)
        assertEquals(25L, preview.total)
    }

    @Test fun thePreviewOfTurnedOffCommentsIsOff() = runTest {
        assertEquals(CommentsPreview.Off, CachedCommentsLoader(source, FakeVideoSource.COMMENTS_OFF_VIDEO, cache).preview())
    }

    @Test fun thePreviewOfAVideoWithoutCommentsIsReadyAndEmpty() = runTest {
        val preview = CommentsLoader { _, _, _ -> page(total = 0) }.preview()
        assertTrue(preview is CommentsPreview.Ready)
        assertNull(preview.first)
    }

    @Test fun thePreviewOfAFailureIsFailedAndLeavesTheCancellationAlone() = runTest {
        source.failNext(VideoSourceError.Network("offline", null))
        assertEquals(CommentsPreview.Failed, CachedCommentsLoader(source, video, cache).preview())
        val cancelling = CommentsLoader { _, _, _ -> throw kotlinx.coroutines.CancellationException("left the screen") }
        var cancelled = false
        try {
            cancelling.preview()
        } catch (_: kotlinx.coroutines.CancellationException) {
            cancelled = true
        }
        assertTrue(cancelled, "a cancelled load is not a failed one")
    }
}
