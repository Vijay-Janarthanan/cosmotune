package com.cosmotune.video.source.newpipe

import com.cosmotune.video.account.AccountFailure
import com.cosmotune.video.account.RelatedFallback
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Which related list "Up next" gets: the extractor's own when it has videos, else the site's `next` answer (the fallback), asked once
 * per video and kept ten minutes; a failed or empty answer is never kept, so asking again asks again.
 */
class NewPipeRelatedFallbackTest {

    @BeforeTest
    fun setUp() {
        NewPipeHost.resetForTests()
        NewPipeHost.ensure()
    }

    @AfterTest
    fun tearDown() {
        NewPipeHost.resetForTests()
    }

    private class Clock(var now: Long)

    private class Harness(val source: NewPipeVideoSource, val asked: MutableList<String>, val clock: Clock)

    private fun video(id: String) = VideoItem(
        id = id, title = "Fallback $id", channelId = "UC1", channelName = "Channel", channelAvatarUrl = "https://yt3.ggpht.com/a",
        thumbnailUrl = "https://i.ytimg.com/vi/$id/hqdefault.jpg", durationSec = 215, viewCount = 1_000_000, uploadedAtMs = null, uploadedText = "2y ago",
    )

    /** A source whose watch page has [own] related items and whose fallback answers [answer] (and records the ids it was asked for). */
    private fun harness(own: List<String> = emptyList(), answer: suspend (String) -> List<VideoItem> = { listOf(video("fallbackAAA"), video("fallbackBBB")) }): Harness {
        val clock = Clock(1_792_152_000_000L)
        val asked = mutableListOf<String>()
        val source = NewPipeVideoSource(clock = { clock.now }, relatedFallback = RelatedFallback { id ->
            synchronized(asked) { asked += id }
            answer(id)
        })
        source.streamInfoLoader = { url ->
            val id = NewPipeMapper.videoIdOf(url) ?: error("no id in $url")
            StreamInfo(0, url, url, StreamType.VIDEO_STREAM, id, "A video", 0).also { info ->
                info.duration = 120
                info.relatedItems = own.map { StreamInfoItem(0, "https://www.youtube.com/watch?v=$it", "Own $it", StreamType.VIDEO_STREAM).also { item -> item.duration = 60 } }
            }
        }
        return Harness(source, asked, clock)
    }

    @Test
    fun theExtractorsListIsUsedWhenItHasVideosAndTheSiteIsNeverAsked() = runTest {
        val h = harness(own = listOf("ownAAAAAAAA", "ownBBBBBBBB"))
        val related = h.source.related("jNQXAC9IVRw")
        assertEquals(listOf("ownAAAAAAAA", "ownBBBBBBBB"), related.map { (it as ListItem.Video).item.id })
        assertTrue(h.asked.isEmpty(), "no second request when the first source had videos")
    }

    @Test
    fun anEmptyListFromTheExtractorIsFilledFromTheSite() = runTest {
        val h = harness()
        val related = h.source.related("jNQXAC9IVRw")
        assertEquals(listOf("fallbackAAA", "fallbackBBB"), related.map { (it as ListItem.Video).item.id })
        assertEquals(listOf("jNQXAC9IVRw"), h.asked)
        val first = (related[0] as ListItem.Video).item
        assertEquals("https://yt3.ggpht.com/a", first.channelAvatarUrl, "the channel picture comes through")
        assertEquals(215, first.durationSec)
        assertEquals(1_000_000L, first.viewCount)
    }

    @Test
    fun theSecondRequestIsMadeOncePerVideoAndKeptTenMinutes() = runTest {
        val h = harness()
        h.source.related("jNQXAC9IVRw")
        h.clock.now += 9 * 60_000L + 59_000L
        h.source.related("jNQXAC9IVRw")
        h.source.related("https://youtu.be/jNQXAC9IVRw")
        assertEquals(1, h.asked.size, "one request for the video, whichever way it is named")
        h.clock.now += 2_000L
        h.source.related("jNQXAC9IVRw")
        assertEquals(2, h.asked.size, "asked again after ten minutes")
        h.source.related("dQw4w9WgXcQ")
        assertEquals(listOf("jNQXAC9IVRw", "jNQXAC9IVRw", "dQw4w9WgXcQ"), h.asked)
    }

    @Test
    fun theDetailsThemselvesStayWhatTheExtractorGave() = runTest {
        val h = harness()
        val details = h.source.details("jNQXAC9IVRw")
        assertTrue(details.related.isEmpty(), "details() makes no second request: playback never waits for the list")
        assertTrue(h.asked.isEmpty())
    }

    @Test
    fun aFailureIsAVideoSourceErrorAndIsNotKept() = runTest {
        var failing = true
        val h = harness(answer = { id -> if (failing) throw IOException("offline") else listOf(video("fallbackAAA")) })
        assertIs<VideoSourceError.Network>(assertFailsWith<VideoSourceError> { h.source.related("jNQXAC9IVRw") })
        failing = false
        assertEquals(listOf("fallbackAAA"), h.source.related("jNQXAC9IVRw").map { (it as ListItem.Video).item.id })
        assertEquals(2, h.asked.size, "the failure was not remembered")
    }

    @Test
    fun aChangedSiteIsAParsingError() = runTest {
        val h = harness(answer = { throw AccountFailure.Changed("The related videos could not be read") })
        assertIs<VideoSourceError.Parsing>(assertFailsWith<VideoSourceError> { h.source.related("jNQXAC9IVRw") })
    }

    @Test
    fun noSuggestionsIsAnEmptyListAndAskedAgainNextTime() = runTest {
        val h = harness(answer = { emptyList() })
        assertEquals(emptyList(), h.source.related("jNQXAC9IVRw"))
        assertEquals(emptyList(), h.source.related("jNQXAC9IVRw"))
        assertEquals(2, h.asked.size, "an empty answer is not kept: the retry button really asks")
    }

    @Test
    fun twoAskersOfTheSameVideoShareOneRequest() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val h = harness(answer = { release.await(); listOf(video("fallbackAAA")) })
        val both = listOf(async { h.source.related("jNQXAC9IVRw") }, async { h.source.related("jNQXAC9IVRw") })
        release.complete(Unit)
        val answers = both.awaitAll()
        assertEquals(answers[0], answers[1])
        assertEquals(1, h.asked.size)
    }

    @Test
    fun aRegionChangeForgetsTheKeptList() = runTest {
        val h = harness()
        h.source.related("jNQXAC9IVRw")
        h.source.setRegion("IN", "hi")
        h.source.related("jNQXAC9IVRw")
        assertEquals(2, h.asked.size, "the list comes back in the new region's language")
    }
}
