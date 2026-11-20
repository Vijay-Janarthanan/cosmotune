package com.cosmotune.canvas

import com.opencanvas.core.BatchDeadline
import com.opencanvas.core.MeasurementShare
import com.opencanvas.core.stream.SongIdentity
import com.opencanvas.core.sync.AlignResult
import com.opencanvas.core.sync.Measurement
import com.opencanvas.core.sync.SyncSegment
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * A song whose stream id changes mid-lookup starts a second lookup of the same song. Seen on a slow emulator: the first lookup
 * measured the official video (confidence 0.86, 98 % of the song), the second measured the same video again beside it, ran past
 * the batch's wait in long garbage-collection pauses, and a weaker upload was shown. The second lookup now takes the first's
 * measurement (or waits for it), and the batch's wait gives back the time the device was stalled.
 */
class CanvasMeasurementShareTest {
    private fun measurement(confidence: Double) =
        Measurement(AlignResult(1_200L, confidence, 0.98, listOf(SyncSegment(0L, Long.MAX_VALUE, 1_200L))), videoKbps = 900.0)

    private val song = SongIdentity.of("Song Name", "Some Artist", 228)
    private val key = MeasurementShare.key(song, "stream-1", "official")

    @Test fun theSongsIdentityNotItsStreamIdIsTheKey() {
        assertEquals(key, MeasurementShare.key(song, "stream-2", "official"), "a new stream id of the same song shares the measurement")
        assertEquals("stream-1|official", MeasurementShare.key(SongIdentity.of("Song Name", "Some Artist", 0), "stream-1", "official"),
            "without a length the song's own stream id keys it")
        assertNull(MeasurementShare.key(null, null, "official"))
    }

    @Test fun aSecondLookupTakesTheMeasurementAlreadyMade() = runTest {
        val share = MeasurementShare()
        val first = measurement(0.86)
        assertSame(first, share.measure(key) { first })
        var measuredAgain = false
        val second = share.measure(MeasurementShare.key(song, "stream-2", "official")) { measuredAgain = true; measurement(0.5) }
        assertSame(first, second)
        assertEquals(false, measuredAgain, "the same song and video is not measured a second time")
    }

    @Test fun aSecondLookupWaitsForTheMeasuringUnderWay() = runTest {
        val share = MeasurementShare()
        val gate = CompletableDeferred<Unit>()
        val official = measurement(0.86)
        var measurings = 0
        val first = async { share.measure(key) { measurings++; gate.await(); official } }
        runCurrent()
        val second = async { share.measure(key) { measurings++; measurement(0.5) } }
        runCurrent()
        gate.complete(Unit)
        assertSame(official, first.await())
        assertSame(official, second.await(), "the waiting lookup gets the first one's answer")
        assertEquals(1, measurings)
    }

    @Test fun whenTheFirstLookupIsAbandonedTheOneWaitingMeasuresForItself() = runTest {
        val share = MeasurementShare()
        val never = CompletableDeferred<Measurement?>()
        val first = launch { share.measure(key) { never.await() } }
        runCurrent()
        val own = measurement(0.9)
        val second = async { share.measure(key) { own } }
        runCurrent()
        first.cancelAndJoin()
        assertSame(own, second.await())
    }

    @Test fun aMeasurementThatDidNotLineUpIsNotKept() = runTest {
        val share = MeasurementShare()
        share.measure(key) { measurement(0.1) }
        assertNull(share.kept(key!!))
        val again = measurement(0.86)
        assertSame(again, share.measure(key) { again })
        assertNotNull(share.kept(key))
    }

    @Test fun theBatchsWaitGivesBackTheTimeTheDeviceWasStalled() {
        var now = 0L
        val deadline = BatchDeadline(12_000L, maxExtraMs = 12_000L) { now }
        // An ordinary wait: a quarter second asked, a quarter second taken.
        now += 250
        deadline.waited(250, 250)
        assertEquals(11_750L, deadline.remaining())
        // A garbage-collection pause: a quarter second asked, four seconds taken. The pause is given back.
        now += 4_000
        deadline.waited(250, 4_000)
        assertEquals(11_750L - 250 - BatchDeadline.STALL_SLACK_MS, deadline.remaining())
        // Never more than the extra allowed in all.
        now += 60_000
        deadline.waited(250, 60_000)
        assertEquals(0L, deadline.remaining(), "the stalls given back are capped")
    }
}
