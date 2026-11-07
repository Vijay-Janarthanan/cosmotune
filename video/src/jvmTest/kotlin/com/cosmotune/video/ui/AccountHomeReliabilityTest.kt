package com.cosmotune.video.ui

import com.cosmotune.video.account.*
import com.cosmotune.video.model.*
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.home.AccountHomeFeed
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import kotlin.test.*

@OptIn(ExperimentalCoroutinesApi::class)
class AccountHomeReliabilityTest {
    private class Account : VideoAccount by FakeVideoAccount() {
        val calls = mutableListOf<String?>()
        var fetch: suspend (PageToken?) -> Result<Page<VideoItem>> = { Result.success(Page(FakeVideoSource.videos.take(2), PageToken("next"))) }
        override suspend fun home(continuation: PageToken?): Result<Page<VideoItem>> { calls += continuation?.value; return fetch(continuation) }
    }
    @Test fun firstFailureIsExplicitAndRetryDoesNotRequireDeletingLocalSuggestions() = runTest {
        val account = Account()
        account.fetch = { Result.failure(AccountFailure.SessionExpired()) }
        val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent()
        assertTrue(feed.loaded && feed.firstError && feed.sessionExpired)
        account.fetch = { Result.success(Page(FakeVideoSource.videos.take(2), PageToken("p2"))) }
        feed.retry(); runCurrent()
        assertEquals(2, feed.items.size); assertFalse(feed.firstError); assertTrue(feed.hasMore)
    }
    @Test fun nextFailureKeepsCardsAndTokenButOnlyExplicitRetryRestartsIt() = runTest {
        val account = Account(); val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent()
        account.fetch = { Result.failure(AccountFailure.Network("offline")) }
        feed.loadMore(); feed.loadMore(); runCurrent()
        assertTrue(feed.moreError); assertEquals(2, feed.items.size)
        feed.loadMore(); runCurrent(); assertEquals(listOf(null, "next"), account.calls)
        account.fetch = { Result.success(Page(FakeVideoSource.videos.drop(2).take(2), null)) }
        feed.retry(); runCurrent()
        assertEquals(listOf(null, "next", "next"), account.calls)
        assertEquals(4, feed.items.size); assertFalse(feed.moreError); assertTrue(feed.exhausted)
    }
    @Test fun repeatedContinuationStopsInsteadOfRequestingAnEndlessLoop() = runTest {
        val account = Account(); val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent()
        feed.loadMore(); runCurrent()
        assertTrue(feed.repeatedPage); assertTrue(feed.exhausted)
        repeat(3) { feed.loadMore() }; runCurrent()
        assertEquals(listOf(null, "next"), account.calls)
    }
    @Test fun aLateAnswerAfterAccountDisposalCannotPublishIntoTheOldFeed() = runTest {
        val account = Account(); val gate = CompletableDeferred<Unit>()
        account.fetch = { withContext(NonCancellable) { gate.await() }; Result.success(Page(FakeVideoSource.videos, null)) }
        val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent(); feed.close(); gate.complete(Unit); runCurrent()
        assertTrue(feed.items.isEmpty()); assertFalse(feed.loaded)
    }
    @Test fun comingBackShowsTheAccountsCardsAtOnceAndAFailedRefreshKeepsThem() = runTest {
        val account = Account(); val feed = AccountHomeFeed(account, backgroundScope)
        feed.start(); runCurrent(); feed.close()
        // Home opened again (a new feed over the same account): the cards are there in the first frame, and nothing is asked again
        // inside the freshness window.
        val back = AccountHomeFeed(account, backgroundScope)
        assertEquals(2, back.items.size); assertTrue(back.loaded)
        back.start(); runCurrent()
        assertEquals(listOf<String?>(null), account.calls)
        // Refresh asks at once; when it fails the cards stay, with the notice.
        account.fetch = { Result.failure(AccountFailure.Network("offline")) }
        back.start(force = true); assertTrue(back.loaded, "no placeholders over the cards while they refresh"); runCurrent()
        assertEquals(listOf<String?>(null, null), account.calls)
        assertEquals(2, back.items.size); assertTrue(back.firstError)
    }
}
