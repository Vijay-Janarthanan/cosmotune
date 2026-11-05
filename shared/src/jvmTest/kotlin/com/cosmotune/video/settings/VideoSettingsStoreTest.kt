package com.cosmotune.video.settings

import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.library.LibraryCodec
import com.cosmotune.video.library.MemoryLibraryFiles
import com.cosmotune.video.segments.SegmentCategory
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The settings store (plan task Y-7, step 4; spec 5.11): `settings.json`, defaults, round trips, a write 500 ms after the last change. */
@OptIn(ExperimentalCoroutinesApi::class)
class VideoSettingsStoreTest {

    private val now = 1_792_152_000_000L

    private class Rig(val files: MemoryLibraryFiles, val logs: MutableList<String>, val store: VideoSettingsStore)

    private fun TestScope.rig(
        files: MemoryLibraryFiles = MemoryLibraryFiles(),
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ): Rig {
        val logs = ArrayList<String>()
        return Rig(files, logs, VideoSettingsStore(files, backgroundScope, { now }, io) { logs += it })
    }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun withoutAFileTheSettingsAreTheDefaults() = runTest {
        val r = rig()
        val s = r.store.settings.value
        assertEquals(VideoSettings(), s)
        assertNull(s.wifiQuality, "Auto on Wi-Fi")
        assertNull(s.meteredQuality, "Auto on mobile data too")
        assertFalse(s.hideShorts, "Shorts are shown")
        pass(5_000)
        assertEquals(emptyList(), r.files.writes, "nothing is written until something changes")
    }

    @Test
    fun anUpdateChangesTheStateAtOnceAndWritesTheFileFiveHundredMillisecondsLater() = runTest {
        val r = rig()
        r.store.update { it.copy(autoplay = false) }
        assertFalse(r.store.settings.value.autoplay)
        pass(499)
        assertEquals(emptyList(), r.files.writes)
        pass(1)
        assertEquals(listOf("settings.json"), r.files.writes)
        assertFalse(LibraryCodec.decodeSettings(r.files.files.getValue("settings.json")).autoplay)
    }

    @Test
    fun severalUpdatesInARowAreOneWriteOfTheLastState() = runTest {
        val r = rig()
        r.store.update { it.copy(defaultSpeed = 1.5f) }
        pass(300)
        r.store.update { it.copy(defaultSpeed = 2f) }
        pass(300)
        assertEquals(emptyList(), r.files.writes)
        pass(200)
        assertEquals(listOf("settings.json"), r.files.writes)
        assertEquals(2f, LibraryCodec.decodeSettings(r.files.files.getValue("settings.json")).defaultSpeed)
    }

    @Test
    fun anUpdateThatChangesNothingWritesNothing() = runTest {
        val r = rig()
        r.store.update { it }
        r.store.update { it.copy(autoplay = true) }
        pass(2_000)
        assertEquals(emptyList(), r.files.writes)
    }

    @Test
    fun aStoredMeteredQualityOf144Or240RoundTrips() = runTest {
        for (height in listOf(144, 240)) {
            val files = MemoryLibraryFiles()
            val first = rig(files).store
            first.update { it.copy(meteredQuality = height, wifiQuality = 2160) }
            pass(500)
            val second = rig(files).store
            assertEquals(height, second.settings.value.meteredQuality)
            assertEquals(2160, second.settings.value.wifiQuality)
        }
    }

    @Test
    fun aFileWithAMeteredQualityOf144Reads() = runTest {
        val files = MemoryLibraryFiles()
        files.files["settings.json"] = """{"version":1,"meteredQuality":144,"wifiQuality":240}"""
        val s = rig(files).store.settings.value
        assertEquals(144, s.meteredQuality)
        assertEquals(240, s.wifiQuality)
    }

    @Test
    fun autoIsStoredAndComesBackAsAuto() = runTest {
        val files = MemoryLibraryFiles()
        val first = rig(files).store
        first.update { it.copy(wifiQuality = 1080, meteredQuality = 480) }
        pass(500)
        first.update { it.copy(wifiQuality = null, meteredQuality = null) }
        pass(500)
        val s = rig(files).store.settings.value
        assertNull(s.wifiQuality)
        assertNull(s.meteredQuality)
    }

