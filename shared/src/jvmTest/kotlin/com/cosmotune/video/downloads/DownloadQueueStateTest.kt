package com.cosmotune.video.downloads

import com.cosmotune.video.model.VideoCodec
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.source.FakeVideoSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The queue of plan task Y-24, step 3 (spec 5.10, 5.16): one at a time, batches, holds, pause and resume, cancel, the snapshot. */
class DownloadQueueStateTest {
    private val caps = DeviceCaps(1080, 60, setOf(VideoCodec.H264, VideoCodec.VP9))
    private val request = DownloadRequest(DownloadQuality.Rung(720, 30), SubtitlePick(true, "en"))
    private fun item(id: String): VideoItem = FakeVideoSource.sampleItem(id)!!
    private fun add(id: String) = QueueEvent.Add(item(id), DownloadPlanner.plannedChoice(item(id), request, caps))
    private fun single(id: String, now: Long = 1_000) = QueueEvent.Enqueue(listOf(add(id)), request, null, now)
    private val road = DownloadBatchInfo("pl:road", BatchKind.PLAYLIST, "Thirty for the road")
    private fun batch(info: DownloadBatchInfo, vararg ids: String) = QueueEvent.Enqueue(ids.map { add(it) }, request, info, 2_000)

    private fun DownloadQueueState.then(event: QueueEvent): DownloadQueueState = reduce(event).state
    private fun DownloadQueueState.idOf(videoId: String) = entries.first { it.item.id == videoId }.id
    private fun DownloadQueueState.stateOf(videoId: String) = entries.first { it.item.id == videoId }.state
    private fun DownloadQueueState.start(videoId: String) = then(QueueEvent.Start(idOf(videoId), entries.first { it.item.id == videoId }.choice))
    private fun DownloadQueueState.finish(videoId: String) = then(QueueEvent.Finish(idOf(videoId), "/out/$videoId.mp4", 10, emptyList(), null))

    private val three = DownloadQueueState()
        .then(single("fakevideo01"))
        .then(single("fakevideo02"))
        .then(batch(road, "fakeplvideo01", "fakeplvideo02", "fakeplvideo03"))

    @Test fun entriesWaitInOrderAndOneRunsAtATime() {
        assertEquals(listOf("fakevideo01", "fakevideo02", "fakeplvideo01", "fakeplvideo02", "fakeplvideo03"), three.entries.map { it.item.id })
        assertEquals(listOf("download-1", "download-2", "download-3", "download-4", "download-5"), three.entries.map { it.id })
        assertTrue(three.entries.all { it.state == DownloadState.Waiting })
        assertEquals("fakevideo01", three.nextToStart()?.item?.id)

        val running = three.start("fakevideo01")
        assertEquals(DownloadState.Running(0f), running.stateOf("fakevideo01"))
        assertNull(running.nextToStart(), "the next waits")
        assertEquals("fakevideo01", running.active?.item?.id)

        val progressed = running.then(QueueEvent.Progress(running.idOf("fakevideo01"), 0.4f))
        assertEquals(DownloadState.Running(0.4f), progressed.stateOf("fakevideo01"))
        val joining = progressed.then(QueueEvent.Join(running.idOf("fakevideo01")))
        assertEquals(DownloadState.Joining, joining.stateOf("fakevideo01"))
        assertNull(joining.nextToStart(), "joining still holds the queue")
        val done = joining.finish("fakevideo01")
        assertEquals(DownloadState.Done("/out/fakevideo01.mp4", 10), done.stateOf("fakevideo01"))
        assertEquals("fakevideo02", done.nextToStart()?.item?.id)
        // first in, first out across batches
        assertEquals("fakeplvideo01", done.start("fakevideo02").finish("fakevideo02").nextToStart()?.item?.id)
    }

    @Test fun startTakesTheResolvedChoiceAndIsRefusedWhenTheEntryIsNoLongerWaiting() {
        val resolved = three.entries.first().choice.copy(label = "720p resolved")
        val started = three.then(QueueEvent.Start("download-1", resolved))
        assertEquals("720p resolved", started.entries.first().choice.label)
        val paused = three.then(QueueEvent.Pause("download-1"))
        assertEquals(paused, paused.then(QueueEvent.Start("download-1", resolved)), "a paused entry does not start")
    }

