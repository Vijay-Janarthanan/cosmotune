package com.cosmotune.video.player.controller

import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.ListItem
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.FirstStreamChooser
import com.cosmotune.video.player.NoSegmentDecider
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.RelatedState
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * "Up next" when the details come with no related videos (the extractor returned none): the controller starts playing, asks the
 * source for the list its second way, shows [RelatedState.LOADING] meanwhile, fills the details (and the cache) with the answer,
 * says [RelatedState.EMPTY] or [RelatedState.FAILED] when there is none, retries on request, and autoplay picks from the filled list.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerControllerRelatedTest {

    /** The fakes' source whose details never carry related videos, and whose [related] is scripted. */
    private class BareSource(val fake: FakeVideoSource = FakeVideoSource()) : VideoSource by fake {
        var answer: suspend (String) -> List<ListItem> = { emptyList() }
        val relatedAsked = mutableListOf<String>()

        override suspend fun details(videoId: String): VideoDetails = fake.details(videoId).copy(related = emptyList())

        override suspend fun related(videoId: String): List<ListItem> {
            relatedAsked += videoId
            return answer(videoId)
        }
    }

    private class Rig(test: TestScope) {
        val source = BareSource()
        val engine = FakeVideoPlayerEngine()
        val settings = FakeVideoSettingsRepository(VideoSettings())
        val controller = VideoPlayerController(
            source, engine, FakeVideoLibrary(), settings, FakeSegmentProvider(), FirstStreamChooser, NoSegmentDecider(), { false },
            test.backgroundScope,
        ) { FakeVideoSource.NOW_MS + test.testScheduler.currentTime }

        val state get() = controller.state.value
    }

    private fun item(id: String): VideoItem = FakeVideoSource.sampleItem(id)!!

    private fun videos(vararg ids: String): List<ListItem> = ids.map { ListItem.Video(item(it)) }

    private fun TestScope.open(r: Rig, id: String) {
        r.controller.play(id, null)
        runCurrent()
    }

    @Test
    fun playbackStartsAtOnceAndTheListLoadsAfterIt() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<List<ListItem>>()
        r.source.answer = { gate.await() }
        open(r, "fakevideo01")
        assertTrue(r.engine.lastRequest != null, "the video was handed to the engine without waiting for the list")
        assertEquals(RelatedState.LOADING, r.state.relatedState)
        assertTrue(r.state.details!!.related.isEmpty())
        gate.complete(videos("fakevideo02", "fakevideo03"))
        runCurrent()
        assertEquals(RelatedState.IDLE, r.state.relatedState)
        assertEquals(listOf("fakevideo02", "fakevideo03"), r.state.details!!.related.map { (it as ListItem.Video).item.id })
    }

    @Test
    fun aListThatDoesNotComeIsFailedAndEmptyIsEmpty() = runTest {
        val r = Rig(this)
        r.source.answer = { throw VideoSourceError.Network("offline", null) }
        open(r, "fakevideo01")
        assertEquals(RelatedState.FAILED, r.state.relatedState)
        r.source.answer = { emptyList() }
        r.controller.retryRelated()
        runCurrent()
        assertEquals(RelatedState.EMPTY, r.state.relatedState)
        assertEquals(2, r.source.relatedAsked.size)
    }

    @Test
    fun tryAgainAsksAgainAndFillsTheDetails() = runTest {
        val r = Rig(this)
        r.source.answer = { throw VideoSourceError.Parsing("changed", null) }
        open(r, "fakevideo01")
        assertEquals(RelatedState.FAILED, r.state.relatedState)
        r.source.answer = { videos("fakevideo04") }
        r.controller.retryRelated()
        runCurrent()
        assertEquals(RelatedState.IDLE, r.state.relatedState)
        assertEquals(listOf("fakevideo04"), r.state.details!!.related.map { (it as ListItem.Video).item.id })
    }

    @Test
    fun aRetryWhileLoadingDoesNotAskTwice() = runTest {
        val r = Rig(this)
        r.source.answer = { CompletableDeferred<List<ListItem>>().await() }
        open(r, "fakevideo01")
        r.controller.retryRelated()
        r.controller.retryRelated()
        runCurrent()
        assertEquals(1, r.source.relatedAsked.size)
        assertEquals(RelatedState.LOADING, r.state.relatedState)
    }

    @Test
    fun detailsThatHaveRelatedVideosAskNothingMore() = runTest {
        val r = Rig(this)
        // The plain fake source: its details carry six related videos.
        val plain = VideoPlayerController(
            r.source.fake, r.engine, FakeVideoLibrary(), r.settings, FakeSegmentProvider(), FirstStreamChooser, NoSegmentDecider(), { false },
            backgroundScope,
        ) { FakeVideoSource.NOW_MS + testScheduler.currentTime }
        plain.play("fakevideo01", null)
        runCurrent()
        assertEquals(RelatedState.IDLE, plain.state.value.relatedState)
        assertTrue(plain.state.value.details!!.related.isNotEmpty())
        assertTrue(r.source.relatedAsked.isEmpty())
    }

    @Test
    fun autoplayAtTheEndPlaysTheFirstOfTheFilledList() = runTest {
        val r = Rig(this)
        r.source.answer = { videos("fakevideo05", "fakevideo06") }
        open(r, "fakevideo01")
        assertEquals("fakevideo05", (r.state.details!!.related.first() as ListItem.Video).item.id)
        r.engine.setSnapshot { it.copy(status = PlayerStatus.ENDED, playing = false) }
        runCurrent()
        assertEquals("fakevideo05", r.state.item?.id, "autoplay went on with the fallback's list")
    }

    @Test
    fun aNewVideoDropsTheOldListsRequest() = runTest {
        val r = Rig(this)
        val gate = CompletableDeferred<List<ListItem>>()
        r.source.answer = { id -> if (id == "fakevideo01") gate.await() else videos("fakevideo07") }
        open(r, "fakevideo01")
        open(r, "fakevideo02")
        gate.complete(videos("fakevideo09"))
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
        assertEquals(listOf("fakevideo07"), r.state.details!!.related.map { (it as ListItem.Video).item.id }, "the late answer of the first video is ignored")
    }
}
