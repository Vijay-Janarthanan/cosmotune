package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The Downloads page's plan (plan task Y-33, step 1; spec 5.10): which rows the page shows for the filter and the sort, a batch
 * as a header with its items under it (collapsible), loose items after, the batch counters ("12 of 24"), the storage line, the
 * done line's parts, and what a tap and the item's sheet offer in each state. Entries come from the fake engine, so they have the
 * engine's shape; their times, kinds and states are then set here.
 */
class DownloadsPlanTest {
    private val video = DownloadRequest(DownloadQuality.Auto, SubtitlePick(false, null))
    private val withSubs = DownloadRequest(DownloadQuality.Rung(720, 30), SubtitlePick(true, "en"))
    private val audio = DownloadRequest(DownloadQuality.AudioOnly, SubtitlePick(false, null))
    private val playlist = DownloadBatchInfo("pl-1", BatchKind.PLAYLIST, "Road trip")
    private val feed = DownloadBatchInfo("feed-1", BatchKind.FEED, "Your feed")

    private val videos = FakeVideoSource.videos.filterNot { it.isLive || it.isUpcoming }

    /** A fake engine whose clock the test moves, so each entry gets its own `createdMs`. */
    private class Engine {
        var now = FakeVideoSource.NOW_MS
        val downloads = FakeDownloadController(clock = { now })

        fun add(item: VideoItem, request: DownloadRequest, atMs: Long) {
            now = atMs
            downloads.enqueue(item, request)
        }

        fun batch(info: DownloadBatchInfo, items: List<VideoItem>, request: DownloadRequest, atMs: Long) {
            now = atMs
            downloads.enqueueBatch(info, items, request)
        }

        val entries get() = downloads.downloads.value
        val batches get() = downloads.batches.value

        fun idOf(videoId: String) = entries.single { it.item.id == videoId }.id
    }

    private fun rows(engine: Engine, filter: DownloadsFilter = DownloadsFilter.ALL, sort: DownloadsSort = DownloadsSort.NEWEST, collapsed: Set<String> = emptySet()) =
        DownloadsPlan.build(engine.entries, engine.batches, filter, sort, collapsed)

    private fun List<DownloadsRow>.shape(): List<String> = map { row ->
        when (row) {
            is DownloadsRow.Batch -> "batch ${row.info.id} ${row.done}/${row.total}"
            is DownloadsRow.Item -> (if (row.batchId != null) "  " else "") + row.entry.item.id
        }
    }

    // ---- Empty ---------------------------------------------------------------------------------------------------------

    @Test fun nothingDownloadedGivesNoRows() {
        assertEquals(emptyList(), DownloadsPlan.build(emptyList(), emptyList(), DownloadsFilter.ALL, DownloadsSort.NEWEST))
    }

    @Test fun aFilterThatMatchesNothingGivesNoRowsAndNoEmptyBatchHeader() {
        val engine = Engine()
        engine.batch(playlist, videos.take(2), video, 1_000)
        engine.add(videos[2], video, 2_000)
        assertEquals(emptyList(), rows(engine, filter = DownloadsFilter.AUDIO))
        assertEquals(emptyList(), rows(engine, filter = DownloadsFilter.SHORTS))
    }

    // ---- Grouping ------------------------------------------------------------------------------------------------------

    @Test fun aBatchIsAHeaderWithItsItemsUnderItAndLooseItemsComeAfter() {
        val engine = Engine()
        engine.add(videos[0], video, 1_000)
        engine.batch(playlist, videos.subList(1, 4), video, 2_000)
        engine.add(videos[4], video, 3_000)
        assertEquals(
            listOf(
                "batch pl-1 0/3",
                "  ${videos[1].id}", "  ${videos[2].id}", "  ${videos[3].id}",
                videos[4].id, videos[0].id,
            ),
            rows(engine).shape(),
        )
    }

    @Test fun aCollapsedBatchKeepsItsHeaderAndHidesItsItems() {
        val engine = Engine()
        engine.batch(playlist, videos.subList(0, 3), video, 1_000)
        engine.batch(feed, videos.subList(3, 5), video, 2_000)
        engine.add(videos[5], video, 3_000)
        val shown = rows(engine, collapsed = setOf("pl-1"))
        assertEquals(listOf("batch feed-1 0/2", "  ${videos[3].id}", "  ${videos[4].id}", "batch pl-1 0/3", videos[5].id), shown.shape())
        val header = shown.filterIsInstance<DownloadsRow.Batch>().single { it.info.id == "pl-1" }
        assertTrue(header.collapsed)
        assertFalse(shown.filterIsInstance<DownloadsRow.Batch>().single { it.info.id == "feed-1" }.collapsed)
    }

