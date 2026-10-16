package com.cosmotune.video.model

import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.DownloadState
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.downloads.isDownloaded
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.library.HistoryEntry
import com.cosmotune.video.library.LibraryImport
import com.cosmotune.video.library.Subscription
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.FakeVideoPlayerEngine
import com.cosmotune.video.player.MediaInput
import com.cosmotune.video.player.PlaybackRequest
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerErrorKind
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.player.QualityOption
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.shorts.ShortsSeedCache
import com.cosmotune.video.source.FakeVideoSource
import kotlinx.coroutines.runBlocking
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The pure helpers on the models (spec 5.0, 6.1, 6.4), the Shorts seed cache (5.15) and the download fake's queue (5.16). */
class VideoModelsTest {

    private val item = VideoItem(
        id = "abc", title = "A video", channelId = null, channelName = null, channelAvatarUrl = null, thumbnailUrl = null,
        durationSec = 200, viewCount = null, uploadedAtMs = null, uploadedText = null,
    )

    @AfterTest
    fun clearTheSeedCache() = ShortsSeedCache.clear()

    @Test
    fun theWatchedFractionIsShownFromTenSecondsAndClamped() {
        assertEquals(0.5f, item.watchedFraction(positionMs = 60_000, durationMs = 120_000))
        assertNull(item.watchedFraction(positionMs = 9_999, durationMs = 120_000))          // the bar shows from 10 s
        assertEquals(10_000f / 120_000f, item.watchedFraction(positionMs = 10_000, durationMs = 120_000))
        assertEquals(1f, item.watchedFraction(positionMs = 500_000, durationMs = 120_000))
        assertEquals(0.25f, item.watchedFraction(positionMs = 50_000, durationMs = 0))        // the item's own 200 s
        assertNull(item.copy(durationSec = null).watchedFraction(positionMs = 50_000, durationMs = 0))
    }

    @Test
    fun anEntryIsWatchedFrom90Percent() {
        fun entry(position: Long, duration: Long) = HistoryEntry(item, lastWatchedMs = 0, positionMs = position, durationMs = duration, watchCount = 1)
        assertTrue(entry(90_000, 100_000).isWatched)
        assertFalse(entry(89_999, 100_000).isWatched)
        assertTrue(entry(180_000, 0).isWatched)    // 90 % of the item's 200 s
        assertFalse(HistoryEntry(item.copy(durationSec = null), 0, 50_000, 0, 1).isWatched)
    }

    @Test
    fun aStreamSetExpiresTwoMinutesBeforeItsTime() {
        val set = StreamSet(emptyList(), emptyList(), emptyList(), null, null, expiresAtMs = 1_000_000, requestHeaders = emptyMap(), durationMs = null)
        assertFalse(set.isExpired(1_000_000 - 120_001))
        assertTrue(set.isExpired(1_000_000 - 120_000))
        assertTrue(set.isExpired(2_000_000))
        assertFalse(set.copy(expiresAtMs = null).isExpired(Long.MAX_VALUE))
    }

    @Test
    fun aQualityLabelShowsTheFrameRateAbove30AndHdr() {
        assertEquals("1080p60", QualityOption.labelOf(1080, 60, hdr = false))
        assertEquals("1080p", QualityOption.labelOf(1080, 30, hdr = false))
        assertEquals("2160p60 HDR", QualityOption.labelOf(2160, 60, hdr = true))
        assertEquals("720p", QualityOption.labelOf(720, 25, hdr = false))
    }

    @Test
    fun theSeedCacheKeepsEightKeysAndTakeRemoves() {
        val keys = (1..9).map { n -> ShortsSeedCache.put(listOf(item.copy(id = "short$n", isShort = true))) }
        assertEquals(9, keys.toSet().size)
        assertNull(ShortsSeedCache.take(keys.first()))   // the oldest of nine is gone
        val last = assertNotNull(ShortsSeedCache.take(keys.last()))
        assertEquals("short9", last.single().id)
        assertNull(ShortsSeedCache.take(keys.last()))    // taken once
        for (k in keys.subList(1, 8)) assertNotNull(ShortsSeedCache.take(k))
        ShortsSeedCache.put("mine", listOf(item))
        assertEquals(listOf(item), ShortsSeedCache.take("mine"))
    }