    private val everyFieldChanged = VideoSettings(
        wifiQuality = 1440, meteredQuality = 360, codec = CodecPreference.VP9, defaultSpeed = 1.25f, autoplay = false, resume = false,
        background = false, autoPip = false, doubleTapSeconds = 15, captionsLanguage = "ta", captionsScale = 0.8f,
        captionsBackground = false, sponsorBlock = false,
        segmentBehaviours = SegmentCategory.defaults() + (SegmentCategory.INTRO to SegmentBehaviour.SKIP) + (SegmentCategory.SPONSOR to SegmentBehaviour.MUTE),
        segmentsOnBar = false, countSkips = true, deArrowTitles = false, deArrowThumbnails = false, titleCase = TitleCase.TITLE,
        dislikes = false, hideShorts = true, region = "GB", contentLanguage = "en",
        downloads = DownloadPrefs(false, DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(true, null))),
        pauseOnLowBattery = false, downloadWifiOnly = false, downloadFolder = "/storage/emulated/0/Movies/Cosmotune", firstRunSeen = true,
        homeKiosk = "live",
    )

    @Test
    fun everyFieldRoundTripsThroughTheFile() = runTest {
        val files = MemoryLibraryFiles()
        val first = rig(files).store
        first.update { everyFieldChanged }
        pass(500)
        assertEquals(everyFieldChanged, rig(files).store.settings.value)
    }

    @Test
    fun theDownloadPreferencesRoundTrip() = runTest {
        val files = MemoryLibraryFiles()
        val first = rig(files).store
        first.update {
            it.copy(downloads = DownloadPrefs(askBeforeEach = false, remembered = DownloadRequest(DownloadQuality.Rung(1080, 60), SubtitlePick(true, "en"))))
        }
        pass(500)
        val d = rig(files).store.settings.value.downloads
        assertFalse(d.askBeforeEach)
        assertEquals(DownloadQuality.Rung(1080, 60), d.remembered.quality)
        assertEquals(SubtitlePick(true, "en"), d.remembered.subtitles)
    }

    @Test
    fun theSegmentBehavioursAreAMapOfApiNameToBehaviourNameInTheFile() = runTest {
        val r = rig()
        r.store.update { it.copy(segmentBehaviours = it.segmentBehaviours + (SegmentCategory.SPONSOR to SegmentBehaviour.ASK)) }
        pass(500)
        val text = r.files.files.getValue("settings.json")
        assertTrue(text.contains("\"segmentBehaviours\":{"), text)
        assertTrue(text.contains("\"sponsor\":\"ASK\""), text)
        assertTrue(text.contains("\"poi_highlight\":\"SHOW\""), text)
    }

    @Test
    fun anUnknownCategoryInTheFileIsIgnoredAndAnUnknownBehaviourFallsBack() = runTest {
        val files = MemoryLibraryFiles()
        files.files["settings.json"] =
            """{"version":1,"segmentBehaviours":{"sponsor":"MUTE","not_a_category":"SKIP","selfpromo":"EXPLODE","outro":"OFF"}}"""
        val r = rig(files)
        val b = r.store.settings.value.segmentBehaviours
        assertEquals(SegmentBehaviour.MUTE, b[SegmentCategory.SPONSOR])
        assertEquals(SegmentBehaviour.OFF, b[SegmentCategory.OUTRO])
        assertEquals(SegmentCategory.SELF_PROMO.defaultBehaviour, b[SegmentCategory.SELF_PROMO])
        assertEquals(SegmentCategory.entries.size, b.size)
        assertTrue(r.logs.isEmpty(), "an unknown name is not an error: ${r.logs}")
        assertEquals(emptyList(), files.files.keys.filter { it.contains("broken") })
    }

    @Test
    fun aFileWrittenByANewerVersionOfTheSameFormatKeepsWorking() = runTest {
        val files = MemoryLibraryFiles()
        files.files["settings.json"] = """{"version":1,"autoplay":false,"aSettingFromTheFuture":{"x":[1,2,3]}}"""
        val s = rig(files).store.settings.value
        assertFalse(s.autoplay)
        assertEquals(VideoSettings().copy(autoplay = false), s)
    }

    @Test
    fun aBrokenFileIsSetAsideAndTheDefaultsAreUsed() = runTest {
        val files = MemoryLibraryFiles()
        files.files["settings.json"] = "{\"version\":1,\"autoplay\":"
        val r = rig(files)
        assertEquals(VideoSettings(), r.store.settings.value)
        assertEquals("{\"version\":1,\"autoplay\":", files.files["settings.json.broken-$now"])
        assertNull(files.files["settings.json"])
        assertEquals(1, r.logs.size)
        assertTrue(r.logs[0].contains("settings.json"), r.logs[0])
        r.store.update { it.copy(autoplay = false) }
        pass(500)
        assertFalse(LibraryCodec.decodeSettings(files.files.getValue("settings.json")).autoplay)
    }

    @Test
    fun aSettingsFileOfANewerVersionIsKeptAside() = runTest {
        val files = MemoryLibraryFiles()
        files.files["settings.json"] = """{"version":2,"autoplay":false}"""
        val r = rig(files)
        assertEquals(VideoSettings(), r.store.settings.value)
        assertEquals("""{"version":2,"autoplay":false}""", files.files["settings.json.broken-$now"])
    }

    @Test
    fun flushWritesAtOnce() = runTest {
        val r = rig()
        r.store.update { it.copy(resume = false) }
        r.store.flush()
        assertEquals(listOf("settings.json"), r.files.writes)
        pass(5_000)
        assertEquals(1, r.files.writes.size)
    }

    @Test
    fun aFailedWriteIsLoggedAndRetriedByTheNextFlush() = runTest {
        val r = rig()
        r.files.failWrites = true
        r.store.update { it.copy(resume = false) }
        pass(500)
        assertEquals(1, r.logs.size)
        assertFalse(r.store.settings.value.resume)
        r.files.failWrites = false
        r.store.flush()
        assertFalse(LibraryCodec.decodeSettings(r.files.files.getValue("settings.json")).resume)
    }

    @Test
    fun theWriteGoesThroughTheIoDispatcher() = runTest {
        var dispatched = 0
        val test = StandardTestDispatcher(testScheduler)
        val io = object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) {
                dispatched += 1
                test.dispatch(context, block)
            }
        }
        val r = rig(io = io)
        r.store.update { it.copy(resume = false) }
        pass(500)
        assertEquals(listOf("settings.json"), r.files.writes)
        assertTrue(dispatched > 0)
    }

    @Test
    fun updatesFromSeveralThreadsKeepEveryChange() = runTest {
        val r = rig()
        val threads = (1..4).map { n ->
            Thread {
                repeat(200) { r.store.update { s -> s.copy(homeKiosk = s.homeKiosk) } }
                r.store.update { s -> s.copy(segmentBehaviours = s.segmentBehaviours + (SegmentCategory.entries[n] to SegmentBehaviour.OFF)) }
            }
        }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        for (n in 1..4) assertEquals(SegmentBehaviour.OFF, r.store.settings.value.segmentBehaviours[SegmentCategory.entries[n]])
    }
}
