package com.cosmotune.video.player.controller

import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.StreamKind
import com.cosmotune.video.model.VideoDetails
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.FakeVideoPlayerEngine.Call
import com.cosmotune.video.player.FirstStreamChooser
import com.cosmotune.video.player.LiveWindow
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.NoSegmentDecider
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.Selection
import com.cosmotune.video.player.StreamChooser
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The controller's live rules (plan task Y-28, step 1; spec 5.13): a live stream plays its HLS address at the live edge (Auto only,
 * no resume, no history, no SponsorBlock), seeks stay in its window, and an end loads it again once before it counts; an upcoming
 * video never reaches the engine: the state is UNAVAILABLE with the premiere and its start time. `fakevideo11` is the fakes' live
 * stream, `fakevideo12` their premiere (two days after [FakeVideoSource.NOW_MS]).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerControllerLiveTest {

    private val hour = 3_600_000L

    /** The fakes' source, with two switches for live streams: NewPipe can list video-only streams for one, and a broadcast ends. */
    private class LiveSource(val fake: FakeVideoSource = FakeVideoSource()) : VideoSource by fake {
        var withVideoOnly = false
        var broadcastOver = false

        override suspend fun details(videoId: String): VideoDetails {
            val d = fake.details(videoId)
            if (!d.item.isLive) return d
            var out = d
            if (withVideoOnly) {
                val vod = FakeVideoSource.streamSetFor(FakeVideoSource.sampleItem("fakevideo01")!!, FakeVideoSource.NOW_MS)
                out = out.copy(streams = out.streams.copy(videoOnly = vod.videoOnly, audio = vod.audio))
            }
            if (broadcastOver) out = out.copy(kind = StreamKind.POST_LIVE, item = out.item.copy(isLive = false), streams = out.streams.copy(hlsUrl = null))
            return out
        }
    }

    /** The fakes' chooser, counted. */
    private class CountingChooser : StreamChooser {
        var count = 0

        override fun choose(
            streams: com.cosmotune.video.model.StreamSet, caps: EngineCapabilities, settings: VideoSettings, metered: Boolean,
            manual: QualityOption?, audioTrackId: String?, bandwidthKbps: Int?, durationMs: Long,
        ): Selection {
            count++
            return FirstStreamChooser.choose(streams, caps, settings, metered, manual, audioTrackId, bandwidthKbps, durationMs)
        }
    }

    private class Rig(test: TestScope, settings: VideoSettings) {
        val source = LiveSource()
        val engine = FakeVideoPlayerEngine()
        val library = FakeVideoLibrary()
        val settings = FakeVideoSettingsRepository(settings)
        val segments = FakeSegmentProvider()
        val chooser = CountingChooser()
        val controller = VideoPlayerController(
            source, engine, library, this.settings, segments, chooser, NoSegmentDecider(), { false }, test.backgroundScope,
        ) { FakeVideoSource.NOW_MS + test.testScheduler.currentTime }

        val state get() = controller.state.value
        val loads: List<PlaybackRequest> get() = engine.calls.filterIsInstance<Call.Load>().map { it.request }
        val seeks: List<Long> get() = engine.calls.filterIsInstance<Call.SeekTo>().map { it.ms }
        fun detailsAsked(id: String) = source.fake.calls.count { it == "details($id)" }
    }

    private fun TestScope.rig(settings: VideoSettings = VideoSettings()) = Rig(this, settings)

    private fun TestScope.open(r: Rig, id: String, startMs: Long? = null) {
        r.controller.play(id, startMs)
        runCurrent()
    }

    /** The live window the engine reports: its end is [durationMs]. */
    private fun TestScope.window(r: Rig, durationMs: Long) {
        r.engine.setSnapshot { it.copy(durationMs = durationMs) }
        runCurrent()
    }

    private fun TestScope.end(r: Rig) {
        r.engine.setSnapshot { it.copy(status = PlayerStatus.ENDED, playing = false) }
        runCurrent()
    }

    private fun item(id: String): VideoItem = FakeVideoSource.sampleItem(id)!!

    // ---------------------------------------------------------------- a live stream

    @Test
    fun aLiveStreamPlaysItsHlsAddressWithoutTheChooser() = runTest {
        val r = rig()
        r.source.withVideoOnly = true            // NewPipe can list the live stream's video-only streams too
        open(r, "fakevideo11")
        val request = assertNotNull(r.engine.lastRequest)
        assertTrue(request.live)
        assertEquals(MediaInput.Hls("https://media.cosmotune.invalid/live/fakevideo11/index.m3u8"), request.input)
        assertEquals(0, r.chooser.count, "no ladder beyond what HLS gives")
        assertTrue(r.state.live)
        assertTrue(r.state.ladder.isEmpty())
        assertNull(r.state.quality)
        assertTrue(r.state.autoQuality)
        assertTrue(r.state.audioTracks.isEmpty(), "HLS picks its own audio")
    }

    @Test
    fun aVideoStillGoesToTheChooser() = runTest {
        val r = rig()
        open(r, "fakevideo01")
        assertEquals(1, r.chooser.count)
        assertFalse(r.state.live)
    }

    @Test
    fun aLiveStreamStartsAtTheEdgeWhateverStartIsAsked() = runTest {
        val r = rig()
        r.library.recordWatch(item("fakevideo11"), 120_000, 0)
        open(r, "fakevideo11", startMs = 33_000)
        assertEquals(0L, r.engine.lastRequest?.startMs, "resume and an explicit start never apply to live")
        assertTrue(r.engine.lastRequest!!.live)
    }

    @Test
    fun aLiveStreamAsksNoSegmentsAndRecordsNoHistory() = runTest {
        val r = rig(VideoSettings(sponsorBlock = true))
        open(r, "fakevideo11")
        window(r, hour)
        r.engine.advance(30_000)
        advanceTimeBy(30_000)
        runCurrent()
        r.controller.seekTo(hour / 2)
        r.controller.togglePlayPause()
        runCurrent()
        r.controller.stop()
        runCurrent()
        assertTrue(r.segments.asked.isEmpty(), "no SponsorBlock lookup for live")
        assertTrue(r.library.history.value.isEmpty(), "no history for live")
        // A video asks SponsorBlock as before.
        val vod = rig(VideoSettings(sponsorBlock = true))
        open(vod, "fakevideo01")
        assertEquals(listOf("fakevideo01"), vod.segments.asked)
    }

    @Test
    fun seeksStayInTheWindowAndStopAtTheLiveEdge() = runTest {
        val r = rig()
        open(r, "fakevideo11")
        window(r, hour)
        r.controller.seekTo(Long.MAX_VALUE)
        r.controller.seekTo(hour)
        r.controller.seekTo(-1)
        r.controller.seekTo(1_800_000)
        assertEquals(listOf(hour - 10_000, hour - 10_000, 0L, 1_800_000L), r.seeks)
        r.engine.clearCalls()
        r.controller.seekTo(LiveWindow.liveEdgeMs(hour))
        r.controller.seekBy(-30_000)
        assertEquals(listOf(hour - 10_000, hour - 40_000), r.seeks)
        r.controller.seekBy(60_000)
        assertEquals(hour - 10_000, r.seeks.last(), "forward stops at the edge")
    }

    @Test
    fun withNoWindowThereIsNothingToSeekIn() = runTest {
        val r = rig()
        open(r, "fakevideo11")
        window(r, 0)
        r.controller.seekTo(5_000)
        r.controller.seekBy(-10_000)
        window(r, 6_000)                          // a window shorter than the live offset
        r.controller.seekTo(1_000)
        assertTrue(r.seeks.isEmpty())
    }

    @Test
    fun previousNeverJumpsToTheStartOfTheWindow() = runTest {
        val r = rig()
        open(r, "fakevideo11")
        window(r, hour)
        r.engine.setPosition(hour - 10_000)
        r.controller.playPrevious()
        assertTrue(r.seeks.isEmpty(), "nothing played before: nothing happens")
        // With a video before it, Previous plays that video at once, however far into the stream.
        val q = rig()
        open(q, "fakevideo01")
        open(q, "fakevideo11")
        window(q, hour)
        q.engine.setPosition(hour - 10_000)
        q.controller.playPrevious()
        runCurrent()
        assertEquals("fakevideo01", q.engine.lastRequest?.videoId)
    }

    @Test
    fun noAudioTrackOrQualityChangeForLive() = runTest {
        val r = rig()
        r.source.withVideoOnly = true
        open(r, "fakevideo11")
        r.engine.clearCalls()
        r.controller.selectAudioTrack("hi.4")
        r.controller.selectQuality(QualityOption(720, 30, com.cosmotune.video.model.VideoCodec.H264, false, "720p"))
        runCurrent()
        assertTrue(r.loads.isEmpty())
    }

    // ---------------------------------------------------------------- the end of a live stream

    @Test
    fun anEndedLiveStreamIsLoadedAgainOnceThenItEnds() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo11")
        assertEquals(1, r.detailsAsked("fakevideo11"))
        end(r)
        assertEquals(2, r.detailsAsked("fakevideo11"), "fresh details decide whether it is still live")
        assertEquals(2, r.loads.size, "a dropped stream: loaded again")
        assertTrue(r.loads.last().live)
        assertEquals(PlayerStatus.READY, r.state.status)
        assertTrue(r.state.playing)
        assertEquals(0, r.engine.unloadCount)
        // It ends again within the minute: that is the end.
        advanceTimeBy(20_000)
        end(r)
        assertEquals(2, r.loads.size)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertEquals(1, r.engine.unloadCount)
        assertNull(r.state.error)
    }

    @Test
    fun aStreamThatDropsAgainLaterIsLoadedAgainAgain() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo11")
        end(r)
        r.engine.advance(LiveWindowMinute + 1_000)
        advanceTimeBy(LiveWindowMinute + 1_000)
        runCurrent()
        end(r)
        assertEquals(3, r.loads.size, "a minute later, a drop is a drop again")
        assertEquals(PlayerStatus.READY, r.state.status)
    }

    @Test
    fun aBroadcastThatIsOverIsNotLoadedAgain() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo11")
        r.source.broadcastOver = true
        end(r)
        assertEquals(2, r.detailsAsked("fakevideo11"))
        assertEquals(1, r.loads.size, "the source says it is no longer live")
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertEquals(1, r.engine.unloadCount)
    }

    @Test
    fun aFailedCheckAtTheEndShowsItsError() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo11")
        r.source.fake.failNext(VideoSourceError.Network("offline", null))
        end(r)
        assertEquals(VideoUiError.NETWORK, r.state.error)
        assertEquals(1, r.loads.size)
    }

    @Test
    fun loopDoesNotLoopALiveStream() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo11")
        r.controller.setLoop(true)
        end(r)
        assertFalse(0L in r.seeks, "no seek to the start of the window")
        assertEquals(2, r.loads.size, "loaded again as a dropped stream instead")
    }

    @Test
    fun theEndOfAVideoIsUnchanged() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r, "fakevideo01")
        end(r)
        assertEquals(1, r.detailsAsked("fakevideo01"))
        assertEquals(1, r.loads.size)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertEquals(1, r.engine.unloadCount)
    }

    // ---------------------------------------------------------------- an upcoming video

    @Test
    fun anUpcomingVideoNeverReachesTheEngine() = runTest {
        val r = rig()
        open(r, "fakevideo12")
        assertEquals(1, r.detailsAsked("fakevideo12"))
        assertTrue(r.loads.isEmpty(), "no player for a premiere")
        val s = r.state
        assertEquals(PlayerStatus.ERROR, s.status)
        assertEquals(VideoUiError.UNAVAILABLE, s.error)
        assertTrue(s.item!!.isUpcoming)
        assertEquals(FakeVideoSource.NOW_MS + 2 * 86_400_000L, LiveWindow.upcomingStartMs(s), "the start time")
        assertTrue(LiveWindow.isUpcoming(s))
        assertNotNull(s.details, "the details the source gave stay for the page")
        assertFalse(s.playing)
        assertFalse(s.live)
        assertTrue(r.segments.asked.isEmpty())
        assertTrue(r.library.history.value.isEmpty())
    }

    @Test
    fun aPremiereKnownToBeAheadIsNotEvenAskedFor() = runTest {
        val r = rig()
        r.controller.addToQueue(item("fakevideo12"))
        open(r, "fakevideo12")
        assertEquals(0, r.detailsAsked("fakevideo12"))
        assertTrue(LiveWindow.isUpcoming(r.state))
        assertTrue(r.loads.isEmpty())
        // Saved to watch later works the same.
        val w = rig()
        w.library.addToWatchLater(item("fakevideo12"))
        open(w, "fakevideo12")
        assertEquals(0, w.detailsAsked("fakevideo12"))
        assertTrue(LiveWindow.isUpcoming(w.state))
    }

    @Test
    fun aPremiereWhoseTimeHasComeIsAskedFor() = runTest {
        val r = rig()
        r.library.addToWatchLater(item("fakevideo12").copy(uploadedAtMs = FakeVideoSource.NOW_MS - 60_000))
        open(r, "fakevideo12")
        assertEquals(1, r.detailsAsked("fakevideo12"), "it may have started")
    }

    @Test
    fun theSourceRefusingAKnownPremiereShowsThePremiere() = runTest {
        val r = rig()
        val late = item("fakevideo12").copy(uploadedAtMs = FakeVideoSource.NOW_MS - 60_000)
        r.library.addToWatchLater(late)
        // What the source answers for a stream that has not started: not available.
        r.source.fake.failNext(VideoSourceError.NotAvailable("not started"))
        open(r, "fakevideo12")
        assertTrue(LiveWindow.isUpcoming(r.state))
        assertEquals(late.uploadedAtMs, LiveWindow.upcomingStartMs(r.state))
        assertTrue(r.loads.isEmpty())
    }

    @Test
    fun otherFailuresOfAKnownPremiereAreThemselves() = runTest {
        val r = rig()
        r.library.addToWatchLater(item("fakevideo12").copy(uploadedAtMs = FakeVideoSource.NOW_MS - 60_000))
        r.source.fake.failNext(VideoSourceError.Network("offline", null))
        open(r, "fakevideo12")
        assertEquals(VideoUiError.NETWORK, r.state.error)
        assertFalse(LiveWindow.isUpcoming(r.state))
        // And an unknown video the source refuses is simply not available.
        val u = rig()
        u.source.fake.failNext(VideoSourceError.NotAvailable("gone"))
        open(u, "fakevideo03")
        assertEquals(VideoUiError.UNAVAILABLE, u.state.error)
        assertFalse(LiveWindow.isUpcoming(u.state))
    }

    @Test
    fun tryAgainOnAPremiereStaysAPremiere() = runTest {
        val r = rig()
        open(r, "fakevideo12")
        r.controller.togglePlayPause()            // "Try again"
        runCurrent()
        r.controller.play("fakevideo12")          // the error's button
        runCurrent()
        assertTrue(LiveWindow.isUpcoming(r.state))
        assertTrue(r.loads.isEmpty())
        assertEquals(1, r.detailsAsked("fakevideo12"), "the premiere is known to be two days away: not asked again")
    }

    @Test
    fun aPremieresDetailsAreNotCached() = runTest {
        val r = rig()
        open(r, "fakevideo12")
        open(r, "fakevideo01")
        open(r, "fakevideo12")
        assertEquals(2, r.detailsAsked("fakevideo12"), "a premiere may start any minute")
        assertEquals(listOf("fakevideo01"), r.loads.map { it.videoId }, "only the video reached the engine")
    }

    private companion object {
        /** The controller's window for a second drop: a minute. */
        val LiveWindowMinute = VideoPlayerController.LIVE_RELOAD_WINDOW_MS
    }
}