    @Test
    fun theShortsFakeServesTwelveShortsSixAtATimeForEveryOrigin() = runBlocking {
        val feed = FakeShortsFeed()
        for (origin in listOf(ShortsOrigin.Home, ShortsOrigin.Subscriptions, ShortsOrigin.Channel(FakeVideoSource.CHANNEL_1),
            ShortsOrigin.Search("q"), ShortsOrigin.Related("fakeshort05"))) {
            val first = feed.start(origin)
            assertEquals(6, first.items.size, "$origin")
            val second = feed.more(origin, assertNotNull(first.next))
            assertEquals(6, second.items.size)
            assertNull(second.next)
            val all = first.items + second.items
            assertEquals((1..12).map { "fakeshort%02d".format(it) }.toSet(), all.map { it.id }.toSet())
            assertTrue(all.all { it.isShort })
        }
        val seeded = feed.start(ShortsOrigin.Related("fakeshort07"), seed = listOf(FakeShortsFeed.shorts[6]))
        assertEquals("fakeshort07", seeded.items.first().id)
        assertEquals(6, seeded.items.size)
    }

    @Test
    fun theDownloadFakeRunsOneItemAtATimeUntilDone() {
        val downloads = FakeDownloadController()
        val request = DownloadRequest(DownloadQuality.Rung(720, 30), SubtitlePick(enabled = true, language = "en"))
        val a = FakeVideoSource.videos[0]
        val b = FakeVideoSource.videos[1]
        downloads.enqueue(a, request)
        downloads.enqueue(b, request)
        assertTrue(downloads.downloads.value.all { it.state == DownloadState.Waiting })
        downloads.advance()
        assertIs<DownloadState.Running>(downloads.downloads.value[0].state)
        assertEquals(DownloadState.Waiting, downloads.downloads.value[1].state)
        downloads.runUntilIdle()
        assertTrue(downloads.isDownloaded(a.id) && downloads.isDownloaded(b.id))
        assertEquals("English", downloads.downloads.value[0].subtitleGot)
        assertFalse(downloads.isDownloaded("fakevideo03"))
        val used = downloads.storage().first
        assertEquals(downloads.downloads.value.sumOf { (it.state as DownloadState.Done).bytes }, used)
    }

    // ---- the fakes other tasks build on (plan task Y-2, step 3) ----

    @Test
    fun theEngineFakeMovesItsPlayheadWithTheVirtualClockAndEnds() {
        val engine = FakeVideoPlayerEngine()
        val request = PlaybackRequest("fakevideo01", MediaInput.Hls("https://media.cosmotune.invalid/x.m3u8"), emptyList(), emptyMap(),
            startMs = 5_000, live = false, title = "t", channel = null, thumbnailUrl = null)
        engine.load(request)
        assertEquals(PlayerStatus.READY, engine.snapshot.value.status)
        assertEquals(600_000L, engine.snapshot.value.durationMs)
        assertFalse(engine.snapshot.value.playing)
        assertEquals(5_000L, engine.positionMs())
        engine.play()
        engine.advance(1_000)
        assertEquals(6_000L, engine.positionMs())
        engine.setSpeed(2f)
        engine.advance(1_000)
        assertEquals(8_000L, engine.positionMs())
        engine.seekTo(599_000)
        engine.advance(5_000)
        assertEquals(PlayerStatus.ENDED, engine.snapshot.value.status)
        assertEquals(600_000L, engine.positionMs())
        assertEquals(7_000L, engine.nowMs)
        engine.fail(PlayerErrorKind.SOURCE_EXPIRED)
        assertEquals(PlayerErrorKind.SOURCE_EXPIRED, engine.snapshot.value.error?.kind)
        assertEquals(request, engine.lastRequest)
        assertEquals(
            listOf(FakeVideoPlayerEngine.Call.Load(request), FakeVideoPlayerEngine.Call.Play, FakeVideoPlayerEngine.Call.SetSpeed(2f),
                FakeVideoPlayerEngine.Call.SeekTo(599_000)),
            engine.calls,
        )
        engine.load(request.copy(live = true))
        assertEquals(0L, engine.snapshot.value.durationMs)
    }

    @Test
    fun thePlaybackFakeReactsToItsActions() {
        val playback = FakeVideoPlayback()
        assertEquals(PlaybackUiState.Idle, playback.state.value)
        playback.play("fakevideo01")
        assertEquals("fakevideo01", playback.state.value.item?.id)
        assertTrue(playback.state.value.playing)
        playback.togglePlayPause()
        assertFalse(playback.state.value.playing)
        playback.seekBy(10_000)
        assertEquals(10_000L, playback.positionMs())
        playback.addToQueue(FakeVideoSource.videos[1])
        playback.playNext()
        assertEquals("fakevideo02", playback.state.value.item?.id)
        assertTrue(playback.state.value.queue.isEmpty())
        playback.play("fakeshort03", short = true)
        assertTrue(playback.state.value.short && playback.state.value.loop)
        assertEquals(9f / 16f, playback.state.value.aspect)
        playback.setMuted(true)
        assertTrue(playback.state.value.muted && playback.engine.snapshot.value.muted)
        playback.stop()
        assertEquals(PlaybackUiState.Idle, playback.state.value)
        assertEquals("play(fakevideo01)", playback.calls.first())
        assertEquals("stop()", playback.calls.last())
    }