    @Test fun aBatchSkipsItemsAlreadyDownloadedOrQueuedAndLiveOnes() {
        val state = three.start("fakevideo01").finish("fakevideo01")
        val mixed = DownloadBatchInfo("feed", BatchKind.FEED, "Subscriptions")
        val after = state.then(batch(mixed, "fakevideo01", "fakevideo02", "fakevideo03", "fakevideo03", "fakevideo11", "fakevideo12"))
        assertEquals(1, after.entries.count { it.batch?.id == "feed" }, "only fakevideo03 is new: 01 is done, 02 is queued, 11 is live and 12 upcoming")
        assertEquals("fakevideo03", after.entries.last().item.id)
        assertEquals(BatchState(mixed, total = 1, done = 0, paused = false), after.batchStates().first { it.info.id == "feed" })
        // a batch with nothing new adds nothing
        val nothing = after.then(batch(DownloadBatchInfo("again", BatchKind.WATCH_LATER, "Watch later"), "fakevideo01", "fakevideo02"))
        assertEquals(after.entries, nothing.entries)
        assertTrue(nothing.batchStates().none { it.info.id == "again" })
        // a single enqueue of a queued video is skipped too
        assertEquals(after.entries, after.then(single("fakevideo02")).entries)
    }

    @Test fun aFailedVideoEnqueuedAgainWaitsAgain() {
        val failed = three.start("fakevideo01").then(QueueEvent.Fail("download-1", "Network error"))
        assertEquals(DownloadState.Failed("Network error"), failed.stateOf("fakevideo01"))
        assertEquals("fakevideo02", failed.nextToStart()?.item?.id, "a failed entry does not hold the queue")
        val again = failed.then(single("fakevideo01"))
        assertEquals(5, again.entries.size)
        assertEquals(DownloadState.Waiting, again.stateOf("fakevideo01"))
        assertEquals(DownloadState.Waiting, failed.then(QueueEvent.Retry("download-1")).stateOf("fakevideo01"))
    }

    @Test fun onlyOnWifiHoldsTheQueueOnMobileData() {
        val running = three.start("fakevideo01")
        val metered = running.reduce(QueueEvent.Conditions(QueueConditions(wifiOnly = true, metered = true)))
        assertEquals(listOf("download-1"), metered.stop, "the running download stops; its bytes stay")
        assertTrue(metered.state.entries.all { it.state == DownloadState.WaitingForWifi })
        assertNull(metered.state.nextToStart())
        // new downloads wait for Wi-Fi too
        assertEquals(DownloadState.WaitingForWifi, metered.state.then(single("fakevideo04")).stateOf("fakevideo04"))
        // Wi-Fi only switched off, or Wi-Fi back: waiting again
        val back = metered.state.then(QueueEvent.Conditions(QueueConditions(wifiOnly = true, metered = false)))
        assertTrue(back.entries.all { it.state == DownloadState.Waiting })
        assertEquals("fakevideo01", back.nextToStart()?.item?.id)
        assertTrue(three.then(QueueEvent.Conditions(QueueConditions(wifiOnly = false, metered = true))).entries.all { it.state == DownloadState.Waiting })
    }

    @Test fun aLowBatteryPausesTheQueueUntilItRecovers() {
        val low = QueueConditions(pauseOnLowBattery = true, batteryLow = true)
        val held = three.start("fakevideo01").reduce(QueueEvent.Conditions(low))
        assertEquals(listOf("download-1"), held.stop)
        assertTrue(held.state.entries.all { it.state == DownloadState.Paused(PauseReason.BATTERY_LOW) })
        assertNull(held.state.nextToStart())
        // Wi-Fi comes first when both hold
        val both = held.state.then(QueueEvent.Conditions(low.copy(wifiOnly = true, metered = true)))
        assertTrue(both.entries.all { it.state == DownloadState.WaitingForWifi })
        val okay = both.then(QueueEvent.Conditions(QueueConditions(pauseOnLowBattery = true, batteryLow = false)))
        assertTrue(okay.entries.all { it.state == DownloadState.Waiting })
        assertTrue(three.then(QueueEvent.Conditions(QueueConditions(pauseOnLowBattery = false, batteryLow = true))).entries.all { it.state == DownloadState.Waiting })
    }

    @Test fun theBatteryIsLowBelowFifteenPercentWhenNotCharging() {
        assertTrue(QueueConditions.isBatteryLow(14, charging = false))
        assertFalse(QueueConditions.isBatteryLow(15, charging = false))
        assertFalse(QueueConditions.isBatteryLow(5, charging = true))
        assertFalse(QueueConditions.isBatteryLow(-1, charging = false), "an unknown level never holds")
    }