    @Test fun everyRowHasItsOwnStableKey() {
        val engine = Engine()
        engine.batch(playlist, videos.subList(0, 3), video, 1_000)
        engine.add(videos[3], video, 2_000)
        val keys = rows(engine).map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertEquals("batch:pl-1", keys.first())
        assertTrue(keys.drop(1).all { it.startsWith("item:download-") })
        // the keys do not move when the state does
        engine.downloads.runUntilIdle()
        assertEquals(keys.toSet(), rows(engine).map { it.key }.toSet())
    }

    @Test fun anEntryWhoseBatchTheEngineNoLongerListsStillGroupsUnderItsOwnBatch() {
        val engine = Engine()
        engine.batch(playlist, videos.subList(0, 2), video, 1_000)
        val shown = DownloadsPlan.build(engine.entries, emptyList(), DownloadsFilter.ALL, DownloadsSort.NEWEST)
        assertEquals(listOf("batch pl-1 0/2", "  ${videos[0].id}", "  ${videos[1].id}"), shown.shape())
        assertEquals("Road trip", (shown.first() as DownloadsRow.Batch).info.title)
    }

    // ---- Batch counters ------------------------------------------------------------------------------------------------

    @Test fun theBatchCounterIsDoneOfTotalForTheWholeBatchWhateverTheFilter() {
        val engine = Engine()
        val items = videos.take(4) + FakeVideoSource.shorts.take(2)
        engine.batch(playlist, items, video, 1_000)
        repeat(7) { engine.downloads.advance() } // the first video: start, four steps, join, done
        val header = rows(engine).first() as DownloadsRow.Batch
        assertEquals(1, header.done)
        assertEquals(6, header.total)
        assertEquals(5, header.unfinished)
        val shortsOnly = rows(engine, filter = DownloadsFilter.SHORTS)
        assertEquals(listOf("batch pl-1 1/6", "  ${FakeVideoSource.shorts[0].id}", "  ${FakeVideoSource.shorts[1].id}"), shortsOnly.shape())
    }

    @Test fun theBatchProgressCountsWhatIsDoneAndThePartOfWhatRuns() {
        val engine = Engine()
        engine.batch(playlist, videos.take(4), video, 1_000)
        repeat(7) { engine.downloads.advance() } // one done
        repeat(3) { engine.downloads.advance() } // the second started, then at 0.25 and 0.5
        val header = rows(engine).first() as DownloadsRow.Batch
        assertEquals((1f + 0.5f) / 4f, header.progress, 0.0001f)
        engine.downloads.runUntilIdle()
        val finished = rows(engine).first() as DownloadsRow.Batch
        assertEquals(1f, finished.progress)
        assertEquals(4, finished.done)
        assertEquals(0, finished.unfinished)
    }

    @Test fun aBatchIsPausedWhenTheEngineSaysSo() {
        val engine = Engine()
        engine.batch(playlist, videos.take(3), video, 1_000)
        engine.downloads.pauseBatch("pl-1")
        assertTrue((rows(engine).first() as DownloadsRow.Batch).paused)
        engine.downloads.resumeBatch("pl-1")
        assertFalse((rows(engine).first() as DownloadsRow.Batch).paused)
    }

    @Test fun withoutTheEnginesWordABatchIsPausedWhenEveryUnfinishedEntryIsPausedByTheUser() {
        val engine = Engine()
        engine.batch(playlist, videos.take(2), video, 1_000)
        engine.downloads.pause(engine.idOf(videos[0].id))
        assertFalse((DownloadsPlan.build(engine.entries, emptyList(), DownloadsFilter.ALL, DownloadsSort.NEWEST).first() as DownloadsRow.Batch).paused)
        engine.downloads.pause(engine.idOf(videos[1].id))
        assertTrue((DownloadsPlan.build(engine.entries, emptyList(), DownloadsFilter.ALL, DownloadsSort.NEWEST).first() as DownloadsRow.Batch).paused)
    }

    // ---- Filters -------------------------------------------------------------------------------------------------------

