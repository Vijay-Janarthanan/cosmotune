package com.cosmotune.video.feed

import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.LibraryFiles
import com.cosmotune.video.model.ChannelItem
import com.cosmotune.video.model.ChannelTab
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.Page
import com.cosmotune.video.model.PageToken
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The feed engine (plan task Y-9, step 3; spec 8.3), with a scripted fetcher, a scripted source and virtual time: retries
 * after 1 s and 4 s, the fallback to the Videos tab, the count of failures, at most 6 at a time, fresh channels skipped,
 * and the cache that survives a restart. Nothing here touches the network.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SubscriptionFeedEngineTest {
    private val mrBeast = FeedFixtures.MRBEAST_CHANNEL_ID
    private val kurzgesagt = FeedFixtures.KURZGESAGT_CHANNEL_ID
    private val start = FakeVideoSource.NOW_MS

    private val notFound = FeedResponse(404, FeedFixtures.notFoundBody)
    private val serverError = FeedResponse(500, "")

    private fun ok(body: String) = FeedResponse(200, body)

    private fun channel(id: String, name: String = "Channel $id"): ChannelItem =
        ChannelItem(id = id, name = name, avatarUrl = "https://example.invalid/avatar/$id.jpg", subscriberCount = null, videoCount = null, description = null)

    private fun idOf(index: Int): String = "UC" + "ch$index".padEnd(22, 'x')

    /** A feed with [count] entries for [channelId], newest first, and no Shorts link. */
    private fun feedOf(channelId: String, count: Int = 3): String = buildString {
        append("<feed>")
        for (i in 1..count) {
            append("<entry><yt:videoId>${channelId.substring(2, 10)}$i</yt:videoId><yt:channelId>$channelId</yt:channelId><title>Upload $i</title>")
            append("<link rel=\"alternate\" href=\"https://www.youtube.com/watch?v=${channelId.substring(2, 10)}$i\"/>")
            append("<published>2026-10-1${count - i}T10:00:00+00:00</published></entry>")
        }
        append("</feed>")
    }

    // ---- scripted collaborators ----

    /** Answers each address from its script in order (the last answer repeats); an unscripted address answers [unscripted]. */
    private class ScriptedFetcher(private val now: () -> Long) : FeedFetcher {
        private val scripts = HashMap<String, List<Any>>()
        private val used = HashMap<String, Int>()
        val log = mutableListOf<Pair<String, Long>>()
        var latencyMs = 0L
        var inFlight = 0
            private set
        var maxInFlight = 0
            private set
        var unscripted: (String) -> Any = { FeedResponse(404, "") }

        fun script(url: String, vararg answers: Any) {
            scripts[url] = answers.toList()
            used[url] = 0
        }

        val urls: List<String> get() = log.map { it.first }

        override suspend fun get(url: String): FeedResponse {
            log += url to now()
            inFlight += 1
            maxInFlight = maxOf(maxInFlight, inFlight)
            try {
                if (latencyMs > 0) delay(latencyMs)
                val list = scripts[url]
                val answer = if (list == null) unscripted(url) else list[minOf(used.getValue(url), list.size - 1)].also { used[url] = used.getValue(url) + 1 }
                when (answer) {
                    is FeedResponse -> return answer
                    is Throwable -> throw answer
                    else -> error("bad script entry $answer")
                }
            } finally {
                inFlight -= 1
            }
        }
    }

    /** The real fake for everything but the Videos tab, which answers what the test sets. */
    private class TabSource : VideoSource by FakeVideoSource() {
        val tabCalls = mutableListOf<String>()
        val failing = mutableSetOf<String>()
        val tabItems = mutableMapOf<String, List<VideoItem>>()

        override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> {
            tabCalls += "$channelId:$tab:${page?.value}"
            if (channelId in failing) throw VideoSourceError.Network("down", null)
            return Page(tabItems[channelId].orEmpty().map { ListItem.Video(it) }, null)
        }
    }

    private class MemoryFiles : LibraryFiles {
        val map = HashMap<String, String>()
        var writes = 0
            private set

        override fun read(name: String): String? = map[name]

        override fun write(name: String, text: String) {
            map[name] = text
            writes += 1
        }
    }

    private fun tabVideo(id: String, channelId: String, at: Long, durationSec: Long? = 600): VideoItem =
        VideoItem(
            id = id, title = "Tab $id", channelId = channelId, channelName = null, channelAvatarUrl = null, thumbnailUrl = "https://example.invalid/$id.jpg",
            durationSec = durationSec, viewCount = 7, uploadedAtMs = at, uploadedText = null,
        )

    private inner class Rig(
        scope: TestScope,
        channels: List<String>,
        hideShorts: Boolean = false,
        val files: MemoryFiles = MemoryFiles(),
        val library: FakeVideoLibrary = FakeVideoLibrary().also { lib -> channels.forEach { lib.subscribe(channel(it)) } },
        val settings: FakeVideoSettingsRepository = FakeVideoSettingsRepository(VideoSettings(hideShorts = hideShorts)),
        val fetcher: ScriptedFetcher = ScriptedFetcher { start + scope.testScheduler.currentTime },
        val source: TabSource = TabSource(),
    ) {
        val clock: () -> Long = { start + scope.testScheduler.currentTime }
        val engine = SubscriptionFeedEngine(library, source, fetcher, files, settings, scope.backgroundScope, clock)
        val state get() = engine.state.value
    }

    private fun TestScope.rig(
        channels: List<String> = listOf(mrBeast),
        hideShorts: Boolean = false,
        files: MemoryFiles = MemoryFiles(),
        library: FakeVideoLibrary = FakeVideoLibrary().also { lib -> channels.forEach { lib.subscribe(channel(it)) } },
        settings: FakeVideoSettingsRepository = FakeVideoSettingsRepository(VideoSettings(hideShorts = hideShorts)),
    ): Rig = Rig(this, channels, hideShorts, files, library, settings)

    // ---- the first step: the feed ----

    @Test
    fun aChannelsFeedBecomesVideoItemsWithoutADurationAndWithTheShortsMarked() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)

        val state = rig.state
        assertEquals(15, state.items.size)
        assertEquals(9, state.items.count { it.isShort })
        assertTrue(state.items.all { it.durationSec == null && !it.isLive && !it.isUpcoming && it.uploadedText == null })
        assertTrue(state.items.all { it.channelId == mrBeast && it.channelName == "Channel $mrBeast" && it.channelAvatarUrl == "https://example.invalid/avatar/$mrBeast.jpg" })
        // The newest upload of the feed is a Short (2026-10-09); the newest long video is of 2026-10-03.
        assertEquals("aNI2jv7lBPQ", state.items.first().id)
        assertEquals("plN7JMbadRg", state.items.first { !it.isShort }.id)
        assertEquals("Eat Everything In A Grocery Store, Win \$1,000,000", state.items.first { !it.isShort }.title)
    }

    @Test
    fun theItemsCarryTheEntrysThumbnailViewsAndPublishedTime() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)

        val short = rig.state.items.first { it.id == "aNI2jv7lBPQ" }
        assertTrue(short.isShort)
        assertEquals("https://i2.ytimg.com/vi/aNI2jv7lBPQ/hqdefault.jpg", short.thumbnailUrl)
        assertEquals(30_266_101L, short.viewCount)
        assertEquals(1_791_561_601_000L, short.uploadedAtMs)
        val long = rig.state.items.first { it.id == "plN7JMbadRg" }
        assertFalse(long.isShort)
    }

    @Test
    fun theFeedIsSortedByUploadTimeNewestFirst() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.engine.refresh(force = false)

        val times = rig.state.items.map { it.uploadedAtMs!! }
        assertEquals(30, times.size)
        assertEquals(times.sortedDescending(), times)
        assertEquals(setOf(mrBeast, kurzgesagt), rig.state.items.mapNotNull { it.channelId }.toSet())
    }

    @Test
    fun withShortsHiddenTheLongFormPlaylistIsReadAndHasNoShorts() = runTest {
        val rig = rig(hideShorts = true)
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, true), ok(FeedFixtures.mrBeastLongFormFeed))
        rig.engine.refresh(force = false)

        assertEquals(listOf("https://www.youtube.com/feeds/videos.xml?playlist_id=UULFX6OQ3DkcsbYNE6H8uQQuVA"), rig.fetcher.urls)
        assertEquals(15, rig.state.items.size)
        assertTrue(rig.state.items.none { it.isShort })
        assertTrue(rig.source.tabCalls.isEmpty())
    }

    // ---- retries and the fallback ----

    @Test
    fun aFeedThatFailsTwiceIsRetriedAfterOneAndFourSecondsAndThenRead() = runTest {
        val rig = rig()
        val url = FeedPolicy.feedUrl(mrBeast, false)
        rig.fetcher.script(url, notFound, serverError, ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)

        assertEquals(listOf(0L, 1_000L, 5_000L), rig.fetcher.log.map { it.second - start })
        assertEquals(listOf(url, url, url), rig.fetcher.urls)
        assertEquals(15, rig.state.items.size)
        assertEquals(emptyList(), rig.state.failedChannels)
        assertTrue(rig.source.tabCalls.isEmpty(), "the fallback is not needed")
    }

    @Test
    fun aFeedThatFailsOnceIsRetriedOnceAfterOneSecond() = runTest {
        val rig = rig()
        val url = FeedPolicy.feedUrl(mrBeast, false)
        rig.fetcher.script(url, serverError, ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)

        assertEquals(listOf(0L, 1_000L), rig.fetcher.log.map { it.second - start })
        assertEquals(15, rig.state.items.size)
    }

    @Test
    fun aFeedThatKeepsFailingFallsBackToTheVideosTabAfterThreeTries() = runTest {
        val rig = rig()
        val url = FeedPolicy.feedUrl(mrBeast, false)
        rig.fetcher.script(url, notFound)
        rig.source.tabItems[mrBeast] = listOf(tabVideo("tabvideo001", mrBeast, 3_000), tabVideo("tabvideo002", mrBeast, 2_000))
        rig.engine.refresh(force = false)

        assertEquals(listOf(0L, 1_000L, 5_000L), rig.fetcher.log.map { it.second - start })
        assertEquals(listOf("$mrBeast:VIDEOS:null"), rig.source.tabCalls)
        assertEquals(listOf("tabvideo001", "tabvideo002"), rig.state.items.map { it.id })
        assertEquals(emptyList(), rig.state.failedChannels)
        // The tab has durations and the channel's name and avatar are filled from the subscription.
        assertEquals(listOf(600L, 600L), rig.state.items.map { it.durationSec })
        assertTrue(rig.state.items.all { it.channelId == mrBeast && it.channelName == "Channel $mrBeast" && it.channelAvatarUrl != null })
    }

    @Test
    fun anAnswerThatIsNotARetryableStatusFallsBackAtOnce() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), FeedResponse(403, "denied"))
        rig.source.tabItems[mrBeast] = listOf(tabVideo("tabvideo001", mrBeast, 3_000))
        rig.engine.refresh(force = false)

        assertEquals(1, rig.fetcher.log.size)
        assertEquals(1, rig.source.tabCalls.size)
        assertEquals(listOf("tabvideo001"), rig.state.items.map { it.id })
    }

    @Test
    fun aNetworkErrorFallsBackAtOnceWithoutWaiting() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), IOException("no route"))
        rig.source.tabItems[mrBeast] = listOf(tabVideo("tabvideo001", mrBeast, 3_000))
        rig.engine.refresh(force = false)

        assertEquals(listOf(0L), rig.fetcher.log.map { it.second - start })
        assertEquals(1, rig.source.tabCalls.size)
        assertEquals(0L, testScheduler.currentTime)
        assertEquals(listOf("tabvideo001"), rig.state.items.map { it.id })
    }

    @Test
    fun anAnswerThatIsNotAFeedFallsBackAtOnce() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.notFoundBody))
        rig.source.tabItems[mrBeast] = listOf(tabVideo("tabvideo001", mrBeast, 3_000))
        rig.engine.refresh(force = false)

        assertEquals(1, rig.fetcher.log.size)
        assertEquals(listOf("tabvideo001"), rig.state.items.map { it.id })
    }

    @Test
    fun theLongFormFeedWithZeroEntriesFallsBackToTheVideosTab() = runTest {
        val rig = rig(hideShorts = true)
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, true), ok("<?xml version=\"1.0\"?><feed xmlns=\"http://www.w3.org/2005/Atom\"><title>Videos</title></feed>"))
        rig.source.tabItems[mrBeast] = listOf(tabVideo("tabvideo001", mrBeast, 3_000))
        rig.engine.refresh(force = false)

        assertEquals(1, rig.fetcher.log.size, "an answer of 200 is not retried")
        assertEquals(listOf("$mrBeast:VIDEOS:null"), rig.source.tabCalls)
        assertEquals(listOf("tabvideo001"), rig.state.items.map { it.id })
    }

    @Test
    fun theChannelFeedWithZeroEntriesIsAnEmptyChannelNotAFailure() = runTest {
        val rig = rig(hideShorts = false)
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok("<feed></feed>"))
        rig.engine.refresh(force = false)

        assertTrue(rig.source.tabCalls.isEmpty())
        assertEquals(emptyList(), rig.state.items)
        assertEquals(emptyList(), rig.state.failedChannels)
    }

    @Test
    fun aChannelThatFailsAllThreeWaysIsCountedAndTheOthersAreKept() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), notFound)
        rig.source.failing += kurzgesagt
        rig.engine.refresh(force = false)

        assertEquals(listOf(kurzgesagt), rig.state.failedChannels)
        assertEquals(15, rig.state.items.size)
        assertTrue(rig.state.items.all { it.channelId == mrBeast })
        assertEquals(start + testScheduler.currentTime, rig.state.updatedAtMs)
        assertFalse(rig.state.refreshing)
    }

    @Test
    fun aFailedChannelIsTriedAgainOnTheNextRefreshEvenWithoutForce() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), notFound, notFound, notFound, ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.source.failing += kurzgesagt
        rig.engine.refresh(force = false)
        assertEquals(listOf(kurzgesagt), rig.state.failedChannels)

        rig.engine.refresh(force = false)
        assertEquals(emptyList(), rig.state.failedChannels)
        assertEquals(30, rig.state.items.size)
        // MrBeast's was fresh and not fetched again: one request for it, four for the other.
        assertEquals(1, rig.fetcher.urls.count { it == FeedPolicy.feedUrl(mrBeast, false) })
        assertEquals(4, rig.fetcher.urls.count { it == FeedPolicy.feedUrl(kurzgesagt, false) })
    }

    @Test
    fun aChannelThatFailsNowKeepsWhatItShowedBefore() = runTest {
        val rig = rig()
        val url = FeedPolicy.feedUrl(mrBeast, false)
        rig.fetcher.script(url, ok(FeedFixtures.mrBeastChannelFeed), notFound)
        rig.engine.refresh(force = false)
        val before = rig.state.items
        val updated = rig.state.updatedAtMs
        rig.source.failing += mrBeast

        advanceTimeBy(45 * 60_000L)
        rig.engine.refresh(force = false)

        assertEquals(before, rig.state.items)
        assertEquals(listOf(mrBeast), rig.state.failedChannels)
        assertEquals(updated, rig.state.updatedAtMs, "nothing was read, so nothing was updated")
    }

    @Test
    fun anUnexpectedErrorOfTheSourceIsAFailureNotACrash() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), notFound)
        val broken = object : VideoSource by FakeVideoSource() {
            override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> = error("boom")
        }
        val engine = SubscriptionFeedEngine(rig.library, broken, rig.fetcher, rig.files, rig.settings, backgroundScope, rig.clock)
        engine.refresh(force = false)
        assertEquals(listOf(mrBeast), engine.state.value.failedChannels)
    }

    // ---- concurrency and freshness ----

    @Test
    fun neverMoreThanSixChannelsAreFetchedAtOnce() = runTest {
        val channels = (1..20).map { idOf(it) }
        val rig = rig(channels = channels)
        rig.fetcher.latencyMs = 100
        rig.fetcher.unscripted = { url -> ok(feedOf("UC" + url.substringAfter("channel_id=UC"))) }
        rig.engine.refresh(force = true)

        assertEquals(6, rig.fetcher.maxInFlight)
        assertEquals(20, rig.fetcher.log.size)
        assertEquals(20, rig.fetcher.urls.toSet().size)
        assertEquals(60, rig.state.items.size)
        assertEquals(emptyList(), rig.state.failedChannels)
        // 20 channels, 6 at a time, 100 ms each: four rounds.
        assertEquals(400L, testScheduler.currentTime)
    }

    @Test
    fun aFallingBackChannelAlsoCountsAgainstTheSix() = runTest {
        val channels = (1..12).map { idOf(it) }
        val rig = rig(channels = channels)
        rig.fetcher.latencyMs = 10
        // Every feed fails, so every channel retries (1 s + 4 s) and then falls back; none may start a seventh.
        rig.fetcher.unscripted = { notFound }
        val live = intArrayOf(0)
        var peak = 0
        val counting = object : VideoSource by FakeVideoSource() {
            override suspend fun channelTab(channelId: String, tab: ChannelTab, page: PageToken?): Page<ListItem> {
                live[0] += 1
                peak = maxOf(peak, live[0])
                delay(50)
                live[0] -= 1
                return Page(emptyList(), null)
            }
        }
        val engine = SubscriptionFeedEngine(rig.library, counting, rig.fetcher, rig.files, rig.settings, backgroundScope, rig.clock)
        engine.refresh(force = true)

        assertTrue(rig.fetcher.maxInFlight <= 6)
        assertTrue(peak <= 6)
        assertEquals(12 * 3, rig.fetcher.log.size)
    }

    @Test
    fun aRefreshWithoutForceSkipsChannelsFetchedInTheLastThirtyMinutes() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))

        rig.engine.refresh(force = false)
        assertEquals(2, rig.fetcher.log.size)

        rig.engine.refresh(force = false)
        assertEquals(2, rig.fetcher.log.size, "both are fresh")

        advanceTimeBy(29 * 60_000L)
        rig.engine.refresh(force = false)
        assertEquals(2, rig.fetcher.log.size, "29 minutes is still fresh")

        advanceTimeBy(2 * 60_000L)
        rig.engine.refresh(force = false)
        assertEquals(4, rig.fetcher.log.size, "31 minutes is due")
    }

    @Test
    fun aPullToRefreshFetchesEveryChannelEvenWhenFresh() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.engine.refresh(force = false)
        rig.engine.refresh(force = true)
        assertEquals(4, rig.fetcher.log.size)
    }

    @Test
    fun aNewlyFollowedChannelIsFetchedWhileTheFreshOnesAreNot() = runTest {
        val rig = rig(channels = listOf(mrBeast))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.engine.refresh(force = false)

        rig.library.subscribe(channel(kurzgesagt))
        rig.engine.refresh(force = false)

        assertEquals(listOf(FeedPolicy.feedUrl(mrBeast, false), FeedPolicy.feedUrl(kurzgesagt, false)), rig.fetcher.urls)
        assertEquals(30, rig.state.items.size)
    }

    @Test
    fun theStateTellsWhenARefreshRunsAndWhenItWasUpdated() = runTest {
        val rig = rig()
        rig.fetcher.latencyMs = 500
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        assertNull(rig.state.updatedAtMs)
        assertFalse(rig.state.refreshing)

        val job = launch { rig.engine.refresh(force = false) }
        runCurrent()
        assertTrue(rig.state.refreshing)
        assertEquals(emptyList(), rig.state.items)

        advanceUntilIdle()
        job.join()
        assertFalse(rig.state.refreshing)
        assertEquals(start + 500, rig.state.updatedAtMs)
        assertEquals(15, rig.state.items.size)
    }

    @Test
    fun aRefreshWithNothingDueLeavesTheFeedAndTheTimeAlone() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)
        val before = rig.state
        advanceTimeBy(5 * 60_000L)
        rig.engine.refresh(force = false)
        assertEquals(before, rig.state)
        assertEquals(1, rig.files.writes)
    }

    @Test
    fun withNoSubscriptionsARefreshDoesNothing() = runTest {
        val rig = rig(channels = emptyList())
        rig.engine.refresh(force = true)
        assertEquals(FeedState(emptyList(), null, emptyList(), false), rig.state)
        assertTrue(rig.fetcher.log.isEmpty())
    }

    @Test
    fun aRefreshGoesOnWhenTheCallerLeaves() = runTest {
        val rig = rig()
        rig.fetcher.latencyMs = 500
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        val caller = launch { rig.engine.refresh(force = true) }
        runCurrent()
        caller.cancel()
        // Nothing but the engine's own work is left, which advanceUntilIdle does not wait for: move the clock.
        advanceTimeBy(1_000)
        runCurrent()
        assertEquals(15, rig.state.items.size)
        assertFalse(rig.state.refreshing)
    }

    @Test
    fun onRealThreadsNothingIsLostAndTheLimitHolds() = runBlocking {
        val channels = (1..60).map { idOf(it) }
        val library = FakeVideoLibrary().also { lib -> channels.forEach { lib.subscribe(channel(it)) } }
        val current = AtomicInteger()
        val peak = AtomicInteger()
        val fetcher = object : FeedFetcher {
            override suspend fun get(url: String): FeedResponse {
                peak.accumulateAndGet(current.incrementAndGet()) { a, b -> maxOf(a, b) }
                try {
                    delay(5)
                    return ok(feedOf("UC" + url.substringAfter("channel_id=UC")))
                } finally {
                    current.decrementAndGet()
                }
            }
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val engine = SubscriptionFeedEngine(library, TabSource(), fetcher, MemoryFiles(), FakeVideoSettingsRepository(), scope) { System.currentTimeMillis() }
            engine.refresh(force = true)
            assertEquals(180, engine.state.value.items.size)
            assertEquals(emptyList(), engine.state.value.failedChannels)
            assertFalse(engine.state.value.refreshing)
            assertTrue(peak.get() in 2..6, "peak ${peak.get()}")
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun aChannelThatFailsWhenTheSettingChangedIsNotMistakenForFreshLater() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, true), ok(FeedFixtures.mrBeastLongFormFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, true), notFound, notFound, notFound, ok("<feed><entry><yt:videoId>kurzLong001</yt:videoId></entry></feed>"))
        rig.engine.refresh(force = false)
        runCurrent()

        rig.settings.update { it.copy(hideShorts = true) }
        runCurrent()
        rig.source.failing += kurzgesagt
        rig.engine.refresh(force = false)
        assertEquals(listOf(kurzgesagt), rig.state.failedChannels)

        // Kurzgesagt's fetch time from the other feed is gone, so it is read again at once; MrBeast's is fresh.
        rig.source.failing -= kurzgesagt
        rig.engine.refresh(force = false)
        assertEquals(emptyList(), rig.state.failedChannels)
        assertTrue(rig.state.items.any { it.id == "kurzLong001" })
        assertEquals(1, rig.fetcher.urls.count { it == FeedPolicy.feedUrl(mrBeast, true) })
    }

    @Test
    fun theFailedChannelsComeInTheOrderOfTheSubscriptions() = runTest {
        val channels = (1..5).map { idOf(it) }
        val rig = rig(channels = channels)
        rig.fetcher.unscripted = { notFound }
        channels.forEach { rig.source.failing += it }
        rig.engine.refresh(force = true)
        assertEquals(channels, rig.state.failedChannels)
    }

    // ---- Shorts and the setting ----

    @Test
    fun switchingHideShortsOnDropsTheShortsAtOnceAndOffBringsThemBack() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)
        assertEquals(15, rig.state.items.size)
        runCurrent()

        rig.settings.update { it.copy(hideShorts = true) }
        runCurrent()
        assertEquals(6, rig.state.items.size)
        assertTrue(rig.state.items.none { it.isShort })

        rig.settings.update { it.copy(hideShorts = false) }
        runCurrent()
        assertEquals(15, rig.state.items.size)
    }

    @Test
    fun afterTheSettingChangesFreshChannelsAreFetchedAgainFromTheOtherFeed() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, true), ok(FeedFixtures.mrBeastLongFormFeed))
        rig.engine.refresh(force = false)
        runCurrent()

        rig.settings.update { it.copy(hideShorts = true) }
        runCurrent()
        rig.engine.refresh(force = false)
        assertEquals(listOf(FeedPolicy.feedUrl(mrBeast, false), FeedPolicy.feedUrl(mrBeast, true)), rig.fetcher.urls)
        assertEquals(15, rig.state.items.size)
        assertTrue(rig.state.items.none { it.isShort })

        // Fresh again under the new setting: no more requests.
        rig.engine.refresh(force = false)
        assertEquals(2, rig.fetcher.log.size)
    }

    @Test
    fun unfollowingAChannelRemovesItsVideosFromTheFeed() = runTest {
        val rig = rig(channels = listOf(mrBeast, kurzgesagt))
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        rig.engine.refresh(force = false)
        runCurrent()
        assertEquals(30, rig.state.items.size)

        rig.library.unsubscribe(kurzgesagt)
        runCurrent()
        assertEquals(15, rig.state.items.size)
        assertTrue(rig.state.items.all { it.channelId == mrBeast })

        rig.engine.refresh(force = true)
        val cache = Json.parseToJsonElement(assertNotNull(rig.files.map[SubscriptionFeedEngine.CACHE_FILE])).jsonObject
        val channels = cache.getValue("items").jsonArray.map { it.jsonObject.getValue("channelId").jsonPrimitive.content }.toSet()
        assertEquals(setOf(mrBeast), channels)
    }

    // ---- the cache ----

    @Test
    fun theResultIsKeptInFeedCacheJsonWithItsVersion() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        rig.engine.refresh(force = false)

        assertEquals("feed-cache.json", SubscriptionFeedEngine.CACHE_FILE)
        val cache: JsonObject = Json.parseToJsonElement(assertNotNull(rig.files.map["feed-cache.json"])).jsonObject
        assertEquals("1", cache.getValue("version").jsonPrimitive.content)
        assertEquals(15, (cache.getValue("items") as JsonArray).size)
        assertEquals(1, rig.files.writes)
    }

    @Test
    fun aNewEngineOpensOnTheLastFeedAtOnceAndKeepsTheFetchTimes() = runTest {
        val files = MemoryFiles()
        val library = FakeVideoLibrary().also { it.subscribe(channel(mrBeast)); it.subscribe(channel(kurzgesagt)) }
        val settings = FakeVideoSettingsRepository()
        val first = rig(files = files, library = library, settings = settings)
        first.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        first.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), notFound)
        first.source.failing += kurzgesagt
        first.engine.refresh(force = false)
        val saved = first.state
        assertEquals(listOf(kurzgesagt), saved.failedChannels)
        advanceTimeBy(10 * 60_000L)

        // A new process: same folder, nothing fetched yet.
        val second = rig(files = files, library = library, settings = settings)
        runCurrent()
        assertEquals(saved, second.state)
        assertEquals(15, second.state.items.size)
        assertTrue(second.fetcher.log.isEmpty())

        // MrBeast was fetched ten minutes ago: still fresh. The failed channel has no fetch time: due.
        second.fetcher.script(FeedPolicy.feedUrl(kurzgesagt, false), ok(FeedFixtures.kurzgesagtChannelFeed))
        second.engine.refresh(force = false)
        assertEquals(listOf(FeedPolicy.feedUrl(kurzgesagt, false)), second.fetcher.urls)
        assertEquals(30, second.state.items.size)
        assertEquals(emptyList(), second.state.failedChannels)
    }

    @Test
    fun aRefreshRightAfterStartWaitsForTheCacheToLoad() = runTest {
        val files = MemoryFiles()
        val library = FakeVideoLibrary().also { it.subscribe(channel(mrBeast)) }
        val first = rig(files = files, library = library)
        first.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        first.engine.refresh(force = false)

        val second = rig(files = files, library = library)
        second.engine.refresh(force = false) // no runCurrent() before: the load has not run yet
        assertTrue(second.fetcher.log.isEmpty(), "the cached fetch time was read before the decision")
        assertEquals(15, second.state.items.size)
    }

    @Test
    fun aCacheOfTheOtherSettingKeepsItsItemsButEveryChannelIsDue() = runTest {
        val files = MemoryFiles()
        val library = FakeVideoLibrary().also { it.subscribe(channel(mrBeast)) }
        val first = rig(files = files, library = library, hideShorts = false)
        first.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
        first.engine.refresh(force = false)

        val second = rig(files = files, library = library, hideShorts = true)
        second.fetcher.script(FeedPolicy.feedUrl(mrBeast, true), ok(FeedFixtures.mrBeastLongFormFeed))
        runCurrent()
        assertEquals(6, second.state.items.size, "the cached Shorts are not shown while they are hidden")
        second.engine.refresh(force = false)
        assertEquals(listOf(FeedPolicy.feedUrl(mrBeast, true)), second.fetcher.urls)
        assertEquals(15, second.state.items.size)
    }

    @Test
    fun theCacheKeepsAtMostTwoThousandItems() = runTest {
        val rig = rig()
        rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), notFound)
        rig.source.tabItems[mrBeast] = (1..2_500).map { tabVideo("tab%05d".format(it), mrBeast, it.toLong() * 1_000) }
        rig.engine.refresh(force = false)

        assertEquals(2_000, rig.state.items.size)
        assertEquals("tab02500", rig.state.items.first().id)
        val cache = Json.parseToJsonElement(assertNotNull(rig.files.map["feed-cache.json"])).jsonObject
        assertEquals(2_000, cache.getValue("items").jsonArray.size)
    }

    @Test
    fun aBrokenOrNewerCacheFileIsIgnored() = runTest {
        for (text in listOf("", "not json at all", "{\"version\": 99, \"items\": []}", "[]", "{\"version\": 1, \"items\": [{\"id\": 5}]}")) {
            val files = MemoryFiles().also { it.map["feed-cache.json"] = text }
            val rig = rig(files = files)
            runCurrent()
            assertEquals(emptyList(), rig.state.items, "cache text: $text")
            rig.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), ok(FeedFixtures.mrBeastChannelFeed))
            rig.engine.refresh(force = false)
            assertEquals(15, rig.state.items.size, "cache text: $text")
        }
    }

    @Test
    fun theCacheKeepsEveryFieldOfAFallbackItem() = runTest {
        val files = MemoryFiles()
        val library = FakeVideoLibrary().also { it.subscribe(channel(mrBeast)) }
        val first = rig(files = files, library = library)
        first.fetcher.script(FeedPolicy.feedUrl(mrBeast, false), notFound)
        val item = VideoItem(
            id = "tabvideo001", title = "A \"quoted\" title ’ 😀", channelId = mrBeast, channelName = null, channelAvatarUrl = null,
            thumbnailUrl = "https://example.invalid/t.jpg", durationSec = 754, viewCount = 12_345, uploadedAtMs = 1_791_561_601_000L,
            uploadedText = "2 weeks ago", isLive = false, isUpcoming = true, isShort = true,
        )
        first.source.tabItems[mrBeast] = listOf(item)
        first.engine.refresh(force = false)

        val second = rig(files = files, library = library)
        runCurrent()
        val expected = item.copy(channelName = "Channel $mrBeast", channelAvatarUrl = "https://example.invalid/avatar/$mrBeast.jpg")
        assertEquals(listOf(expected), first.state.items)
        assertEquals(listOf(expected), second.state.items)
    }
}