    @Test
    fun theLibraryFakeKeepsTheRulesOfSpec81() {
        var now = FakeVideoSource.NOW_MS
        val library = FakeVideoLibrary { now }
        val (a, b) = FakeVideoSource.videos
        library.recordWatch(a, 10_000, 754_000)
        library.recordWatch(b, 20_000, 600_000)
        library.recordWatch(a, 30_000, 754_000)
        assertEquals(listOf(a.id, b.id), library.history.value.map { it.item.id })
        assertEquals(1, library.history.value.first().watchCount)
        now += 31 * 60_000L
        library.recordWatch(a, 40_000, 754_000)
        assertEquals(2, library.history.value.first().watchCount)            // again after 30 minutes
        assertEquals(40_000L, library.positionOf(a.id))
        library.setHistoryPaused(true)
        library.recordWatch(FakeVideoSource.videos[2], 50_000, 1_325_000)
        assertEquals(2, library.history.value.size)
        library.addToWatchLater(a); library.addToWatchLater(b); library.addToWatchLater(a)
        library.moveInWatchLater(0, 1)
        assertEquals(listOf(b.id, a.id), library.watchLater.value.map { it.id })
        val id = library.createPlaylist("Mine")
        library.addToPlaylist(id, a); library.addToPlaylist(id, a); library.addToPlaylist(id, b)
        library.removeFromPlaylist(id, a.id)
        assertEquals(listOf(b.id), library.playlists.value.single().items.map { it.id })
        repeat(60) { library.addSearch("query $it") }
        library.addSearch("  Query 59 ")
        assertEquals(50, library.searches.value.size)
        assertEquals("Query 59", library.searches.value.first())
        library.subscribe(FakeVideoSource.channels[0]); library.subscribe(FakeVideoSource.channels[0])
        assertEquals(1, library.subscriptions.value.size)
        assertTrue(library.exportJson().contains("\"format\":\"cosmotune-video\""))
        val report = library.importDocument(
            LibraryImport(
                subscriptions = FakeVideoSource.channels.map { Subscription(it.id, it.name, null, 0, null) },
                history = listOf(HistoryEntry(b, lastWatchedMs = now + 1, positionMs = 99_000, durationMs = 600_000, watchCount = 3)),
                playlists = emptyList(), savedPlaylists = emptyList(), watchLater = emptyList(), liked = listOf(a), searches = emptyList(),
            ),
        )
        assertEquals(1, report.subscriptions)
        assertEquals(1, report.history)
        assertEquals(99_000L, library.positionOf(b.id))      // the newer watch wins
        assertEquals(listOf(a.id), library.liked.value.map { it.id })
        assertTrue(FakeVideoLibrary.withSamples().history.value.isNotEmpty())
    }

    @Test
    fun theDownloadFakeOffersTheLadderAndABatchSkipsWhatItHas() = runBlocking {
        val downloads = FakeDownloadController()
        val options = downloads.options(FakeVideoSource.videos[0])
        assertEquals(listOf(2160, 1440, 1080, 720, 480, 360, 240, 144), options.rungs.map { (it.quality as DownloadQuality.Rung).height })
        assertEquals(listOf("en", "hi", "en"), options.subtitleLanguages.map { it.tag })
        assertEquals(listOf(false, false, true), options.subtitleLanguages.map { it.autoGenerated })
        val batch = downloads.batchOptions(FakeVideoSource.videos.take(3))
        assertTrue(batch.rungs.all { it.isEstimate })
        assertTrue(batch.subtitleLanguages.any { it.tag == "" })
        val request = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))
        downloads.enqueue(FakeVideoSource.videos[0], request)
        val info = com.cosmotune.video.downloads.DownloadBatchInfo("b1", com.cosmotune.video.downloads.BatchKind.PLAYLIST, "Playlist")
        downloads.enqueueBatch(info, FakeVideoSource.videos.take(3), request)
        assertEquals(3, downloads.downloads.value.size)            // the first was already queued
        assertEquals(2, downloads.batches.value.single().total)
        downloads.pauseBatch("b1")
        assertTrue(downloads.batches.value.single().paused)
    }
}