    @Test fun theFiltersFollowTheKindOfEachDownload() {
        val engine = Engine()
        engine.add(videos[0], video, 1_000)
        engine.add(FakeVideoSource.shorts[0], video, 2_000)
        engine.add(videos[1], audio, 3_000)
        assertEquals(DownloadKind.SHORT, engine.entries[1].kind)
        assertEquals(DownloadKind.AUDIO, engine.entries[2].kind)
        assertEquals(listOf(videos[1].id, FakeVideoSource.shorts[0].id, videos[0].id), rows(engine).shape())
        assertEquals(listOf(videos[0].id), rows(engine, filter = DownloadsFilter.VIDEOS).shape())
        assertEquals(listOf(FakeVideoSource.shorts[0].id), rows(engine, filter = DownloadsFilter.SHORTS).shape())
        assertEquals(listOf(videos[1].id), rows(engine, filter = DownloadsFilter.AUDIO).shape())
    }

    @Test fun matchesSaysWhichKindsAFilterKeeps() {
        val engine = Engine()
        engine.add(videos[0], video, 1_000)
        val entry = engine.entries.single()
        assertTrue(DownloadsPlan.matches(entry, DownloadsFilter.ALL))
        assertTrue(DownloadsPlan.matches(entry, DownloadsFilter.VIDEOS))
        assertFalse(DownloadsPlan.matches(entry, DownloadsFilter.SHORTS))
        assertFalse(DownloadsPlan.matches(entry, DownloadsFilter.AUDIO))
        for (kind in DownloadKind.entries) assertTrue(DownloadsPlan.matches(entry.copy(kind = kind), DownloadsFilter.ALL))
    }

    // ---- Sorts ---------------------------------------------------------------------------------------------------------

    private fun sortEngine(): Engine {
        val engine = Engine()
        engine.add(videos[0], video, 3_000) // "Building a tiny synth from scratch", 754 s
        engine.add(videos[1], video, 1_000) // "Ten minutes of rain on a tin roof", 600 s
        engine.add(videos[3], video, 2_000) // "Night drive through the city, no talking", 3605 s
        return engine
    }

    @Test fun newestFirstAndOldestFirstFollowWhenEachWasAdded() {
        val engine = sortEngine()
        assertEquals(listOf(videos[0].id, videos[3].id, videos[1].id), rows(engine, sort = DownloadsSort.NEWEST).shape())
        assertEquals(listOf(videos[1].id, videos[3].id, videos[0].id), rows(engine, sort = DownloadsSort.OLDEST).shape())
    }

    @Test fun twoAddedAtTheSameMomentKeepTheOrderTheyWereAddedSoABatchKeepsItsOwnOrder() {
        val engine = Engine()
        engine.add(videos[0], video, 1_000)
        engine.add(videos[1], video, 1_000)
        assertEquals(listOf(videos[0].id, videos[1].id), rows(engine, sort = DownloadsSort.NEWEST).shape())
        assertEquals(listOf(videos[0].id, videos[1].id), rows(engine, sort = DownloadsSort.OLDEST).shape())
        val batch = Engine()
        batch.batch(playlist, listOf(videos[3], videos[0], videos[1]), video, 1_000)
        assertEquals(listOf("batch pl-1 0/3", "  ${videos[3].id}", "  ${videos[0].id}", "  ${videos[1].id}"), rows(batch, sort = DownloadsSort.NEWEST).shape())
    }

    @Test fun titleSortsByTheTitleIgnoringCase() {
        val engine = sortEngine()
        engine.add(videos[4].copy(title = "apple orchard"), video, 4_000)
        assertEquals(listOf(videos[4].id, videos[0].id, videos[3].id, videos[1].id), rows(engine, sort = DownloadsSort.TITLE).shape())
    }

    @Test fun sizeSortsTheLargestFirstWithTheFileSizeOnceDoneAndTheEstimateBefore() {
        val engine = sortEngine()
        assertEquals(listOf(videos[3].id, videos[0].id, videos[1].id), rows(engine, sort = DownloadsSort.SIZE).shape())
        // a finished file's real size wins over the estimate
        val first = engine.entries.first { it.item.id == videos[1].id }
        val big = first.copy(state = DownloadState.Done("/x/a.mp4", 50L * 1_000_000_000))
        val list = engine.entries.map { if (it.id == first.id) big else it }
        assertEquals(50L * 1_000_000_000, DownloadsPlan.sizeOf(big))
        assertEquals(first.choice.estimatedBytes, DownloadsPlan.sizeOf(first))
        assertEquals(
            listOf(videos[1].id, videos[3].id, videos[0].id),
            DownloadsPlan.build(list, emptyList(), DownloadsFilter.ALL, DownloadsSort.SIZE).shape(),
        )
    }

