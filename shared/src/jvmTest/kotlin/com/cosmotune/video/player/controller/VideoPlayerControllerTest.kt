package com.cosmotune.video.player.controller

import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.VideoLibrary
import com.cosmotune.video.model.Chapter
import com.cosmotune.video.model.StreamSet
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.player.EngineCapabilities
import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.FakeVideoPlayerEngine.Call
import com.cosmotune.video.player.LocalBundle
import com.cosmotune.video.player.LocalSubtitle
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.NoSegmentDecider
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.player.SegmentDecider
import com.cosmotune.video.player.SegmentDecision
import com.cosmotune.video.player.Selection
import com.cosmotune.video.player.StreamChooser
import com.cosmotune.video.player.VideoPlayerEngine
import com.cosmotune.video.player.VideoUiError
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SegmentProvider
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.settings.SegmentBehaviour
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.source.VideoSourceError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The player controller (plan task Y-10; spec 6.1), with the fakes of Y-2 and virtual time. The controller's own scope is the
 * test's background scope; `runCurrent()` lets it work, [playFor] moves the engine's clock and the test clock together.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoPlayerControllerTest {

    // ---------------------------------------------------------------- the rig

    /** A chooser that records its arguments and honours `manual` and `audioTrackId` over the fake source's ladder. */
    private class SpyChooser : StreamChooser {
        data class Args(
            val streams: StreamSet, val caps: EngineCapabilities, val settings: VideoSettings, val metered: Boolean,
            val manual: QualityOption?, val audioTrackId: String?, val bandwidthKbps: Int?, val durationMs: Long,
        )

        val calls = mutableListOf<Args>()
        val selections = mutableListOf<Selection>()

        override fun choose(
            streams: StreamSet, caps: EngineCapabilities, settings: VideoSettings, metered: Boolean, manual: QualityOption?,
            audioTrackId: String?, bandwidthKbps: Int?, durationMs: Long,
        ): Selection {
            calls += Args(streams, caps, settings, metered, manual, audioTrackId, bandwidthKbps, durationMs)
            val ladder = streams.videoOnly.groupBy { it.height }
                .map { (_, same) -> same.maxBy { it.fps } }
                .sortedByDescending { it.height }
                .map { QualityOption(it.height, it.fps, it.codec, it.hdr, QualityOption.labelOf(it.height, it.fps, it.hdr)) }
            val option = manual?.let { m -> ladder.firstOrNull { it.height == m.height } } ?: ladder.firstOrNull()
            val video = option?.let { o -> streams.videoOnly.first { it.height == o.height && it.fps == o.fps && it.codec == o.codec } }
            val audio = if (video == null) null else (streams.audio.firstOrNull { it.trackId == audioTrackId } ?: streams.audio.firstOrNull())
            val hls = streams.hlsUrl
            val input = if (video == null && hls != null) MediaInput.Hls(hls) else MediaInput.Progressive(video, audio)
            return Selection(option, input, video, audio, ladder).also { selections += it }
        }
    }

    /** The library of the fakes, remembering every `recordWatch`. */
    private class SpyLibrary(val fake: FakeVideoLibrary = FakeVideoLibrary()) : VideoLibrary by fake {
        data class Record(val videoId: String, val isShort: Boolean, val positionMs: Long, val durationMs: Long)

        val records = mutableListOf<Record>()

        override fun recordWatch(item: VideoItem, positionMs: Long, durationMs: Long) {
            records += Record(item.id, item.isShort, positionMs, durationMs)
            fake.recordWatch(item, positionMs, durationMs)
        }
    }

    /** SponsorBlock that remembers what it was asked and can fail like a missing network. */
    private class SpySegments(var answer: List<SkipSegment> = emptyList(), var failing: Boolean = false) : SegmentProvider {
        val asked = mutableListOf<Pair<String, Set<SegmentCategory>>>()

        override suspend fun segments(videoId: String, categories: Set<SegmentCategory>): List<SkipSegment> {
            asked += videoId to categories
            if (failing) throw VideoSourceError.Network("offline", null)
            return answer.filter { it.category in categories }
        }
    }

    /**
     * A policy standing in for Y-6's: SKIP skips, MUTE mutes, ASK asks; outside every segment it answers `Unmute` or `None`
     * ([explicitUnmute]), so the controller is held to its own rule of ending a mute when the position leaves the segment.
     */
    private class TestDecider(private val explicitUnmute: Boolean = true) : SegmentDecider {
        override fun decide(positionMs: Long, segments: List<SkipSegment>, settings: VideoSettings, handled: Set<String>, seekedInto: Set<String>): SegmentDecision {
            val inside = segments.firstOrNull { positionMs >= it.startMs && positionMs < it.endMs && it.uuid !in handled && it.uuid !in seekedInto }
                ?: return if (explicitUnmute) SegmentDecision.Unmute else SegmentDecision.None
            return when (settings.segmentBehaviours[inside.category]) {
                SegmentBehaviour.SKIP -> SegmentDecision.SkipTo(inside.endMs, inside)
                SegmentBehaviour.MUTE -> SegmentDecision.Mute(inside.endMs, inside)
                SegmentBehaviour.ASK -> SegmentDecision.Ask(inside)
                else -> SegmentDecision.None
            }
        }
    }

    private class Rig(
        test: TestScope,
        settings: VideoSettings = VideoSettings(),
        val segments: SpySegments = SpySegments(),
        decider: SegmentDecider = NoSegmentDecider(),
        var metered: Boolean = false,
        sourceClock: () -> Long = { FakeVideoSource.NOW_MS },
        engine: FakeVideoPlayerEngine = FakeVideoPlayerEngine(),
        wrap: (FakeVideoPlayerEngine) -> VideoPlayerEngine = { it },
    ) {
        val source = FakeVideoSource(sourceClock)
        val engine = engine
        val library = SpyLibrary()
        val settings = FakeVideoSettingsRepository(settings)
        val chooser = SpyChooser()
        val controller = VideoPlayerController(
            source, wrap(engine), library, this.settings, segments, chooser, decider, { metered }, test.backgroundScope,
        ) { FakeVideoSource.NOW_MS + test.testScheduler.currentTime }

        val state: PlaybackUiState get() = controller.state.value
        val loads: List<PlaybackRequest> get() = engine.calls.filterIsInstance<Call.Load>().map { it.request }
    }

    private fun TestScope.rig(
        settings: VideoSettings = VideoSettings(),
        segments: SpySegments = SpySegments(),
        decider: SegmentDecider = NoSegmentDecider(),
        metered: Boolean = false,
        sourceClock: () -> Long = { FakeVideoSource.NOW_MS },
    ) = Rig(this, settings, segments, decider, metered, sourceClock)

    /** Plays [id] and lets the controller finish loading. */
    private fun TestScope.open(r: Rig, id: String = "fakevideo01", startMs: Long? = null, queue: List<VideoItem> = emptyList(), short: Boolean = false) {
        r.controller.play(id, startMs, queue, short)
        runCurrent()
    }

    /** Lets [ms] of playing go by in steps of 250 ms: the engine's clock and the test's clock move together. */
    private fun TestScope.playFor(r: Rig, ms: Long) {
        var left = ms
        while (left > 0) {
            val step = minOf(250L, left)
            r.engine.advance(step)
            advanceTimeBy(step)
            runCurrent()
            left -= step
        }
    }

    private fun item(id: String): VideoItem = FakeVideoSource.sampleItem(id) ?: error("no sample item $id")

    private fun segment(uuid: String, category: SegmentCategory, startMs: Long, endMs: Long) =
        SkipSegment(uuid, category, SegmentAction.SKIP, startMs, endMs, null, 5, false, null)

    private val sponsor = segment("sponsor-1", SegmentCategory.SPONSOR, 30_000, 60_000)
    private val promo = segment("promo-1", SegmentCategory.SELF_PROMO, 120_000, 140_000)
    private val muteMe = segment("mute-1", SegmentCategory.INTERACTION, 200_000, 210_000)

    private val behaviours = SegmentCategory.defaults() + (SegmentCategory.INTERACTION to SegmentBehaviour.MUTE)

    // ---------------------------------------------------------------- 1. play

    @Test
    fun playFetchesTheDetailsAndLoadsTheEngineWithTheChoice() = runTest {
        val r = rig()
        open(r)
        val expected = FakeVideoSource().details("fakevideo01")

        assertEquals(listOf("details(fakevideo01)"), r.source.calls)

        val args = r.chooser.calls.single()
        assertEquals(r.engine.capabilities, args.caps)
        assertEquals(VideoSettings(), args.settings)
        assertFalse(args.metered)
        assertNull(args.manual, "the first choice is Auto")
        assertEquals(expected.streams, args.streams)

        val request = assertNotNull(r.engine.lastRequest)
        assertEquals("fakevideo01", request.videoId)
        assertEquals(r.chooser.selections.single().input, request.input)
        assertEquals(expected.subtitles, request.subtitles)
        assertEquals(expected.streams.requestHeaders, request.headers)
        assertEquals(0L, request.startMs)
        assertFalse(request.live)
        assertEquals(expected.item.title, request.title)
        assertEquals(expected.item.channelName, request.channel)
        assertEquals(expected.item.thumbnailUrl, request.thumbnailUrl)

        val calls = r.engine.calls
        assertEquals(listOf(Call.Load(request), Call.SetSpeed(1f), Call.Play), calls)

        val s = r.state
        assertEquals("fakevideo01", s.item?.id)
        assertEquals(expected, s.details)
        assertEquals(r.chooser.selections.single().ladder, s.ladder)
        assertEquals(s.ladder.first(), s.quality)
        assertTrue(s.autoQuality)
        assertEquals(PlayerStatus.READY, s.status)
        assertTrue(s.playing)
        assertNull(s.error)
        assertEquals(expected.subtitles, s.subtitles)
        assertEquals(600_000L, s.durationMs)
        assertFalse(s.live)
        assertFalse(s.short)
        assertEquals(16f / 9f, s.aspect)
    }

    @Test
    fun theChooserIsToldAboutMeteredData() = runTest {
        val r = rig(metered = true)
        open(r)
        assertTrue(r.chooser.calls.single().metered)
    }

    @Test
    fun theDefaultSpeedIsApplied() = runTest {
        val r = rig(VideoSettings(defaultSpeed = 1.5f))
        open(r)
        assertTrue(Call.SetSpeed(1.5f) in r.engine.calls)
        assertEquals(1.5f, r.state.speed)
        assertEquals(1.5f, r.engine.snapshot.value.speed)
    }

    @Test
    fun theAudioTracksOfAVideoAreListedOncePerTrack() = runTest {
        val r = rig()
        open(r)
        // The sample has AAC and Opus of the original track, and one dubbed track.
        assertEquals(listOf(null, "hi.4"), r.state.audioTracks.map { it.trackId })
        assertNull(r.state.audioTrackId)
    }

    @Test
    fun theCaptionLanguageOfTheSettingsPicksAManualTrackFirst() = runTest {
        val r = rig(VideoSettings(captionsLanguage = "en"))
        open(r)
        assertTrue(Call.SelectSubtitle("en") in r.engine.calls)
        assertEquals("en", r.state.subtitleLanguage)
        // No caption language: none selected.
        val off = rig()
        open(off)
        assertTrue(off.engine.calls.none { it is Call.SelectSubtitle })
        assertNull(off.state.subtitleLanguage)
    }

    @Test
    fun theHistoryPositionIsTheStartWhenResumeIsOnAndTheEntryIsNotWatched() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo01"), 120_000, 754_000)
        open(r)
        assertEquals(120_000L, r.engine.lastRequest?.startMs)
        assertEquals(120_000L, r.controller.positionMs())
    }

    @Test
    fun aWatchedVideoStartsAtZero() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo01"), 700_000, 754_000)      // 92 %
        open(r)
        assertEquals(0L, r.engine.lastRequest?.startMs)
    }

    @Test
    fun withResumeOffTheVideoStartsAtZero() = runTest {
        val r = rig(VideoSettings(resume = false))
        r.library.fake.recordWatch(item("fakevideo01"), 120_000, 754_000)
        open(r)
        assertEquals(0L, r.engine.lastRequest?.startMs)
    }

    @Test
    fun anExplicitStartWinsOverTheHistory() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo01"), 120_000, 754_000)
        open(r, startMs = 33_000)
        assertEquals(33_000L, r.engine.lastRequest?.startMs)
        // Even when the video was watched to the end.
        val watched = rig()
        watched.library.fake.recordWatch(item("fakevideo01"), 750_000, 754_000)
        open(watched, startMs = 5_000)
        assertEquals(5_000L, watched.engine.lastRequest?.startMs)
    }

    @Test
    fun aLiveStreamIsLoadedAsLiveAndNeverResumed() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo11"), 120_000, 0)
        open(r, "fakevideo11")
        val request = assertNotNull(r.engine.lastRequest)
        assertTrue(request.live)
        assertEquals(0L, request.startMs)
        assertIs<MediaInput.Hls>(request.input)
        assertTrue(r.state.live)
        assertTrue(r.state.ladder.isEmpty())
    }

    @Test
    fun playingTheVideoThatIsAlreadyPlayingDoesNotRestartIt() = runTest {
        val r = rig()
        open(r)
        playFor(r, 3_000)
        r.engine.clearCalls()
        open(r)                                 // a card of the playing video was tapped
        assertTrue(r.engine.calls.isEmpty())
        assertEquals(listOf("details(fakevideo01)"), r.source.calls)
        // ...but a start position seeks.
        open(r, startMs = 50_000)
        assertEquals(listOf<Call>(Call.SeekTo(50_000)), r.engine.calls)
    }

    @Test
    fun playingTheEndedOrFailedVideoAgainStartsItAgain() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 5_000
        open(r)
        playFor(r, 5_000)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        open(r)
        assertEquals(2, r.loads.size)
    }

    // ---------------------------------------------------------------- 2. refresh on a 403

    @Test
    fun anExpiredAddressIsRefreshedAtTheEnginesLastPositionWithTheSameQuality() = runTest {
        val r = rig()
        open(r)
        val ladder = r.state.ladder
        r.controller.selectQuality(ladder[3])
        runCurrent()
        playFor(r, 40_000)
        val position = r.engine.positionMs()
        assertTrue(position >= 40_000)
        val before = r.loads.size

        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED, "HTTP 403")
        runCurrent()

        assertEquals(listOf("details(fakevideo01)", "streams(fakevideo01)"), r.source.calls)
        assertEquals(before + 1, r.loads.size)
        val request = r.loads.last()
        assertEquals(position, request.startMs)
        assertEquals(ladder[3], r.state.quality)
        assertEquals(720, (request.input as MediaInput.Progressive).video?.height)
        assertEquals(ladder[3], r.chooser.calls.last().manual)
        assertNull(r.state.error)
        assertTrue(r.state.playing, "the video plays on after the refresh")
        assertFalse(r.state.autoQuality)
    }

    @Test
    fun inAutoTheRefreshKeepsTheRungItHadAndStaysAuto() = runTest {
        val r = rig()
        open(r)
        val first = r.state.quality
        playFor(r, 10_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertEquals(first, r.chooser.calls.last().manual, "the rung it had is asked for, so the picture does not jump")
        assertEquals(first, r.state.quality)
        assertTrue(r.state.autoQuality)
    }

    @Test
    fun aThirdExpiryWithinFiveMinutesLeavesStopped() = runTest {
        val r = rig()
        open(r)
        playFor(r, 5_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertNull(r.state.error)
        playFor(r, 60_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertNull(r.state.error)
        playFor(r, 60_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()

        assertEquals(VideoUiError.STOPPED, r.state.error)
        assertEquals(PlayerStatus.ERROR, r.state.status)
        assertFalse(r.state.playing)
        assertEquals(2, r.source.calls.count { it.startsWith("streams(") }, "only two refreshes were made")
        assertEquals(3, r.loads.size)
    }

    @Test
    fun theBudgetFreesAfterFiveMinutes() = runTest {
        val r = rig()
        open(r)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        advanceTimeBy(60_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        advanceTimeBy(5 * 60_000L)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertNull(r.state.error)
        assertEquals(3, r.source.calls.count { it.startsWith("streams(") })
    }

    @Test
    fun aRefreshThatTheSourceRefusesShowsTheSourcesError() = runTest {
        val r = rig()
        open(r)
        r.source.failNext(VideoSourceError.Network("offline", null))
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertEquals(VideoUiError.NETWORK, r.state.error)
        assertEquals(PlayerStatus.ERROR, r.state.status)
        assertEquals(1, r.loads.size)
    }

    @Test
    fun playPressedOnAStoppedVideoTriesAgainAtTheSamePosition() = runTest {
        val r = rig()
        open(r)
        playFor(r, 20_000)
        repeat(3) {
            r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
            runCurrent()
            advanceTimeBy(1_000)
        }
        assertEquals(VideoUiError.STOPPED, r.state.error)
        val loadsBefore = r.loads.size
        r.controller.togglePlayPause()              // "Try again"
        runCurrent()
        assertEquals(loadsBefore + 1, r.loads.size)
        assertEquals(20_000L, r.loads.last().startMs)
        assertNull(r.state.error)
        assertTrue(r.state.playing)
        assertEquals(2, r.source.calls.count { it == "details(fakevideo01)" }, "a retry starts from fresh details, not the cache")
    }

    @Test
    fun streamsThatAreAboutToExpireAreFetchedFreshBeforeTheFirstLoad() = runTest {
        // The source's clock is 6 h behind the controller's less a minute: its streams expire in one minute, under the 2 minute margin.
        val r = rig(sourceClock = { FakeVideoSource.NOW_MS - 6 * 3_600_000L + 30_000L })
        open(r)
        assertEquals(listOf("details(fakevideo01)", "streams(fakevideo01)"), r.source.calls)
        assertEquals(1, r.loads.size)
    }

    // ---------------------------------------------------------------- 3. history

    @Test
    fun historyIsRecordedEveryTenSecondsOfPlayingAndOnPause() = runTest {
        val r = rig()
        open(r)
        playFor(r, 25_000)
        assertEquals(listOf(10_000L, 20_000L), r.library.records.map { it.positionMs })
        assertTrue(r.library.records.all { it.videoId == "fakevideo01" && it.durationMs == 600_000L })

        r.controller.togglePlayPause()           // pause
        runCurrent()
        assertEquals(listOf(10_000L, 20_000L, 25_000L), r.library.records.map { it.positionMs })

        // Paused: nothing more, however long it stays so.
        advanceTimeBy(60_000)
        assertEquals(3, r.library.records.size)
        // Resumed: the ten seconds count from the resume.
        r.controller.togglePlayPause()
        runCurrent()
        playFor(r, 9_750)
        assertEquals(3, r.library.records.size)
        playFor(r, 250)
        assertEquals(35_000L, r.library.records.last().positionMs)
    }

    @Test
    fun historyIsRecordedOnStop() = runTest {
        val r = rig()
        open(r)
        playFor(r, 4_000)
        r.controller.stop()
        runCurrent()
        assertEquals(listOf(4_000L), r.library.records.map { it.positionMs })
        assertEquals(4_000L, r.library.fake.positionOf("fakevideo01"))
    }

    @Test
    fun historyIsRecordedWhenTheUiLeaves() = runTest {
        val r = rig()
        open(r)
        playFor(r, 4_000)
        r.controller.onUiVisible(false)
        runCurrent()
        assertEquals(listOf(4_000L), r.library.records.map { it.positionMs })
    }

    @Test
    fun theVideoThatWasPlayingIsRecordedWhenAnotherOneStarts() = runTest {
        val r = rig()
        open(r)
        playFor(r, 3_000)
        open(r, "fakevideo02")
        assertEquals(listOf(SpyLibrary.Record("fakevideo01", false, 3_000, 600_000)), r.library.records)
    }

    @Test
    fun nothingIsRecordedWhileHistoryIsPaused() = runTest {
        val r = rig()
        r.library.setHistoryPaused(true)
        open(r)
        playFor(r, 25_000)
        r.controller.togglePlayPause()
        runCurrent()
        r.controller.onUiVisible(false)
        r.controller.stop()
        runCurrent()
        assertTrue(r.library.records.isEmpty())
        assertTrue(r.library.history.value.isEmpty())
    }

    @Test
    fun nothingIsRecordedForALiveStream() = runTest {
        val r = rig()
        open(r, "fakevideo11")
        playFor(r, 25_000)
        r.controller.togglePlayPause()
        runCurrent()
        r.controller.stop()
        runCurrent()
        assertTrue(r.library.records.isEmpty())
    }

    @Test
    fun aVideoThatEndedIsRecordedAsWatched() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 30_000
        open(r)
        playFor(r, 30_000)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        val last = r.library.records.last()
        assertEquals(30_000L, last.positionMs)
        assertTrue(r.library.history.value.single().isWatched)
    }

    @Test
    fun aPositionBeforeTheEngineIsReadyIsNotRecorded() = runTest {
        // Opening and leaving at once must not overwrite the resume position with the engine's 0 before it has seeked.
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo01"), 120_000, 754_000)
        r.library.records.clear()
        open(r)
        r.engine.setSnapshot { it.copy(status = PlayerStatus.LOADING, playing = false) }
        r.engine.setPosition(0)
        runCurrent()
        r.controller.onUiVisible(false)
        runCurrent()
        assertTrue(r.library.records.isEmpty())
        assertEquals(120_000L, r.library.fake.positionOf("fakevideo01"))
    }

    // ---------------------------------------------------------------- 4. segments

    @Test
    fun afterTheLoadTheSegmentsOfTheEnabledCategoriesAreAsked() = runTest {
        val r = rig(segments = SpySegments(listOf(sponsor)))
        open(r)
        val (id, categories) = r.segments.asked.single()
        assertEquals("fakevideo01", id)
        assertEquals(SegmentCategory.entries.toSet() - SegmentCategory.FILLER, categories, "filler is off by default")
        assertEquals(listOf(sponsor), r.state.segments)
    }

    @Test
    fun segmentsAreNotAskedWhenSponsorBlockIsOff() = runTest {
        val r = rig(VideoSettings(sponsorBlock = false), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 31_000)
        assertTrue(r.segments.asked.isEmpty())
        assertTrue(r.state.segments.isEmpty())
        assertTrue(r.engine.calls.none { it is Call.SeekTo })
    }

    @Test
    fun segmentsThatTheProviderCannotGetAreNone() = runTest {
        val r = rig(segments = SpySegments(listOf(sponsor), failing = true))
        open(r)
        assertTrue(r.state.segments.isEmpty())
        assertTrue(r.state.playing, "playback does not wait for, or depend on, SponsorBlock")
    }

    @Test
    fun aSkipSegmentIsSkippedOnceAndTheToastGoesAfterFourSeconds() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 29_750)
        assertNull(r.state.lastSkip)
        assertTrue(r.engine.calls.none { it is Call.SeekTo })

        playFor(r, 250)                                                 // the tick at 30 s
        assertTrue(Call.SeekTo(60_000) in r.engine.calls)
        assertEquals(sponsor, r.state.lastSkip)
        assertEquals(60_000L, r.controller.positionMs())

        playFor(r, 3_500)
        assertEquals(sponsor, r.state.lastSkip, "the toast shows for 4 s")
        playFor(r, 600)
        assertNull(r.state.lastSkip)

        // Once per load: going back into it does not skip again.
        r.engine.setPosition(40_000)
        playFor(r, 1_000)
        assertEquals(1, r.engine.calls.count { it == Call.SeekTo(60_000) })
        assertEquals(41_000L, r.controller.positionMs())
    }

    @Test
    fun undoGoesBackToTheStartOfTheSegmentAndItIsNotSkippedAgain() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 30_000)
        assertEquals(sponsor, r.state.lastSkip)

        r.controller.undoSkip()
        runCurrent()
        assertEquals(Call.SeekTo(30_000), r.engine.calls.last { it is Call.SeekTo })
        assertNull(r.state.lastSkip)
        assertEquals(30_000L, r.controller.positionMs())

        playFor(r, 2_000)
        assertEquals(1, r.engine.calls.count { it == Call.SeekTo(60_000) }, "marked handled by the undo")
        assertEquals(32_000L, r.controller.positionMs())
    }

    @Test
    fun aSegmentTheUserSeekedIntoIsNotSkipped() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 5_000)
        r.controller.seekTo(35_000)                    // the user chose to be there
        runCurrent()
        playFor(r, 2_000)
        assertTrue(Call.SeekTo(60_000) !in r.engine.calls)
        assertNull(r.state.lastSkip)
        assertEquals(37_000L, r.controller.positionMs())
    }

    @Test
    fun aSeekByThatLandsInsideASegmentIsAChoiceToo() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 25_000)
        r.controller.seekBy(10_000)                    // 35 s
        playFor(r, 1_000)
        assertTrue(Call.SeekTo(60_000) !in r.engine.calls)
    }

    @Test
    fun aMuteSegmentMutesAndUnmutesAtItsEnd() = runTest {
        for (explicitUnmute in listOf(true, false)) {
            val r = rig(VideoSettings(segmentBehaviours = behaviours), SpySegments(listOf(muteMe)), TestDecider(explicitUnmute))
            open(r)
            r.engine.setPosition(199_750)
            playFor(r, 250)
            assertTrue(Call.SetMuted(true) in r.engine.calls, "explicitUnmute=$explicitUnmute")
            assertEquals(muteMe, r.state.mutedSegment)
            assertFalse(r.state.muted, "the Mute segment is not the user's mute")

            playFor(r, 5_000)
            assertEquals(1, r.engine.calls.count { it == Call.SetMuted(true) }, "muted once, not at every tick")
            assertEquals(muteMe, r.state.mutedSegment)

            playFor(r, 5_000)                                           // past 210 s
            assertNull(r.state.mutedSegment, "explicitUnmute=$explicitUnmute")
            assertEquals(Call.SetMuted(false), r.engine.calls.last { it is Call.SetMuted })
            assertFalse(r.engine.snapshot.value.muted)
        }
    }

    @Test
    fun leavingAMuteSegmentBySeekingUnmutesAtOnceEvenWhilePaused() = runTest {
        val r = rig(VideoSettings(segmentBehaviours = behaviours), SpySegments(listOf(muteMe)), TestDecider())
        open(r)
        r.engine.setPosition(199_750)
        playFor(r, 500)
        assertNotNull(r.state.mutedSegment)
        r.controller.togglePlayPause()
        runCurrent()
        r.controller.seekTo(100_000)
        runCurrent()
        assertNull(r.state.mutedSegment)
        assertFalse(r.engine.snapshot.value.muted)
    }

    @Test
    fun theUsersMuteSurvivesASegmentsMute() = runTest {
        val r = rig(VideoSettings(segmentBehaviours = behaviours), SpySegments(listOf(muteMe)), TestDecider())
        open(r)
        r.controller.setMuted(true)
        r.engine.setPosition(199_750)
        playFor(r, 11_000)
        assertNull(r.state.mutedSegment)
        assertTrue(r.engine.snapshot.value.muted, "the user's mute is still on after the segment")
        assertTrue(r.state.muted)
    }

    @Test
    fun anAskSegmentShowsThePillUntilItEndsAndSkipPendingSeeksToItsEnd() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(promo)), TestDecider())
        open(r)
        r.engine.setPosition(119_750)
        playFor(r, 250)
        assertEquals(promo, r.state.pendingSkip)
        assertTrue(r.engine.calls.none { it is Call.SeekTo }, "asking does not skip")

        r.controller.skipPending()
        runCurrent()
        assertEquals(Call.SeekTo(140_000), r.engine.calls.last { it is Call.SeekTo })
        assertNull(r.state.pendingSkip)
        assertEquals(promo, r.state.lastSkip, "a manual skip can be undone too")
        playFor(r, 1_000)
        assertNull(r.state.pendingSkip, "handled")
    }

    @Test
    fun thePillGoesWhenThePositionLeavesTheSegment() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(promo)), TestDecider())
        open(r)
        r.engine.setPosition(119_750)
        playFor(r, 250)
        assertNotNull(r.state.pendingSkip)
        r.engine.setPosition(160_000)
        playFor(r, 250)
        assertNull(r.state.pendingSkip)
    }

    @Test
    fun skipPendingWithNothingPendingDoesNothing() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(promo)), TestDecider())
        open(r)
        r.engine.clearCalls()
        r.controller.skipPending()
        r.controller.undoSkip()
        runCurrent()
        assertTrue(r.engine.calls.isEmpty())
    }

    // ---------------------------------------------------------------- 5. quality

    @Test
    fun selectingAQualityReloadsAtTheCurrentPositionAndKeepsPlaying() = runTest {
        val r = rig()
        open(r)
        val ladder = r.state.ladder
        playFor(r, 12_000)

        r.controller.selectQuality(ladder[4])           // 480p
        runCurrent()

        assertEquals(ladder[4], r.chooser.calls.last().manual)
        val request = r.loads.last()
        assertEquals(12_000L, request.startMs)
        assertEquals(480, (request.input as MediaInput.Progressive).video?.height)
        assertEquals(ladder[4], r.state.quality)
        assertFalse(r.state.autoQuality)
        assertTrue(r.state.playing)
        assertEquals(2, r.loads.size)
        assertEquals(listOf("details(fakevideo01)"), r.source.calls, "the streams in hand are used, not fetched again")
        assertEquals(1f, r.state.speed)
        assertEquals(2, r.engine.calls.count { it == Call.SetSpeed(1f) }, "the speed is applied again after the reload")
    }

    @Test
    fun aQualityChangeOnStreamsThatAreAboutToExpireFetchesFreshOnesFirst() = runTest {
        val r = rig(sourceClock = { FakeVideoSource.NOW_MS - 6 * 3_600_000L + 30_000L })       // the sets it gives expire in 30 s
        open(r)
        assertEquals(listOf("details(fakevideo01)", "streams(fakevideo01)"), r.source.calls)
        playFor(r, 8_000)

        r.controller.selectQuality(r.state.ladder[4])
        runCurrent()

        assertEquals("streams(fakevideo01)", r.source.calls.last())
        assertEquals(3, r.source.calls.size)
        assertEquals(2, r.loads.size)
        assertEquals(8_000L, r.loads.last().startMs)
        assertEquals(480, (r.loads.last().input as MediaInput.Progressive).video?.height)
        assertTrue(r.state.playing)
    }

    @Test
    fun aQualityChangeThatCannotFetchKeepsThePictureThatIsPlaying() = runTest {
        val r = rig(sourceClock = { FakeVideoSource.NOW_MS - 6 * 3_600_000L + 30_000L })
        open(r)
        playFor(r, 8_000)
        r.source.failNext(VideoSourceError.Network("offline", null))
        r.controller.selectQuality(r.state.ladder[4])
        runCurrent()
        assertEquals(1, r.loads.size)
        assertNull(r.state.error, "the old stream still plays; if it fails the refresh takes over")
        assertTrue(r.state.playing)
    }

    @Test
    fun selectingAQualityWhilePausedDoesNotStartPlaying() = runTest {
        val r = rig()
        open(r)
        playFor(r, 3_000)
        r.controller.togglePlayPause()
        runCurrent()
        r.engine.clearCalls()
        r.controller.selectQuality(r.state.ladder[2])
        runCurrent()
        assertTrue(r.engine.calls.none { it == Call.Play })
        assertFalse(r.state.playing)
    }

    @Test
    fun nullReturnsToAuto() = runTest {
        val r = rig()
        open(r)
        val ladder = r.state.ladder
        r.controller.selectQuality(ladder[4])
        runCurrent()
        assertFalse(r.state.autoQuality)
        r.controller.selectQuality(null)
        runCurrent()
        assertNull(r.chooser.calls.last().manual)
        assertTrue(r.state.autoQuality)
        assertEquals(ladder[0], r.state.quality)
    }

    @Test
    fun selectingAnAudioTrackReloadsWithThatTrack() = runTest {
        val r = rig()
        open(r)
        playFor(r, 7_000)
        r.controller.selectAudioTrack("hi.4")
        runCurrent()
        assertEquals("hi.4", r.chooser.calls.last().audioTrackId)
        assertEquals("hi.4", (r.loads.last().input as MediaInput.Progressive).audio?.trackId)
        assertEquals(7_000L, r.loads.last().startMs)
        assertEquals("hi.4", r.state.audioTrackId)
        r.controller.selectAudioTrack(null)
        runCurrent()
        assertNull(r.state.audioTrackId)
        assertEquals(3, r.loads.size)
    }

    @Test
    fun aReloadCarriesTheSpeedTheMuteAndTheSubtitlesOver() = runTest {
        val r = rig(VideoSettings(captionsLanguage = "en"))
        open(r)
        r.controller.setSpeed(2f)
        r.controller.setMuted(true)
        r.controller.selectQuality(r.state.ladder[2])
        runCurrent()
        val afterLoad = r.engine.calls.dropWhile { it !is Call.Load }.drop(1).dropWhile { it !is Call.Load }.drop(1)
        assertTrue(Call.SetSpeed(2f) in afterLoad)
        assertEquals("en", r.state.subtitleLanguage)
        assertTrue(Call.SelectSubtitle("en") in afterLoad)
        assertEquals(2f, r.state.speed)
        assertTrue(r.engine.snapshot.value.muted)
    }

    @Test
    fun inAutoTooManyDroppedFramesStepOneRungDown() = runTest {
        val r = rig()
        open(r)
        val ladder = r.state.ladder
        assertEquals(60, ladder[0].fps)
        playFor(r, 9_750)
        r.engine.setSnapshot { it.copy(droppedFrames = 100) }       // 100 of 600 frames: more than 10 %
        runCurrent()
        assertEquals(1, r.loads.size)
        playFor(r, 250)                                              // the window is over

        assertEquals(2, r.loads.size)
        assertEquals(ladder[1], r.state.quality)
        assertEquals(ladder[1], r.chooser.calls.last().manual)
        assertEquals(10_000L, r.loads.last().startMs)
        assertTrue(r.state.autoQuality, "it is still Auto, with a lower ceiling")
        assertTrue(r.state.playing)

        // The ceiling stays for this video: a refresh does not climb back.
        playFor(r, 2_000)
        r.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertEquals(ladder[1], r.state.quality)
    }

    @Test
    fun aFewDroppedFramesDoNotStepDown() = runTest {
        val r = rig()
        open(r)
        playFor(r, 9_750)
        r.engine.setSnapshot { it.copy(droppedFrames = 50) }        // 50 of 600: under 10 %
        runCurrent()
        playFor(r, 30_000)
        assertEquals(1, r.loads.size)
    }

    @Test
    fun aQualityTheUserChoseIsNeverSteppedDown() = runTest {
        val r = rig()
        open(r)
        r.controller.selectQuality(r.state.ladder[0])
        runCurrent()
        val loads = r.loads.size
        playFor(r, 9_750)
        r.engine.setSnapshot { it.copy(droppedFrames = 500) }
        runCurrent()
        playFor(r, 10_000)
        assertEquals(loads, r.loads.size)
    }

    @Test
    fun autoStepsDownOneRungAtATimeAndStopsAtTheLowest() = runTest {
        val r = rig()
        open(r)
        val ladder = r.state.ladder
        repeat(ladder.size + 3) {
            r.engine.setSnapshot { it.copy(droppedFrames = it.droppedFrames + 10_000) }
            runCurrent()
            playFor(r, 10_000)
        }
        assertEquals(ladder.last(), r.state.quality)
        assertTrue(r.state.autoQuality)
        assertEquals(ladder.size, r.loads.size, "the first load and one for each rung below the top, none under the lowest")
        assertEquals(ladder.map { it.height }, r.loads.map { (it.input as MediaInput.Progressive).video?.height })
    }

    // ---------------------------------------------------------------- 6. the end

    @Test
    fun loopRestartsAtTheEnd() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 20_000
        open(r)
        r.controller.setLoop(true)
        assertTrue(r.state.loop)
        playFor(r, 20_000)
        runCurrent()
        assertEquals(PlayerStatus.READY, r.state.status)
        assertTrue(r.state.playing)
        assertEquals(Call.SeekTo(0), r.engine.calls.last { it is Call.SeekTo })
        assertEquals(1, r.loads.size)
        assertEquals(listOf("details(fakevideo01)"), r.source.calls)
        playFor(r, 1_000)
        assertEquals(1_000L, r.controller.positionMs())
    }

    @Test
    fun theQueuesNextPlaysAtTheEnd() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 20_000
        val three = FakeVideoSource.videos.take(3)
        open(r, "fakevideo01", queue = three)
        assertEquals(listOf("fakevideo02", "fakevideo03"), r.state.queue.map { it.id })

        playFor(r, 20_000)
        runCurrent()

        assertEquals("fakevideo02", r.state.item?.id)
        assertEquals(listOf("fakevideo03"), r.state.queue.map { it.id })
        assertTrue("details(fakevideo02)" in r.source.calls)
        assertEquals("fakevideo02", r.loads.last().videoId)
        assertTrue(r.state.playing)
    }

    @Test
    fun withNoQueueAutoplayPlaysTheFirstRelatedVideoNotWatched() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id, "the first related video")
    }

    @Test
    fun autoplaySkipsRelatedVideosWatchedToTheEnd() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 20_000
        r.library.fake.recordWatch(item("fakevideo02"), 580_000, 600_000)
        open(r)
        playFor(r, 20_000)
        runCurrent()
        assertEquals("fakevideo03", r.state.item?.id)
    }

    @Test
    fun withAutoplayOffTheVideoJustEnds() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        runCurrent()
        assertEquals("fakevideo01", r.state.item?.id)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertFalse(r.state.playing)
        assertEquals(listOf("details(fakevideo01)"), r.source.calls)
    }

    @Test
    fun theQueueStillPlaysWithAutoplayOff() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r, "fakevideo01", queue = FakeVideoSource.videos.take(2))
        playFor(r, 20_000)
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
    }

    @Test
    fun anEndedVideoPlaysAgainFromTheStartWhenPlayIsPressed() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        r.controller.togglePlayPause()
        runCurrent()
        assertEquals(PlayerStatus.READY, r.state.status)
        assertTrue(r.state.playing)
        assertEquals(0L, r.controller.positionMs())
    }

    @Test
    fun theAutoplaySwitchStartsAsTheSettingsSay() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        assertFalse(r.state.autoplay)
        open(r)
        assertFalse(r.state.autoplay)
    }

    @Test
    fun theAutoplaySwitchIsTheSettingsAutoplay() = runTest {
        val r = rig()
        assertTrue(r.state.autoplay)
        r.controller.setAutoplay(false)
        runCurrent()
        assertFalse(r.settings.settings.value.autoplay)
        assertFalse(r.state.autoplay)
        r.settings.update { it.copy(autoplay = true) }
        runCurrent()
        assertTrue(r.state.autoplay)
    }

    // ---------------------------------------------------------------- the queue, through the controller

    @Test
    fun addToQueueNextAndPrevious() = runTest {
        val r = rig()
        open(r, "fakevideo01")
        r.controller.addToQueue(item("fakevideo05"))
        r.controller.addToQueue(item("fakevideo06"))
        r.controller.addToQueue(item("fakevideo05"))
        assertEquals(listOf("fakevideo05", "fakevideo06"), r.state.queue.map { it.id })

        r.controller.playNext()
        runCurrent()
        assertEquals("fakevideo05", r.state.item?.id)
        assertEquals(listOf("fakevideo06"), r.state.queue.map { it.id })

        r.controller.playPrevious()                       // under 3 s into it: the previous video
        runCurrent()
        assertEquals("fakevideo01", r.state.item?.id)
        assertEquals(listOf("fakevideo05", "fakevideo06"), r.state.queue.map { it.id })
    }

    @Test
    fun previousAfterThreeSecondsRestartsTheVideo() = runTest {
        val r = rig()
        open(r, "fakevideo01", queue = FakeVideoSource.videos.take(3))
        r.controller.playNext()
        runCurrent()
        playFor(r, 5_000)
        r.controller.playPrevious()
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
        assertEquals(0L, r.controller.positionMs())
    }

    @Test
    fun nextWithNothingQueuedPlaysTheRelatedPickEvenWithAutoplayOff() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        open(r)
        r.controller.playNext()
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
    }

    @Test
    fun aVideoOpenedFromACardCanBeGoneBackFrom() = runTest {
        val r = rig()
        open(r, "fakevideo01")
        open(r, "fakevideo04")
        r.controller.playPrevious()
        runCurrent()
        assertEquals("fakevideo01", r.state.item?.id)
    }

    @Test
    fun playingAPlaylistFromTheMiddleQueuesTheRestAndKeepsTheEarlierOnesForPrevious() = runTest {
        val r = rig()
        val all = FakeVideoSource.playlistItems.take(5)
        open(r, all[2].id, queue = all)
        assertEquals(all.drop(3).map { it.id }, r.state.queue.map { it.id })
        r.controller.playPrevious()
        runCurrent()
        assertEquals(all[1].id, r.state.item?.id)
    }

    // ---------------------------------------------------------------- 7. background

    @Test
    fun leavingWithBackgroundOnGoesAudioOnlyAndComingBackBringsTheVideoBack() = runTest {
        val r = rig()
        open(r)
        playFor(r, 2_000)
        r.engine.clearCalls()

        r.controller.onUiVisible(false)
        runCurrent()
        assertEquals(listOf<Call>(Call.SetAudioOnly(true)), r.engine.calls)
        assertTrue(r.state.audioOnly)
        assertTrue(r.state.playing, "the sound carries on")

        r.controller.onUiVisible(true)
        runCurrent()
        assertEquals(Call.SetAudioOnly(false), r.engine.calls.last())
        assertFalse(r.state.audioOnly)
    }

    @Test
    fun leavingWithBackgroundOffPauses() = runTest {
        val r = rig(VideoSettings(background = false))
        open(r)
        playFor(r, 2_000)
        r.engine.clearCalls()

        r.controller.onUiVisible(false)
        runCurrent()
        assertEquals(listOf<Call>(Call.Pause), r.engine.calls)
        assertFalse(r.state.playing)

        r.controller.onUiVisible(true)
        runCurrent()
        assertEquals(Call.SetAudioOnly(false), r.engine.calls.last())
        assertTrue(r.engine.calls.none { it == Call.Play }, "coming back does not resume what the user left paused")
    }

    @Test
    fun aShortDoesNotPlayOnInTheBackground() = runTest {
        val r = rig()
        open(r, "fakeshort01", short = true)
        r.engine.clearCalls()
        r.controller.onUiVisible(false)
        runCurrent()
        assertEquals(listOf<Call>(Call.Pause), r.engine.calls)
    }

    @Test
    fun theNextVideoOfTheBackgroundStartsAudioOnly() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 10_000
        open(r)
        r.controller.onUiVisible(false)
        runCurrent()
        playFor(r, 10_000)
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
        assertTrue(r.engine.snapshot.value.audioOnly)
        assertTrue(r.state.playing)
    }

    @Test
    fun aVideoThatFinishedLoadingAfterTheUiLeftWithBackgroundOffDoesNotStart() = runTest {
        val r = rig(VideoSettings(background = false))
        r.controller.play("fakevideo01")
        r.controller.onUiVisible(false)
        runCurrent()
        assertTrue(r.engine.calls.none { it == Call.Play })
        assertFalse(r.state.playing)
    }

    // ---------------------------------------------------------------- 8. errors from details

    @Test
    fun errorsFromDetailsMapToTheUiErrorAndStop() = runTest {
        val cases = listOf(
            VideoSourceError.NotAvailable("x") to VideoUiError.UNAVAILABLE,
            VideoSourceError.AgeRestricted("x") to VideoUiError.AGE,
            VideoSourceError.MadeForKids("x") to VideoUiError.KIDS,
            VideoSourceError.Private("x") to VideoUiError.PRIVATE,
            VideoSourceError.Paid("x") to VideoUiError.PAID,
            VideoSourceError.GeoBlocked("x") to VideoUiError.GEO,
            VideoSourceError.BotCheck("x") to VideoUiError.BOT,
            VideoSourceError.Network("x", null) to VideoUiError.NETWORK,
            VideoSourceError.Parsing("x", null) to VideoUiError.PARSING,
        )
        for ((error, ui) in cases) {
            val r = rig()
            r.source.failNext(error)
            open(r)
            assertEquals(ui, r.state.error, error::class.simpleName)
            assertEquals(PlayerStatus.ERROR, r.state.status)
            assertFalse(r.state.playing)
            assertTrue(r.engine.calls.none { it is Call.Load || it == Call.Play }, "nothing was loaded")
            assertTrue(r.library.records.isEmpty())
        }
    }

    @Test
    fun anUnknownVideoIsNotAvailable() = runTest {
        val r = rig()
        open(r, "nosuchvideo")
        assertEquals(VideoUiError.UNAVAILABLE, r.state.error)
    }

    @Test
    fun aFailureWhileAnotherVideoIsPlayingStopsTheOldOne() = runTest {
        val r = rig()
        open(r)
        playFor(r, 2_000)
        r.source.failNext(VideoSourceError.GeoBlocked("x"))
        open(r, "fakevideo02")
        assertEquals(VideoUiError.GEO, r.state.error)
        assertFalse(r.engine.snapshot.value.playing, "the old video does not play on behind the message")
    }

    @Test
    fun theEnginesOwnFailuresAreMappedToo() = runTest {
        val network = rig()
        open(network)
        network.engine.fail(PlayerErrorKind.NETWORK)
        runCurrent()
        assertEquals(VideoUiError.NETWORK, network.state.error)
        assertEquals(1, network.source.calls.size, "only a 403 asks for fresh streams")

        val decoder = rig()
        open(decoder)
        decoder.engine.fail(PlayerErrorKind.DECODER)
        runCurrent()
        assertEquals(VideoUiError.STOPPED, decoder.state.error)
    }

    // ---------------------------------------------------------------- 9. offline

    @Test
    fun playLocalGivesTheEngineTheFilesWithNoSourceCall() = runTest {
        val r = rig()
        val files = MediaInput.LocalFiles(muxedPath = "/data/a/fakevideo01.mp4", videoPath = null, audioPath = null)
        r.controller.playLocal(item("fakevideo01"), files)
        runCurrent()

        assertTrue(r.source.calls.isEmpty(), "offline: no source call")
        val request = assertNotNull(r.engine.lastRequest)
        assertEquals(files, request.input)
        assertEquals("fakevideo01", request.videoId)
        assertEquals(0L, request.startMs)
        assertFalse(request.live)
        assertEquals(item("fakevideo01").title, request.title)
        assertEquals(listOf<Call>(Call.Load(request), Call.SetSpeed(1f), Call.Play), r.engine.calls)
        assertTrue(r.chooser.calls.isEmpty())
        assertEquals("fakevideo01", r.state.item?.id)
        assertTrue(r.state.playing)
        assertTrue(r.state.ladder.isEmpty())
        assertTrue(request.localSubtitles.isEmpty() && request.subtitles.isEmpty())
        val details = assertNotNull(r.state.details, "a stand-in made without the source")
        assertEquals("fakevideo01", details.item.id)
        assertTrue(details.related.isEmpty() && details.chapters.isEmpty() && details.subtitles.isEmpty())
    }

    @Test
    fun aBundleBringsItsSubtitlesChaptersAndSegmentsWithNoSourceCall() = runTest {
        val r = rig(VideoSettings(captionsLanguage = "hi"), SpySegments(listOf(promo)))
        val files = MediaInput.LocalFiles(muxedPath = "/data/a/fakevideo01.mp4", videoPath = null, audioPath = null)
        val subtitles = listOf(
            LocalSubtitle("/data/a/fakevideo01.en.vtt", "en", "English", autoGenerated = false),
            LocalSubtitle("/data/a/fakevideo01.hi.vtt", "hi", "Hindi", autoGenerated = false),
        )
        val chapters = listOf(Chapter("Start", 0, null), Chapter("The main part", 90_000, null))
        val bundle = LocalBundle(files, subtitles, chapters, listOf(sponsor))
        r.controller.playLocal(item("fakevideo01"), bundle)
        runCurrent()

        assertTrue(r.source.calls.isEmpty(), "offline: no source call")
        assertTrue(r.segments.asked.isEmpty(), "the cached segments are the bundle's: nothing is asked")
        assertTrue(r.chooser.calls.isEmpty())
        val request = assertNotNull(r.engine.lastRequest)
        assertEquals(files, request.input)
        assertEquals(subtitles, request.localSubtitles, "the files reach the engine through the request")
        assertTrue(request.subtitles.isEmpty())
        assertEquals(Call.SelectSubtitle("hi"), r.engine.calls.single { it is Call.SelectSubtitle }, "the caption setting finds the saved file")

        assertEquals(chapters, r.state.details?.chapters)
        assertEquals(listOf("en", "hi"), r.state.subtitles.map { it.language })
        assertEquals(listOf("English", "Hindi"), r.state.subtitles.map { it.name })
        assertEquals("hi", r.state.subtitleLanguage)
        assertEquals(listOf(sponsor), r.state.segments)
        assertTrue(r.state.playing)
    }

    @Test
    fun aDownloadsSavedSegmentsDriveTheSkipLikeFetchedOnes() = runTest {
        val r = rig(VideoSettings(), SpySegments(), TestDecider())
        r.controller.playLocal(item("fakevideo01"), LocalBundle(MediaInput.LocalFiles("/a.mp4", null, null), segments = listOf(sponsor)))
        runCurrent()
        playFor(r, 30_000)
        assertEquals(sponsor, r.state.lastSkip)
        assertTrue(Call.SeekTo(60_000) in r.engine.calls)
        assertTrue(r.source.calls.isEmpty())
    }

    @Test
    fun aBundlesSegmentsOfACategoryThatIsOffNowAreLeftOut() = runTest {
        val off = SegmentCategory.defaults() + (SegmentCategory.SPONSOR to SegmentBehaviour.OFF)
        val r = rig(VideoSettings(segmentBehaviours = off))
        r.controller.playLocal(item("fakevideo01"), LocalBundle(MediaInput.LocalFiles("/a.mp4", null, null), segments = listOf(sponsor, promo)))
        runCurrent()
        assertEquals(listOf(promo), r.state.segments)
    }

    @Test
    fun theFilesOnlyOverloadIsABundleWithNothingElse() = runTest {
        val r = rig()
        val files = MediaInput.LocalFiles("/a.mp4", null, null)
        r.controller.playLocal(item("fakevideo01"), files)
        runCurrent()
        assertEquals(files, r.engine.lastRequest?.input)
        assertTrue(r.engine.lastRequest?.localSubtitles.orEmpty().isEmpty())
        assertTrue(r.state.segments.isEmpty())
    }

    @Test
    fun aDownloadPlaysAgainFromTheBundleAfterAFailure() = runTest {
        val r = rig()
        val subtitles = listOf(LocalSubtitle("/a.en.vtt", "en", "English", autoGenerated = false))
        r.controller.playLocal(item("fakevideo01"), LocalBundle(MediaInput.LocalFiles("/a.mp4", null, null), subtitles))
        runCurrent()
        playFor(r, 5_000)
        r.engine.fail(PlayerErrorKind.DECODER)
        runCurrent()
        assertEquals(VideoUiError.STOPPED, r.state.error)
        r.controller.togglePlayPause()                 // Try again
        runCurrent()
        assertEquals(2, r.loads.size)
        assertEquals(subtitles, r.loads.last().localSubtitles)
        assertEquals(5_000L, r.loads.last().startMs)
        assertTrue(r.source.calls.isEmpty())
    }

    @Test
    fun playLocalResumesFromTheHistoryAndRecordsHistoryAsForAnyVideo() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakevideo01"), 90_000, 754_000)
        r.library.records.clear()
        val files = MediaInput.LocalFiles("/data/a.mp4", null, null)
        r.controller.playLocal(item("fakevideo01"), files)
        runCurrent()
        assertEquals(90_000L, r.engine.lastRequest?.startMs)
        playFor(r, 10_000)
        assertEquals(listOf(100_000L), r.library.records.map { it.positionMs })
        playFor(r, 3_000)
        r.controller.stop()
        runCurrent()
        assertEquals(listOf(100_000L, 103_000L), r.library.records.map { it.positionMs })
        assertTrue(r.library.records.all { it.videoId == "fakevideo01" })
    }

    @Test
    fun aLocalVideoDoesNotRefreshOrAutoplayOverTheNetwork() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 10_000
        r.controller.playLocal(item("fakevideo01"), MediaInput.LocalFiles("/a.mp4", null, null))
        runCurrent()
        playFor(r, 10_000)
        runCurrent()
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertTrue(r.source.calls.isEmpty())

        val again = rig()
        again.controller.playLocal(item("fakevideo01"), MediaInput.LocalFiles("/a.mp4", null, null))
        runCurrent()
        again.engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        runCurrent()
        assertEquals(VideoUiError.STOPPED, again.state.error)
        assertTrue(again.source.calls.isEmpty())
    }

    // ---------------------------------------------------------------- 10. Shorts

    @Test
    fun aShortLoopsIgnoresTheHistoryPositionAndIsRecordedAsAShort() = runTest {
        val r = rig()
        r.library.fake.recordWatch(item("fakeshort01"), 5_000, 17_000)
        r.library.records.clear()
        open(r, "fakeshort01", short = true)

        assertTrue(r.state.loop)
        assertTrue(r.state.short)
        assertEquals(0L, r.engine.lastRequest?.startMs, "no resume for a Short")
        assertEquals(9f / 16f, r.state.aspect)

        playFor(r, 12_000)
        r.controller.stop()
        runCurrent()
        assertTrue(r.library.records.all { it.videoId == "fakeshort01" && it.isShort })
        assertEquals(listOf(10_000L, 12_000L), r.library.records.map { it.positionMs })
    }

    @Test
    fun aShortNeverAutoplaysAtTheEndItLoops() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 15_000
        open(r, "fakeshort01", short = true)
        r.controller.addToQueue(item("fakevideo05"))             // not for a Short
        playFor(r, 15_000)
        runCurrent()
        assertEquals("fakeshort01", r.state.item?.id)
        assertTrue(r.state.playing)
        assertEquals(1, r.loads.size)
        assertEquals(Call.SeekTo(0), r.engine.calls.last { it is Call.SeekTo })
        assertEquals(listOf("details(fakeshort01)"), r.source.calls)
    }

    @Test
    fun aShortThatTheUserTurnedLoopOffJustStopsAtTheEnd() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 15_000
        open(r, "fakeshort01", short = true)
        r.controller.setLoop(false)
        playFor(r, 15_000)
        runCurrent()
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertEquals(listOf("details(fakeshort01)"), r.source.calls, "the pager moves on, not the controller")
    }

    @Test
    fun theMuteChoiceIsKeptFromShortToShort() = runTest {
        val r = rig()
        open(r, "fakeshort01", short = true)
        r.controller.setMuted(true)
        assertEquals(Call.SetMuted(true), r.engine.calls.last())
        assertTrue(r.state.muted)

        open(r, "fakeshort02", short = true)
        assertTrue(r.state.muted)
        assertTrue(r.engine.snapshot.value.muted)
        r.controller.setMuted(false)
        assertEquals(Call.SetMuted(false), r.engine.calls.last())
        assertFalse(r.state.muted)
        assertFalse(r.engine.snapshot.value.muted)
    }

    @Test
    fun theMuteChoiceIsAppliedAgainOnAnEngineThatForgetsItOnLoad() = runTest {
        // An engine whose load resets the mute, as a new media item can.
        val fake = FakeVideoPlayerEngine()
        val forgetful = object : VideoPlayerEngine by fake {
            override fun load(request: PlaybackRequest) {
                fake.load(request)
                fake.setMuted(false)
            }
        }
        val r = Rig(this, engine = fake, wrap = { forgetful })
        open(r, "fakeshort01", short = true)
        r.controller.setMuted(true)
        fake.clearCalls()
        open(r, "fakeshort02", short = true)
        assertTrue(Call.SetMuted(true) in fake.calls)
        assertTrue(fake.snapshot.value.muted)
    }

    @Test
    fun theMuteSetBeforeAnythingPlaysIsKept() = runTest {
        val r = rig()
        r.controller.setMuted(true)
        assertEquals(listOf<Call>(Call.SetMuted(true)), r.engine.calls)
        assertTrue(r.state.muted)
        open(r, "fakeshort01", short = true)
        assertTrue(r.engine.snapshot.value.muted)
        assertTrue(r.state.muted)
    }

    @Test
    fun prefetchFetchesTheDetailsOnceAndDoesNotTouchTheEngine() = runTest {
        val r = rig()
        r.controller.prefetch("fakeshort02")
        r.controller.prefetch("fakeshort02")            // a second call while the first is in flight, and after it
        runCurrent()
        r.controller.prefetch("fakeshort02")
        runCurrent()
        assertEquals(listOf("details(fakeshort02)"), r.source.calls)
        assertTrue(r.engine.calls.isEmpty())
        assertEquals(PlaybackUiState.Idle, r.state)

        // The page that settles on it plays from the cache.
        open(r, "fakeshort02", short = true)
        assertEquals(listOf("details(fakeshort02)"), r.source.calls)
        assertEquals("fakeshort02", r.state.item?.id)
        assertEquals("fakeshort02", r.engine.lastRequest?.videoId)
    }

    @Test
    fun playingWhileThePrefetchIsStillInFlightSharesIt() = runTest {
        val r = rig()
        r.controller.prefetch("fakeshort02")
        r.controller.play("fakeshort02", short = true)
        runCurrent()
        assertEquals(listOf("details(fakeshort02)"), r.source.calls)
        assertEquals("fakeshort02", r.state.item?.id)
    }

    @Test
    fun theDetailsCacheKeepsTenMinutes() = runTest {
        val r = rig()
        open(r, "fakevideo01")
        advanceTimeBy(9 * 60_000L)
        open(r, "fakevideo02")
        open(r, "fakevideo01")
        assertEquals(1, r.source.calls.count { it == "details(fakevideo01)" }, "9 minutes: from the cache")
        advanceTimeBy(2 * 60_000L)
        open(r, "fakevideo02")
        open(r, "fakevideo01")
        assertEquals(2, r.source.calls.count { it == "details(fakevideo01)" }, "11 minutes: fetched again")
    }

    @Test
    fun aFailedPrefetchIsNotCachedAndThePlayReportsTheError() = runTest {
        val r = rig()
        r.source.failNext(VideoSourceError.Network("offline", null))
        r.controller.prefetch("fakeshort02")
        runCurrent()
        assertEquals(PlaybackUiState.Idle, r.state, "a prefetch never shows an error")
        open(r, "fakeshort02", short = true)
        assertEquals(2, r.source.calls.size)
        assertNull(r.state.error)
        assertTrue(r.state.playing)
    }

    // ---------------------------------------------------------------- the rest of the interface

    @Test
    fun positionMsForwardsToTheEngine() = runTest {
        val r = rig()
        open(r)
        r.engine.setPosition(12_345)
        assertEquals(12_345L, r.controller.positionMs())
        assertSame(r.engine, r.controller.engine)
    }

    @Test
    fun playPauseSeekSpeedAndSubtitlesDriveTheEngine() = runTest {
        val r = rig()
        open(r)
        playFor(r, 4_000)

        r.controller.togglePlayPause()
        runCurrent()
        assertEquals(Call.Pause, r.engine.calls.last())
        assertFalse(r.state.playing)
        r.controller.togglePlayPause()
        runCurrent()
        assertEquals(Call.Play, r.engine.calls.last())
        assertTrue(r.state.playing)

        r.controller.seekTo(100_000)
        assertEquals(Call.SeekTo(100_000), r.engine.calls.last())
        r.controller.seekBy(-30_000)
        assertEquals(Call.SeekTo(70_000), r.engine.calls.last())
        r.controller.seekBy(-500_000)
        assertEquals(Call.SeekTo(0), r.engine.calls.last(), "a seek stays inside the video")
        r.controller.seekTo(900_000)
        assertEquals(Call.SeekTo(600_000), r.engine.calls.last())

        r.controller.setSpeed(1.75f)
        assertEquals(Call.SetSpeed(1.75f), r.engine.calls.last())
        assertEquals(1.75f, r.state.speed)
        r.controller.setSpeed(9f)
        assertEquals(Call.SetSpeed(4f), r.engine.calls.last())
        r.controller.setSpeed(0.1f)
        assertEquals(Call.SetSpeed(0.25f), r.engine.calls.last())

        r.controller.selectSubtitles("hi")
        assertEquals(Call.SelectSubtitle("hi"), r.engine.calls.last())
        assertEquals("hi", r.state.subtitleLanguage)
        r.controller.selectSubtitles(null)
        assertNull(r.state.subtitleLanguage)
    }

    @Test
    fun stopRecordsPausesAndReturnsToIdle() = runTest {
        val r = rig(VideoSettings(autoplay = true))
        open(r, "fakevideo01", queue = FakeVideoSource.videos.take(3))
        r.controller.setMuted(true)
        playFor(r, 6_000)
        r.controller.stop()
        runCurrent()
        assertEquals(Call.Pause, r.engine.calls.last())
        assertEquals(1, r.engine.unloadCount, "stop clears the engine so no session or notification stays")
        assertEquals(PlaybackUiState.Idle.copy(muted = true), r.state)
        assertEquals(6_000L, r.library.fake.positionOf("fakevideo01"))
        // The ticker is gone: no more history however long we wait.
        val records = r.library.records.size
        advanceTimeBy(60_000)
        assertEquals(records, r.library.records.size)
    }

    @Test
    fun theStateOfAPlayingVideoIsOneObjectUntilSomethingHappens() = runTest {
        val r = rig()
        open(r)
        val before = r.state
        playFor(r, 2_000)                     // only the playhead moves: it is not in the state
        assertTrue(before === r.state)
    }

    @Test
    fun theStateCarriesTheBufferedAmountAndTheVideosOwnShape() = runTest {
        val r = rig()
        open(r)
        r.engine.setSnapshot { it.copy(bufferedMs = 30_000, videoWidth = 1920, videoHeight = 800, status = PlayerStatus.BUFFERING) }
        runCurrent()
        assertEquals(30_000L, r.state.bufferedMs)
        assertEquals(1920f / 800f, r.state.aspect)
        assertEquals(PlayerStatus.BUFFERING, r.state.status)
    }

    @Test
    fun aNewVideoShowsLoadingAtOnceAndForgetsTheOldOnesSegmentsAndSkips() = runTest {
        val r = rig(VideoSettings(), SpySegments(listOf(sponsor)), TestDecider())
        open(r)
        playFor(r, 30_000)
        assertNotNull(r.state.lastSkip)
        r.controller.play("fakevideo02")
        // Before the controller runs: loading, nothing left over.
        val loading = r.state
        assertEquals(PlayerStatus.LOADING, loading.status)
        assertNull(loading.lastSkip)
        assertTrue(loading.segments.isEmpty())
        assertFalse(loading.playing)
        assertTrue(loading.ladder.isEmpty())
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
    }

    @Test
    fun aVideoThatIsReplacedBeforeItLoadsNeverLoads() = runTest {
        val r = rig()
        r.controller.play("fakevideo01")
        r.controller.play("fakevideo02")
        runCurrent()
        assertEquals("fakevideo02", r.state.item?.id)
        assertEquals(listOf("fakevideo02"), r.loads.map { it.videoId })
    }

    @Test
    fun theSettingsAreReadWhenAVideoLoadsNotWhenTheControllerIsMade() = runTest {
        val r = rig()
        r.settings.update { it.copy(defaultSpeed = 2f, captionsLanguage = "hi") }
        open(r)
        assertTrue(Call.SetSpeed(2f) in r.engine.calls)
        assertEquals("hi", r.state.subtitleLanguage)
        assertEquals(2f, r.chooser.calls.single().settings.defaultSpeed)
    }

    @Test
    fun theFakeSegmentProviderOfTheFakesWorksWithTheController() = runTest {
        val r = Rig(this, segments = SpySegments(FakeSegmentProvider.SAMPLE))
        open(r)
        assertEquals(FakeSegmentProvider.SAMPLE.filter { it.category != SegmentCategory.FILLER }, r.state.segments)
    }

    // ---------------------------------------------------------------- unload

    @Test
    fun theEngineIsNotUnloadedWhileVideosPlayOrSwitch() = runTest {
        val r = rig()
        open(r, "fakevideo01", queue = FakeVideoSource.videos.take(3))
        playFor(r, 3_000)
        r.controller.playNext()
        runCurrent()
        r.controller.selectQuality(r.state.ladder[2])
        runCurrent()
        r.controller.onUiVisible(false)
        r.controller.onUiVisible(true)
        assertEquals(0, r.engine.unloadCount)
    }

    @Test
    fun stopUnloadsEvenWhenNothingIsPlaying() = runTest {
        val r = rig()
        r.controller.stop()
        assertEquals(1, r.engine.unloadCount)
        open(r)
        r.controller.stop()
        assertEquals(2, r.engine.unloadCount)
    }

    @Test
    fun whenTheEndComesWithNothingNextTheEngineIsUnloadedAndTheStateStaysEnded() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        runCurrent()
        assertEquals(1, r.engine.unloadCount)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        assertFalse(r.state.playing)
        assertNull(r.state.error)
        assertEquals("fakevideo01", r.state.item?.id)
        assertEquals(20_000L, r.state.durationMs)
        assertEquals(PlayerStatus.IDLE, r.engine.snapshot.value.status)
        // The history has the end, written before the unload.
        assertTrue(r.library.history.value.single().isWatched)
        // And nothing ticks on: no history, no calls, however long we wait.
        val records = r.library.records.size
        advanceTimeBy(60_000)
        assertEquals(records, r.library.records.size)
    }

    @Test
    fun whenAutoplayPicksOrTheQueueContinuesOrItLoopsTheEngineIsNotUnloaded() = runTest {
        val picks = rig()
        picks.engine.durationOnLoadMs = 20_000
        open(picks)
        playFor(picks, 20_000)
        assertEquals("fakevideo02", picks.state.item?.id)
        assertEquals(0, picks.engine.unloadCount)

        val queued = rig(VideoSettings(autoplay = false))
        queued.engine.durationOnLoadMs = 20_000
        open(queued, "fakevideo01", queue = FakeVideoSource.videos.take(2))
        playFor(queued, 20_000)
        assertEquals("fakevideo02", queued.state.item?.id)
        assertEquals(0, queued.engine.unloadCount)

        val looping = rig(VideoSettings(autoplay = false))
        looping.engine.durationOnLoadMs = 20_000
        open(looping)
        looping.controller.setLoop(true)
        playFor(looping, 20_000)
        assertEquals(0, looping.engine.unloadCount)

        val short = rig()
        short.engine.durationOnLoadMs = 15_000
        open(short, "fakeshort01", short = true)
        short.controller.setLoop(false)
        playFor(short, 15_000)
        assertEquals(PlayerStatus.ENDED, short.state.status)
        assertEquals(0, short.engine.unloadCount, "a Short waits for its pager with the engine ready")
    }

    @Test
    fun playingAnUnloadedEndedVideoLoadsItAgainFromTheStart() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        assertEquals(1, r.engine.unloadCount)

        r.controller.togglePlayPause()                 // Replay
        runCurrent()
        assertEquals(2, r.loads.size)
        assertEquals(0L, r.loads.last().startMs)
        assertEquals(PlayerStatus.READY, r.state.status)
        assertTrue(r.state.playing)
        assertEquals(1, r.engine.unloadCount)
        playFor(r, 2_000)
        assertEquals(2_000L, r.controller.positionMs())
    }

    @Test
    fun aSeekOrASkipBackInAnUnloadedEndedVideoPlaysFromThere() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        r.controller.seekTo(5_000)
        runCurrent()
        assertEquals(5_000L, r.loads.last().startMs)
        assertTrue(r.state.playing)

        val again = rig(VideoSettings(autoplay = false))
        again.engine.durationOnLoadMs = 20_000
        open(again)
        playFor(again, 20_000)
        again.controller.seekBy(-10_000)
        runCurrent()
        assertEquals(10_000L, again.loads.last().startMs, "back by 10 s from the end")
    }

    @Test
    fun tappingTheEndedVideoAgainPlaysItEvenAfterTheUnload() = runTest {
        val r = rig(VideoSettings(autoplay = false))
        r.engine.durationOnLoadMs = 20_000
        open(r)
        playFor(r, 20_000)
        open(r)
        assertEquals(2, r.loads.size)
        assertTrue(r.state.playing)
    }

    @Test
    fun aLocalVideoThatEndsIsUnloadedToo() = runTest {
        val r = rig()
        r.engine.durationOnLoadMs = 10_000
        r.controller.playLocal(item("fakevideo01"), MediaInput.LocalFiles("/a.mp4", null, null))
        runCurrent()
        playFor(r, 10_000)
        assertEquals(1, r.engine.unloadCount)
        assertEquals(PlayerStatus.ENDED, r.state.status)
        r.controller.togglePlayPause()
        runCurrent()
        assertEquals(2, r.loads.size)
        assertEquals(MediaInput.LocalFiles("/a.mp4", null, null), r.loads.last().input)
        assertTrue(r.source.calls.isEmpty())
    }

    // ---------------------------------------------------------------- what screens read from the state

    @Test
    fun theQueueInTheStateIsWhatPlaysAfterTheCurrentVideo() = runTest {
        val r = rig()
        val all = FakeVideoSource.playlistItems.take(4)
        open(r, all[1].id, queue = all)
        assertEquals(listOf(all[2].id, all[3].id), r.state.queue.map { it.id })
        assertTrue(r.state.queue.none { it.id == r.state.item?.id })
    }
}
