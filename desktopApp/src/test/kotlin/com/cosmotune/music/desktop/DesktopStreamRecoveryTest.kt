package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.playback.recovery.PlaybackFailureKind
import java.net.SocketTimeoutException
import java.util.concurrent.CountDownLatch
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopStreamRecoveryTest {

    @Test
    fun `FFmpeg's read errors are sorted the way the phone sorts Media3's`() {
        val timedOut = DesktopStreamFailures.fromFfmpeg("Connection timed out")
        assertTrue(timedOut.network)
        assertNull(timedOut.httpStatus)
        assertEquals(PlaybackFailureKind.NETWORK, DesktopStreamFailures.classify(timedOut))

        assertTrue(DesktopStreamFailures.fromFfmpeg("Input/output error").network)
        assertTrue(DesktopStreamFailures.fromFfmpeg("Connection reset by peer").network)

        val forbidden = DesktopStreamFailures.fromFfmpeg("Server returned 403 Forbidden (access denied)")
        assertEquals(403, forbidden.httpStatus)
        assertFalse(forbidden.network)
        assertEquals(PlaybackFailureKind.STALE_LINK, DesktopStreamFailures.classify(forbidden))

        val missing = DesktopStreamFailures.fromFfmpeg("Server returned 404 Not Found")
        assertEquals(PlaybackFailureKind.OTHER, DesktopStreamFailures.classify(missing))
        assertEquals(PlaybackFailureKind.OTHER, DesktopStreamFailures.classify(DesktopStreamFailures.fromFfmpeg("Invalid data found when processing input")))
    }

    @Test
    fun `a socket exception under a resolve failure is the network`() {
        val failure = IllegalStateException("no source answered", SocketTimeoutException("connect timed out"))
        assertEquals(PlaybackFailureKind.NETWORK, DesktopStreamFailures.classify(failure))
    }

    /** A source that plays [blocks] blocks and then throws, as a decoder whose connection dropped. */
    private class DroppingSource(private val blocks: Int, private val thrown: Exception) : DesktopSampleSource {
        private var next = 0
        private val buffer = FloatArray(4)
        override var sampleCount: Int = 0
            private set

        override fun readSamples(): FloatArray? {
            if (next >= blocks) throw thrown
            buffer.fill(next++.toFloat())
            sampleCount = buffer.size
            return buffer
        }

        override fun seek(micros: Long) = true
        override fun close() = Unit
    }

    private fun ahead(source: DesktopSampleSource) = DesktopDecodeAhead(
        source = source,
        outputFormat = DesktopPcmFormat(44_100, 2, 4, isFloat = true),
        durationUs = 180_000_000L,
        measuredFormat = null,
        aheadSamples = 64,
    )

    @Test
    fun `a dropped stream ends as a failure the engine can read, not as the end of the song`() {
        val dropped = DesktopStreamFailures.fromFfmpeg("Connection timed out")
        val reader = ahead(DroppingSource(blocks = 3, thrown = dropped))
        try {
            repeat(3) { assertEquals(it.toFloat(), reader.readSamples()!![0]) }
            assertNull(reader.readSamples())
            assertTrue(reader.readFailed)
            assertIs<DesktopStreamException>(reader.readFailure)
            assertEquals(PlaybackFailureKind.NETWORK, DesktopStreamFailures.classify(reader.readFailure))
        } finally {
            reader.close()
        }
    }

    /** A source whose every read blocks, as one stuck on a stalled connection. */
    private class StuckSource : DesktopSampleSource {
        val release = CountDownLatch(1)
        override val sampleCount: Int = 0
        override fun readSamples(): FloatArray? {
            release.await()
            return null
        }

        override fun seek(micros: Long) = true
        override fun close() = Unit
    }

    @Test
    fun `a stall recovery can let the audio thread out of an unbounded wait`() {
        val source = StuckSource()
        val reader = ahead(source)
        try {
            val waiting = Thread { runCatching { reader.readSamples() }.onFailure { thrownInWait = it } }
            waiting.start()
            Thread.sleep(50)
            assertTrue(waiting.isAlive, "parked on the stuck decoder")
            reader.interruptWait()
            waiting.join(2_000)
            assertFalse(waiting.isAlive)
            assertIs<DesktopDecodePending>(thrownInWait)
            assertFalse(reader.readFailed, "let go, not failed")
        } finally {
            source.release.countDown()
            reader.close()
        }
    }

    @Volatile private var thrownInWait: Throwable? = null

    private fun songs(vararg ids: String) = ids.map { Song(it, it.uppercase(), "Artist", null, queueTier = QueueTier.CONTEXT) }

    @Test
    fun `repeat-all starting over with shuffle on deals the round a new order`() {
        val ids = (1..30).map { "s$it" }.toTypedArray()
        val last = DesktopQueue(songs(*ids), index = ids.lastIndex)
        val rounds = (1..5).map { last.copy(index = 0).newRound(wrappedFrom = last, shuffle = true) }
        rounds.forEach { round ->
            assertEquals("s1", round.current?.videoId, "the round starts where the queue does")
            assertEquals(ids.toSet(), round.songs.map(Song::videoId).toSet(), "nothing gained or lost")
        }
        assertTrue(rounds.any { it.songs != last.songs }, "a fresh order")
        assertNotEquals(1, rounds.map { it.songs }.toSet().size, "and a different one each round")
    }

    @Test
    fun `without shuffle, or away from the wrap, the order is left alone`() {
        val last = DesktopQueue(songs("a", "b", "c", "d"), index = 3)
        assertEquals(last.copy(index = 0), last.copy(index = 0).newRound(wrappedFrom = last, shuffle = false))
        val middle = DesktopQueue(songs("a", "b", "c", "d"), index = 1)
        assertEquals(middle.copy(index = 0), middle.copy(index = 0).newRound(wrappedFrom = middle, shuffle = true))
    }
}