    @Test fun theSortAppliesInsideABatchAndToTheBatchesThemselves() {
        val engine = Engine()
        engine.batch(playlist, listOf(videos[1], videos[0]), video, 1_000)
        engine.batch(feed, listOf(videos[3]), video, 2_000)
        assertEquals(
            listOf("batch feed-1 0/1", "  ${videos[3].id}", "batch pl-1 0/2", "  ${videos[1].id}", "  ${videos[0].id}"),
            rows(engine, sort = DownloadsSort.NEWEST).shape(),
        )
        assertEquals(
            listOf("batch pl-1 0/2", "  ${videos[1].id}", "  ${videos[0].id}", "batch feed-1 0/1", "  ${videos[3].id}"),
            rows(engine, sort = DownloadsSort.OLDEST).shape(),
        )
        // "Road trip" before "Your feed"; inside, "Building..." before "Ten minutes..."
        assertEquals(
            listOf("batch pl-1 0/2", "  ${videos[0].id}", "  ${videos[1].id}", "batch feed-1 0/1", "  ${videos[3].id}"),
            rows(engine, sort = DownloadsSort.TITLE).shape(),
        )
        // the feed's one long video outweighs the playlist's two short ones
        assertEquals("batch feed-1 0/1", rows(engine, sort = DownloadsSort.SIZE).shape().first())
    }

    // ---- Texts ---------------------------------------------------------------------------------------------------------

    @Test fun theStorageLineWritesBothSizesAsTheSheetDoes() {
        assertEquals("1.4 GB" to "21 GB", DownloadsPlan.storage(1_400_000_000L to 21_000_000_000L))
        assertEquals("0 KB" to "312 MB", DownloadsPlan.storage(0L to 312_000_000L))
        val fake = FakeDownloadController()
        assertEquals(DownloadPlanner.sizeText(0) to DownloadPlanner.sizeText(21L * 1024 * 1024 * 1024), DownloadsPlan.storage(fake.storage()))
    }

    @Test fun thePercentIsWholeAndNeverPastTheEnds() {
        assertEquals(0, DownloadsPlan.percent(0f))
        assertEquals(42, DownloadsPlan.percent(0.429f))
        assertEquals(99, DownloadsPlan.percent(0.999f))
        assertEquals(100, DownloadsPlan.percent(1f))
        assertEquals(0, DownloadsPlan.percent(-0.5f))
        assertEquals(100, DownloadsPlan.percent(3f))
    }

    @Test fun theDoneLineHasTheQualityTheSizeAndTheSubtitles() {
        val engine = Engine()
        engine.add(videos[0], withSubs, 1_000)
        engine.add(videos[1], video, 2_000)
        engine.add(videos[3], audio, 3_000)
        assertNull(DownloadsPlan.doneParts(engine.entries[0]), "nothing to say until it is done")
        engine.downloads.runUntilIdle()
        val (subs, plain, sound) = engine.entries
        val withSubtitles = DownloadsPlan.doneParts(subs)!!
        assertEquals("720p", withSubtitles.quality)
        assertEquals(DownloadPlanner.sizeText((subs.state as DownloadState.Done).bytes), withSubtitles.size)
        assertEquals("English", withSubtitles.subtitles)
        assertFalse(withSubtitles.missedSubtitles)
        val noSubtitles = DownloadsPlan.doneParts(plain)!!
        assertNull(noSubtitles.subtitles)
        assertFalse(noSubtitles.missedSubtitles, "none were asked for")
        assertNull(DownloadsPlan.doneParts(sound)!!.quality, "audio only: the page says so in its own words")
        val missed = DownloadsPlan.doneParts(subs.copy(subtitleGot = null, subtitleFiles = emptyList()))!!
        assertNull(missed.subtitles)
        assertTrue(missed.missedSubtitles, "asked for, none found")
    }

    // ---- Taps and the item sheet ---------------------------------------------------------------------------------------

    /** An entry of the first video in [state]; a 720p H.264 request, so its file (or the kept pair) is MP4. */
    private fun entryIn(state: DownloadState, kind: DownloadKind = DownloadKind.VIDEO): DownloadEntry {
        val engine = Engine()
        engine.add(videos[0], if (kind == DownloadKind.AUDIO) audio else withSubs, 1_000)
        return engine.entries.single().copy(state = state, kind = kind)
    }

    private val done = DownloadState.Done("/m/Cosmotune/Building a tiny synth [fakevideo01].mp4", 312_000_000)