    @Test fun pauseAndResumeOneItem() {
        val step = three.start("fakevideo01").reduce(QueueEvent.Pause("download-1"))
        assertEquals(listOf("download-1"), step.stop)
        assertEquals(DownloadState.Paused(PauseReason.USER), step.state.stateOf("fakevideo01"))
        assertEquals("fakevideo02", step.state.nextToStart()?.item?.id, "the next one runs meanwhile")
        // a user pause survives the holds coming and going
        val heldAndBack = step.state
            .then(QueueEvent.Conditions(QueueConditions(wifiOnly = true, metered = true)))
            .then(QueueEvent.Conditions(QueueConditions()))
        assertEquals(DownloadState.Paused(PauseReason.USER), heldAndBack.stateOf("fakevideo01"))
        val resumed = step.state.then(QueueEvent.Resume("download-1"))
        assertEquals(DownloadState.Waiting, resumed.stateOf("fakevideo01"))
        assertEquals("fakevideo01", resumed.nextToStart()?.item?.id, "it keeps its place")
        // joining is not interrupted
        val joining = three.start("fakevideo01").then(QueueEvent.Join("download-1"))
        assertEquals(DownloadState.Joining, joining.then(QueueEvent.Pause("download-1")).stateOf("fakevideo01"))
    }

    @Test fun pauseAndResumeABatch() {
        val state = three.start("fakevideo01").finish("fakevideo01").start("fakevideo02").finish("fakevideo02")
            .start("fakeplvideo01").finish("fakeplvideo01").start("fakeplvideo02")
        val step = state.reduce(QueueEvent.PauseBatch("pl:road"))
        assertEquals(listOf(state.idOf("fakeplvideo02")), step.stop)
        val paused = step.state
        assertEquals(DownloadState.Done("/out/fakeplvideo01.mp4", 10), paused.stateOf("fakeplvideo01"), "finished items stay finished")
        assertEquals(DownloadState.Paused(PauseReason.USER), paused.stateOf("fakeplvideo02"))
        assertEquals(DownloadState.Paused(PauseReason.USER), paused.stateOf("fakeplvideo03"))
        assertEquals(BatchState(road, total = 3, done = 1, paused = true), paused.batchStates().single())
        assertNull(paused.nextToStart())
        val resumed = paused.then(QueueEvent.ResumeBatch("pl:road"))
        assertEquals(BatchState(road, total = 3, done = 1, paused = false), resumed.batchStates().single())
        assertEquals("fakeplvideo02", resumed.nextToStart()?.item?.id)
        // resuming one item of a paused batch lets it run and the batch is no longer paused
        val one = paused.then(QueueEvent.Resume(paused.idOf("fakeplvideo03")))
        assertEquals("fakeplvideo03", one.nextToStart()?.item?.id)
        assertFalse(one.batchStates().single().paused)
    }

    @Test fun cancelRemovesTheEntryAndListsEveryFileItHas() {
        val running = three.start("fakevideo01")
        val step = running.reduce(QueueEvent.Cancel("download-1"))
        assertEquals(listOf("download-1"), step.stop)
        assertEquals(listOf("download-1"), step.removed.map { it.id })
        assertTrue(step.state.entries.none { it.id == "download-1" })
        assertEquals(
            setOf("/cache/download-1.video.part", "/cache/download-1.audio.part", "/cache/download-1.joined.part"),
            DownloadQueueState.filesOf(step.removed.single(), "/cache").toSet(),
        )

        val base = "Building a tiny synth from scratch [fakevideo01]"
        val done = running.then(QueueEvent.Finish("download-1", "/movies/Cosmotune/$base.mp4", 99, listOf("/movies/Cosmotune/$base.en.vtt"), "English"))
        val removed = done.reduce(QueueEvent.Remove("download-1"))
        assertTrue(removed.stop.isEmpty())
        assertEquals(
            setOf(
                "/cache/download-1.video.part", "/cache/download-1.audio.part", "/cache/download-1.joined.part",
                "/movies/Cosmotune/$base.mp4", "/movies/Cosmotune/$base.en.vtt", "/movies/Cosmotune/$base.cosmotune.json",
            ),
            DownloadQueueState.filesOf(removed.removed.single(), "/cache").toSet(),
        )
        // a pair kept after a failed join: both halves go
        val pair = running.then(QueueEvent.Finish("download-1", "C:\\Videos\\Cosmotune\\$base.video.mp4", 99, emptyList(), null))
        val files = DownloadQueueState.filesOf(pair.reduce(QueueEvent.Cancel("download-1")).removed.single(), "C:\\cache").toSet()
        assertTrue("C:\\Videos\\Cosmotune\\$base.video.mp4" in files && "C:\\Videos\\Cosmotune\\$base.audio.m4a" in files && "C:\\Videos\\Cosmotune\\$base.cosmotune.json" in files, "$files")
    }

