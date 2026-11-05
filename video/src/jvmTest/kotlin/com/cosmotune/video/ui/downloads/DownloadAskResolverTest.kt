package com.cosmotune.video.ui.downloads

import com.cosmotune.video.downloads.BatchKind
import com.cosmotune.video.downloads.DownloadBatchInfo
import com.cosmotune.video.downloads.DownloadEntry
import com.cosmotune.video.downloads.DownloadOptions
import com.cosmotune.video.downloads.DownloadPrefs
import com.cosmotune.video.downloads.DownloadQuality
import com.cosmotune.video.downloads.DownloadRequest
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.downloads.SubtitleLanguage
import com.cosmotune.video.downloads.SubtitlePick
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.settings.VideoSettings
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.actions.DownloadAsk
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The download sheet's decision (spec 5.16, plan task Y-32 step 1): the sheet, the remembered choice at once, or nothing to do; and the
 * pure helpers the sheet and its host share (what a batch queues, what the sheet starts with, what "Remember my choice" stores).
 * In memory: the entries come from the fake engine.
 */
class DownloadAskResolverTest {
    private val video = FakeVideoSource.videos[0]
    private val batchItems = FakeVideoSource.videos.take(6)
    private val remembered = DownloadRequest(DownloadQuality.Rung(720, 30), SubtitlePick(true, "en"))
    private val asking = DownloadPrefs()
    private val rememberedPrefs = DownloadPrefs(askBeforeEach = false, remembered = remembered)
    private val playlist = DownloadBatchInfo("PLfakeplaylist0000000001", BatchKind.PLAYLIST, "A playlist")
    private val channel = DownloadBatchInfo("UCfakechannel0000000001", BatchKind.CHANNEL_LATEST, "A channel")
    private val anyRequest = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))

    /** The entries of the fake engine after [done] finished, [queued] waits and [failed] failed. */
    private fun entries(done: List<VideoItem> = emptyList(), queued: List<VideoItem> = emptyList(), failed: List<VideoItem> = emptyList()): List<DownloadEntry> {
        val engine = FakeDownloadController()
        done.forEach { engine.enqueue(it, anyRequest) }
        engine.runUntilIdle()
        failed.forEach { item ->
            engine.enqueue(item, anyRequest)
            engine.fail(engine.downloads.value.single { it.item.id == item.id }.id)
        }
        queued.forEach { engine.enqueue(it, anyRequest) }
        return engine.downloads.value
    }

    // ---- The decision ----------------------------------------------------------------------------------------------------------

    @Test fun asksWhileAskBeforeEachIsOn() {
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(asking, DownloadAsk.One(video)))
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(asking, DownloadAsk.Batch(playlist, batchItems, latestPicker = false)))
    }

    @Test fun startsAtOnceWithTheRememberedChoice() {
        assertEquals(DownloadDecision.Start(remembered), DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video)))
        assertEquals(DownloadDecision.Start(remembered), DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.Batch(playlist, batchItems, latestPicker = false)))
    }

    @Test fun anAlreadyDownloadedVideoAsksNothing() {
        val done = entries(done = listOf(video))
        assertSame(DownloadDecision.AlreadyDownloaded, DownloadAskResolver.resolve(asking, DownloadAsk.One(video), done))
        assertSame(DownloadDecision.AlreadyDownloaded, DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video), done))
        assertSame(DownloadDecision.AlreadyDownloaded, DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video), done, forceAsk = true), "Change after it finished")
    }

    @Test fun aVideoAlreadyInTheQueueIsAlreadyInDownloads() {
        val queued = entries(queued = listOf(video))
        assertSame(DownloadDecision.AlreadyInDownloads, DownloadAskResolver.resolve(asking, DownloadAsk.One(video), queued))
        assertSame(DownloadDecision.AlreadyInDownloads, DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video), queued))
    }

    @Test fun aFailedDownloadCanBeAskedForAgain() {
        val failed = entries(failed = listOf(video))
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(asking, DownloadAsk.One(video), failed))
        assertEquals(DownloadDecision.Start(remembered), DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video), failed))
    }

    @Test fun aBatchWithTheLatestPickerAlwaysAsks() {
        val picker = DownloadAsk.Batch(channel, batchItems, latestPicker = true)
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(rememberedPrefs, picker), "the count is asked even with a remembered choice")
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(rememberedPrefs, picker, entries(done = batchItems.take(5))), "the newest may be done, a larger count is not")
    }

    @Test fun aBatchWithNothingLeftToDownloadAsksNothing() {
        val batch = DownloadAsk.Batch(playlist, batchItems, latestPicker = false)
        assertSame(DownloadDecision.AlreadyDownloaded, DownloadAskResolver.resolve(asking, batch, entries(done = batchItems)))
        assertSame(DownloadDecision.AlreadyInDownloads, DownloadAskResolver.resolve(rememberedPrefs, batch, entries(done = batchItems.take(3), queued = batchItems.drop(3))))
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(asking, batch, entries(done = batchItems.take(5))), "one is left")
    }

    @Test fun changeReopensTheSheetOverARememberedChoice() {
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.One(video), forceAsk = true))
        assertSame(DownloadDecision.Ask, DownloadAskResolver.resolve(rememberedPrefs, DownloadAsk.Batch(playlist, batchItems, latestPicker = false), forceAsk = true))
    }

    // ---- What a batch queues ---------------------------------------------------------------------------------------------------

    @Test fun pendingLeavesOutTheDoneAndTheQueuedButNotTheFailed() {
        val list = entries(done = listOf(batchItems[0]), queued = listOf(batchItems[1]), failed = listOf(batchItems[2]))
        assertEquals(batchItems.drop(2), DownloadAskResolver.pending(batchItems, list))
        assertEquals(batchItems, DownloadAskResolver.pending(batchItems, emptyList()))
    }

    @Test fun theLatestPickerTakesTheNewestN() {
        val picker = DownloadAsk.Batch(channel, batchItems, latestPicker = true)
        assertEquals(batchItems.take(5), DownloadAskResolver.batchItems(picker, 5))
        assertEquals(batchItems, DownloadAskResolver.batchItems(picker, 50), "fewer loaded than asked: all of them")
        assertEquals(batchItems, DownloadAskResolver.batchItems(DownloadAsk.Batch(playlist, batchItems, latestPicker = false), 5), "no picker: the whole list")
        assertEquals(listOf(5, 10, 25, 50), LatestCount.CHOICES)
        assertEquals(10, LatestCount.DEFAULT)
    }

    // ---- What "Remember my choice" stores ---------------------------------------------------------------------------------------

    @Test fun rememberStoresTheChoiceAndStopsAsking() {
        val request = DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(true, "hi"))
        val stored = DownloadAskResolver.confirm(VideoSettings(), request, remember = true)
        assertEquals(DownloadPrefs(askBeforeEach = false, remembered = request), stored.downloads)
    }

    @Test fun leavingRememberOffChangesNothing() {
        val before = VideoSettings(downloads = rememberedPrefs)
        assertEquals(before, DownloadAskResolver.confirm(before, DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), remember = false))
        assertEquals(VideoSettings(), DownloadAskResolver.confirm(VideoSettings(), remembered, remember = false), "askBeforeEach stays as it was")
    }

    // ---- What the sheet starts with --------------------------------------------------------------------------------------------

    @Test fun theSheetStartsOnTheRememberedQualityWhenItIsOffered() = runTest {
        val options = FakeDownloadController().options(video)
        assertEquals(DownloadQuality.Rung(720, 60), DownloadAskResolver.initialQuality(options, DownloadQuality.Rung(720, 60)))
        assertEquals(DownloadQuality.Auto, DownloadAskResolver.initialQuality(options, DownloadQuality.Rung(720, 30)), "a rung the video lacks: Auto")
        assertEquals(DownloadQuality.AudioOnly, DownloadAskResolver.initialQuality(options, DownloadQuality.AudioOnly))
        assertEquals(DownloadQuality.Auto, DownloadAskResolver.initialQuality(options, DownloadQuality.Auto))
    }

    @Test fun theSubtitleLanguageIsTheAppsWhenTheVideoHasItElseTheOriginal() = runTest {
        val options = FakeDownloadController().options(video)
        val none = SubtitlePick(false, null)
        assertEquals(SubtitleLanguage("hi", "Hindi", false), DownloadAskResolver.initialLanguage(options, none, appLanguage = "hi"))
        assertEquals(SubtitleLanguage("en", "English", false), DownloadAskResolver.initialLanguage(options, none, appLanguage = "fr"), "the original, a person's track first")
        assertEquals(SubtitleLanguage("hi", "Hindi", false), DownloadAskResolver.initialLanguage(options, SubtitlePick(true, "hi"), appLanguage = "en"), "the remembered one when the video has it")
        assertEquals(SubtitleLanguage("en", "English", false), DownloadAskResolver.initialLanguage(options, SubtitlePick(true, "de"), appLanguage = "en"))
        val empty = options.copy(subtitleLanguages = emptyList())
        assertNull(DownloadAskResolver.initialLanguage(empty, none, appLanguage = "en"), "a video with no subtitles")
    }

    @Test fun aBatchOffersTheAppsLanguageOrEachVideosOwn() = runTest {
        val options = FakeDownloadController().batchOptions(batchItems)
        assertEquals("en", DownloadAskResolver.initialLanguage(options, SubtitlePick(false, null), appLanguage = "en")?.tag)
        assertEquals("", DownloadAskResolver.initialLanguage(options, SubtitlePick(true, ""), appLanguage = "en")?.tag, "each video's original language")
    }

    @Test fun theRequestCarriesTheQualityAndTheSubtitleChoice() {
        val hindi = SubtitleLanguage("hi", "Hindi", false)
        val each = SubtitleLanguage("", "Each original", false)
        assertEquals(DownloadRequest(DownloadQuality.Rung(1080, 60), SubtitlePick(true, "hi")), DownloadAskResolver.request(DownloadQuality.Rung(1080, 60), subtitles = true, language = hindi))
        assertEquals(DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), DownloadAskResolver.request(DownloadQuality.Auto, subtitles = false, language = hindi))
        assertEquals(DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null)), DownloadAskResolver.request(DownloadQuality.Auto, subtitles = true, language = null), "nothing to take")
        assertEquals(SubtitlePick(true, ""), DownloadAskResolver.request(DownloadQuality.AudioOnly, subtitles = true, language = each).subtitles)
    }

    @Test fun autosSizeIsTheSizeOfTheRungItMeans() = runTest {
        val options = FakeDownloadController().options(video)
        assertEquals(options.rungs.first(), DownloadAskResolver.autoOption(options))
        val audioOnly: DownloadOptions = options.copy(autoMeans = "Audio only", rungs = emptyList())
        assertEquals(options.audioOnly, DownloadAskResolver.autoOption(audioOnly), "no rung the device plays: audio only")
    }

    @Test fun theStartToastNamesARungByItsLabel() {
        assertEquals("720p", DownloadAskResolver.rungLabel(DownloadQuality.Rung(720, 30)))
        assertEquals("1080p60", DownloadAskResolver.rungLabel(DownloadQuality.Rung(1080, 60)))
        assertNull(DownloadAskResolver.rungLabel(DownloadQuality.Auto))
        assertNull(DownloadAskResolver.rungLabel(DownloadQuality.AudioOnly))
    }
}