    @Test fun aTapPlaysAFinishedDownloadRetriesAFailedOneAndOtherwiseOpensTheSheet() {
        assertEquals(DownloadItemAction.PLAY, DownloadsPlan.tapAction(entryIn(done)))
        assertEquals(DownloadItemAction.RETRY, DownloadsPlan.tapAction(entryIn(DownloadState.Failed("Network error"))))
        for (state in listOf(DownloadState.Waiting, DownloadState.WaitingForWifi, DownloadState.Paused(PauseReason.USER), DownloadState.Running(0.3f), DownloadState.Joining)) {
            assertNull(DownloadsPlan.tapAction(entryIn(state)), "$state opens the sheet")
        }
    }

    @Test fun theSheetOffersWhatTheStateAllows() {
        assertEquals(listOf(DownloadItemAction.PLAY, DownloadItemAction.SAVE_COPY, DownloadItemAction.DELETE), DownloadsPlan.actionsFor(entryIn(done), canSaveCopy = true))
        assertEquals(listOf(DownloadItemAction.PLAY, DownloadItemAction.DELETE), DownloadsPlan.actionsFor(entryIn(done), canSaveCopy = false), "the desktop has no Movies copy")
        assertEquals(listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.Running(0.5f)), true))
        assertEquals(listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.Waiting), true))
        assertEquals(listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.WaitingForWifi), true))
        assertEquals(listOf(DownloadItemAction.PAUSE, DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.Paused(PauseReason.BATTERY_LOW)), true), "a battery hold is not the user's pause")
        assertEquals(listOf(DownloadItemAction.RESUME, DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.Paused(PauseReason.USER)), true))
        assertEquals(listOf(DownloadItemAction.CANCEL), DownloadsPlan.actionsFor(entryIn(DownloadState.Joining), true))
        assertEquals(listOf(DownloadItemAction.RETRY, DownloadItemAction.DELETE), DownloadsPlan.actionsFor(entryIn(DownloadState.Failed("x")), true))
    }

    @Test fun onlyOneFinishedPlayableFileCanBeCopiedToMovies() {
        assertTrue(DownloadsPlan.canSaveCopy(entryIn(done)))
        assertFalse(DownloadsPlan.canSaveCopy(entryIn(DownloadState.Running(0.5f))))
        assertFalse(DownloadsPlan.canSaveCopy(entryIn(DownloadState.Done("/m/a [fakevideo01].m4a", 9_000_000), DownloadKind.AUDIO)), "audio only")
        val pair = entryIn(DownloadState.Done("/m/Cosmotune/Building a tiny synth [fakevideo01].video.mp4", 300_000_000))
        assertTrue(pair.localFiles()?.muxedPath == null, "the pair kept after a failed join")
        assertFalse(DownloadsPlan.canSaveCopy(pair))
        assertEquals(listOf(DownloadItemAction.PLAY, DownloadItemAction.DELETE), DownloadsPlan.actionsFor(pair, canSaveCopy = true))
    }

    @Test fun aDeleteRemovesAFinishedOrFailedDownloadAndCancelsTheRest() {
        assertTrue(DownloadsPlan.removes(entryIn(done)))
        assertTrue(DownloadsPlan.removes(entryIn(DownloadState.Failed("x"))))
        assertFalse(DownloadsPlan.removes(entryIn(DownloadState.Running(0.5f))))
        assertFalse(DownloadsPlan.removes(entryIn(DownloadState.Paused(PauseReason.USER))))
    }

    // ---- Cancelling a batch --------------------------------------------------------------------------------------------

    @Test fun cancellingABatchWithNothingFinishedCancelsTheWholeBatch() {
        val engine = Engine()
        engine.batch(playlist, videos.take(3), video, 1_000)
        assertNull(DownloadsPlan.batchCancelIds(engine.entries, "pl-1"), "null: the engine's cancelBatch")
    }

    @Test fun cancellingABatchKeepsWhatIsAlreadyDownloaded() {
        val engine = Engine()
        engine.batch(playlist, videos.take(3), video, 1_000)
        repeat(7) { engine.downloads.advance() } // the first is done
        engine.downloads.fail(engine.idOf(videos[2].id))
        val ids = DownloadsPlan.batchCancelIds(engine.entries, "pl-1")
        assertEquals(listOf(engine.idOf(videos[1].id), engine.idOf(videos[2].id)), ids, "the unfinished and the failed go, the finished one stays")
        ids!!.forEach { engine.downloads.cancel(it) }
        assertEquals(listOf("batch pl-1 1/1", "  ${videos[0].id}"), rows(engine).shape())
    }
}