    @Test fun cancelABatchRemovesEveryItemAndTheBatch() {
        val running = three.start("fakevideo01").finish("fakevideo01").start("fakevideo02").finish("fakevideo02").start("fakeplvideo01")
        val step = running.reduce(QueueEvent.CancelBatch("pl:road"))
        assertEquals(listOf(running.idOf("fakeplvideo01")), step.stop)
        assertEquals(setOf("fakeplvideo01", "fakeplvideo02", "fakeplvideo03"), step.removed.map { it.item.id }.toSet())
        assertTrue(step.state.batchStates().isEmpty())
        assertEquals(2, step.state.entries.size)
    }

    @Test fun theBatchCountsWhatIsDone() {
        val state = three.start("fakevideo01").finish("fakevideo01").start("fakevideo02").finish("fakevideo02")
            .start("fakeplvideo01").finish("fakeplvideo01").start("fakeplvideo02").finish("fakeplvideo02")
        assertEquals(BatchState(road, total = 3, done = 2, paused = false), state.batchStates().single())
        assertEquals(40L, state.usedBytes())
        // removing a finished item shrinks the batch; removing the last item removes the batch
        val smaller = state.then(QueueEvent.Remove(state.idOf("fakeplvideo01")))
        assertEquals(BatchState(road, total = 2, done = 1, paused = false), smaller.batchStates().single())
        val gone = smaller.then(QueueEvent.Remove(state.idOf("fakeplvideo02"))).then(QueueEvent.Remove(state.idOf("fakeplvideo03")))
        assertTrue(gone.batchStates().isEmpty() && gone.batches.isEmpty())
    }

    @Test fun theSnapshotRoundTripsAndRunningItemsComeBackWaiting() {
        val state = three.start("fakevideo01").finish("fakevideo01").start("fakevideo02")
            .then(QueueEvent.Pause("download-3"))
            .then(QueueEvent.Conditions(QueueConditions(wifiOnly = true, metered = true)))
        val text = DownloadsFile.encode(state)
        assertTrue("\"version\"" in text)
        val back = DownloadsFile.decode(text)!!
        assertEquals(state.nextNumber, back.nextNumber)
        assertEquals(state.batches, back.batches)
        assertEquals(DownloadState.Done("/out/fakevideo01.mp4", 10), back.stateOf("fakevideo01"))
        assertEquals(DownloadState.Waiting, back.stateOf("fakevideo02"), "running comes back waiting")
        assertEquals(DownloadState.Paused(PauseReason.USER), back.stateOf("fakeplvideo01"), "a user pause is kept")
        assertEquals(DownloadState.Waiting, back.stateOf("fakeplvideo02"), "holds are applied again by the controller")
        assertEquals(QueueConditions(), back.conditions)
        assertEquals(state.entries.map { it.copy(state = DownloadState.Waiting) }, back.entries.map { it.copy(state = DownloadState.Waiting) }, "everything else is kept")
        // the next number survives: a new entry does not reuse an id
        assertEquals("download-6", back.then(single("fakevideo05")).entries.last().id)
        assertNull(DownloadsFile.decode("not json"))
        assertNull(DownloadsFile.decode("{\"version\": 99, \"entries\": []}"), "a newer version is not read")
    }

    @Test fun theFileIsWrittenToATemporaryNameThenRenamed() {
        val io = MemoryIo()
        DownloadsFile.save(io, three)
        assertEquals(listOf("write downloads.json.tmp", "rename downloads.json.tmp downloads.json"), io.log)
        assertEquals(three.entries, DownloadsFile.load(io).entries)
        // a broken file is set aside and an empty queue starts
        io.files["downloads.json"] = "{broken"
        assertTrue(DownloadsFile.load(io).entries.isEmpty())
        assertEquals("{broken", io.files["downloads.json.bad"])
        assertTrue(DownloadsFile.load(MemoryIo()).entries.isEmpty(), "no file: an empty queue")
    }

    private class MemoryIo : DownloadsFile.Io {
        val files = mutableMapOf<String, String>()
        val log = mutableListOf<String>()
        override fun read(name: String): String? = files[name]
        override fun write(name: String, text: String) { log += "write $name"; files[name] = text }
        override fun rename(from: String, to: String) { log += "rename $from $to"; files[to] = files.remove(from)!! }
    }
}
