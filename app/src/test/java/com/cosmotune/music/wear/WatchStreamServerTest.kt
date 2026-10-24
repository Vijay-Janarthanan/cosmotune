package com.cosmotune.music.wear

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * The phone-backed stream the watch speaker plays through: the phone's real
 * server ([WatchStreamServer]) and the watch's real framing ([WearWire]) on
 * the two ends of a pipe, standing in for the Wearable channel.
 */
class WatchStreamServerTest {

    private val track = ByteArray(200_000) { (it * 31 + 7).toByte() }

    /** A track the phone's chain opened, honouring the request's position and length. */
    private inner class TrackSource(private val request: WearWire.StreamRequest, private val slowAfter: Int = -1) :
        WatchStreamServer.Source {
        private var at = request.position.toInt()
        private val end = if (request.length < 0) track.size else minOf(track.size, at + request.length.toInt())
        val closed = AtomicBoolean(false)
        override val length: Long = (end - at).toLong()
        override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
            if (at >= end) return -1
            if (slowAfter in 0..at) Thread.sleep(5)
            val n = minOf(length, end - at)
            System.arraycopy(track, at, buffer, offset, n)
            at += n
            return n
        }
        override fun close() = closed.set(true)
    }

    private class Wire {
        val toPhone = PipedOutputStream()
        val phoneIn = PipedInputStream(toPhone, 64 * 1024)
        val toWatch = PipedOutputStream()
        val watchIn = PipedInputStream(toWatch, 64 * 1024)
    }

    private val pool = Executors.newCachedThreadPool()

    private fun phone(wire: Wire, open: (WearWire.StreamRequest) -> WatchStreamServer.Source?): Future<Long> =
        pool.submit<Long> {
            try {
                WatchStreamServer.serve(wire.phoneIn, wire.toWatch, bufferSize = 8 * 1024, open = open)
            } finally {
                wire.toWatch.close()
            }
        }

    /** The watch's side of PhoneStreamDataSource.open/read, minus the channel. */
    private fun watchRead(wire: Wire, request: WearWire.StreamRequest): Pair<WearWire.StreamReply, ByteArray> {
        DataOutputStream(wire.toPhone).apply {
            request.writeTo(this)
            flush()
        }
        val input = DataInputStream(BufferedInputStream(wire.watchIn, 16 * 1024))
        val reply = WearWire.StreamReply.readFrom(input)
        if (!reply.ok) return reply to ByteArray(0)
        val body = WearWire.StreamBody(input, WearWire.expectedLength(request.length, reply))
        val got = ByteArrayOutputStream()
        val buffer = ByteArray(4096)
        while (true) {
            val read = body.read(buffer, 0, buffer.size)
            if (read < 0) break
            got.write(buffer, 0, read)
        }
        return reply to got.toByteArray()
    }

    @Test
    fun `a whole track arrives byte for byte`() {
        val wire = Wire()
        val opened = AtomicReference<TrackSource>()
        val served = phone(wire) { TrackSource(it).also(opened::set) }
        val (reply, bytes) = watchRead(wire, WearWire.StreamRequest("cosmotune://source/a", 0L, -1L))
        assertTrue(reply.ok)
        assertEquals(track.size.toLong(), reply.length)
        assertArrayEquals(track, bytes)
        assertEquals(track.size.toLong(), served.get(5, TimeUnit.SECONDS))
        assertTrue("the phone's source is closed", opened.get().closed.get())
    }

    @Test
    fun `a seek is a read from the new position, bounded when the watch bounds it`() {
        val wire = Wire()
        val asked = AtomicReference<WearWire.StreamRequest>()
        phone(wire) { request -> asked.set(request); TrackSource(request) }
        val (reply, bytes) = watchRead(wire, WearWire.StreamRequest("cosmotune://source/a", 150_000L, 10_000L))
        assertEquals(WearWire.StreamRequest("cosmotune://source/a", 150_000L, 10_000L), asked.get())
        assertEquals(10_000L, reply.length)
        assertArrayEquals(track.copyOfRange(150_000, 160_000), bytes)
    }

    @Test
    fun `a track the phone cannot open is a failure, not a hang`() {
        val wire = Wire()
        val served = phone(wire) { null }
        val (reply, bytes) = watchRead(wire, WearWire.StreamRequest("cosmotune://source/missing", 0L, -1L))
        assertFalse(reply.ok)
        assertEquals(0, bytes.size)
        assertEquals(-1L, served.get(5, TimeUnit.SECONDS))
    }

    @Test
    fun `a resolver that throws is the same failure`() {
        val wire = Wire()
        phone(wire) { throw IllegalStateException("no stream for this track") }
        val (reply, _) = watchRead(wire, WearWire.StreamRequest("cosmotune://source/x", 0L, -1L))
        assertFalse(reply.ok)
    }

    @Test
    fun `the watch closing early (a seek elsewhere) ends the read and closes the source`() {
        val wire = Wire()
        val opened = AtomicReference<TrackSource>()
        val started = CountDownLatch(1)
        val served = phone(wire) { TrackSource(it, slowAfter = 0).also { s -> opened.set(s); started.countDown() } }
        DataOutputStream(wire.toPhone).apply {
            WearWire.StreamRequest("cosmotune://source/a", 0L, -1L).writeTo(this)
            flush()
        }
        val input = DataInputStream(wire.watchIn)
        assertTrue(WearWire.StreamReply.readFrom(input).ok)
        assertTrue(started.await(5, TimeUnit.SECONDS))
        input.read(ByteArray(1024))
        // The watch's player moved on: it closes its end.
        wire.watchIn.close()
        val failure = runCatching { served.get(10, TimeUnit.SECONDS) }.exceptionOrNull()
        assertTrue("the server stops writing: $failure", failure?.cause is IOException)
        assertTrue(opened.get().closed.get())
    }
}
