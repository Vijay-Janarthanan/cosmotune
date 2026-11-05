package com.cosmotune.video.feed

import com.cosmotune.video.model.ChannelItem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The feed's refresh limit (plan task Y-16, from Y-9's report: a channel that fails is retried on every refresh, about 5 s each,
 * with no back-off): at most one refresh that is not forced per [RefreshLimitedFeed.MIN_INTERVAL_MS], unless a channel was followed
 * since; a forced refresh (pull to refresh, "Try again") always runs.
 */
class RefreshLimitedFeedTest {
    private class CountingFeed : SubscriptionFeed {
        override val state: StateFlow<FeedState> = MutableStateFlow(FeedState(emptyList(), null, emptyList(), false))
        val asked = mutableListOf<Boolean>()
        override suspend fun refresh(force: Boolean) {
            asked += force
        }
    }

    private var now = 50_000_000L
    private val inner = CountingFeed()
    private val channels = MutableStateFlow(setOf("UCa"))
    private val feed = RefreshLimitedFeed(inner, subscriptions = { channels.value }, clock = { now })

    @Test fun theFirstRefreshRunsAndAnotherSoonAfterDoesNot() = runTest {
        feed.refresh(force = false)
        now += 60_000
        feed.refresh(force = false)
        assertEquals(listOf(false), inner.asked)
    }

    @Test fun aRefreshRunsAgainOnceTheIntervalHasPassed() = runTest {
        feed.refresh(force = false)
        now += RefreshLimitedFeed.MIN_INTERVAL_MS - 1
        feed.refresh(force = false)
        now += 1
        feed.refresh(force = false)
        assertEquals(listOf(false, false), inner.asked)
        assertEquals(10 * 60_000L, RefreshLimitedFeed.MIN_INTERVAL_MS)
    }

    @Test fun aForcedRefreshAlwaysRuns() = runTest {
        feed.refresh(force = false)
        feed.refresh(force = true)
        feed.refresh(force = true)
        assertEquals(listOf(false, true, true), inner.asked)
        now += 1_000
        feed.refresh(force = false)
        assertEquals(3, inner.asked.size, "a forced refresh also counts as the last one")
    }

    @Test fun aChannelFollowedSinceTheLastRefreshIsFetchedAtOnce() = runTest {
        feed.refresh(force = false)
        channels.value = setOf("UCa", "UCb")
        now += 1_000
        feed.refresh(force = false)
        assertEquals(listOf(false, false), inner.asked)
        now += 1_000
        channels.value = setOf("UCb")
        feed.refresh(force = false)
        assertEquals(2, inner.asked.size, "an unfollowed channel needs no fetch: the engine drops its videos by itself")
    }

    @Test fun theStateIsTheEnginesOwn() {
        assertSame(inner.state, feed.state)
    }

    @Test fun theFollowedChannelsOfALibraryAreTheirIds() {
        val library = com.cosmotune.video.library.FakeVideoLibrary()
        library.subscribe(ChannelItem("UCx", "X", null, null, null, null))
        val limited = RefreshLimitedFeed(inner, library, clock = { now })
        assertSame(inner.state, limited.state)
    }
}
