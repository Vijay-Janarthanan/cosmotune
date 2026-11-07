package com.cosmotune.music.desktop

import com.opencanvas.core.stream.ProgressiveRangeSource
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import java.io.IOException
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A clip that cannot be read is a canvas that does not show (the cover stays), never a crash.
 *
 * The JVM aborted (`Internal Error (0x20474343)`, in `avformat_open_input` under
 * `DesktopCanvasDecoder.open`) when a canvas opened while a song's providers were being raced:
 * JavaCPP's callback trampolines are shared by every decoder and handed out with no lock, so two
 * decoders opening at once could be given the same one, and once the other let go of it FFmpeg's
 * next read recursed through JavaCPP until the stack overflowed. These tests drive the decoder
 * through the same custom input the progressive stream uses, with sources that throw, lie and send
 * garbage, alone and on many threads at once. A regression need not fail an assertion: it can kill
 * the test JVM, which fails the run just as surely.
 */
class DesktopCanvasDecoderFailureTest {

    private val clip: ByteArray = javaClass.getResource("/canvas/full-hd.mp4")!!.readBytes()

    /** A clip in memory, read the way a progressive stream is. */
    private open class MemorySource(private val bytes: ByteArray) : DesktopAvioSource {
        private var at = 0L
        override fun read(into: ByteArray, count: Int): Int {
            if (at >= bytes.size) return -1
            val n = minOf(count.toLong(), bytes.size - at).toInt()
            System.arraycopy(bytes, at.toInt(), into, 0, n)
            at += n
            return n
        }
        override fun seek(position: Long) { at = position.coerceAtLeast(0L) }
        override fun position(): Long = at
        override fun length(): Long = bytes.size.toLong()
    }

    /** Opens [source], and decodes a frame when it opened; the decoder is always closed again. */
    private fun attempt(source: DesktopAvioSource): Pair<Result<Unit>, Boolean> {
        val decoder = DesktopCanvasDecoder()
        try {
            val opened = decoder.open(source)
            val decoded = opened.isSuccess &&
                decoder.nextFrame(ByteArray(decoder.width * decoder.height * 4), loop = false)
            return opened to decoded
        } finally {
            decoder.close()
        }
    }

    @Test
    fun `a clip read through a source that behaves decodes`() {
        val (opened, decoded) = attempt(MemorySource(clip))
        assertTrue(opened.isSuccess, "the clip did not open: ${opened.exceptionOrNull()}")
        assertTrue(decoded, "the clip opened but gave no frame")
    }

    @Test
    fun `a source that throws on every read is a failed open, whatever it throws`() {
        val thrown = listOf(
            IOException("connection reset"),
            IllegalStateException("closed"),
            InterruptedException("cancelled"),
            StackOverflowError("from the source"),
            OutOfMemoryError("from the source"),
            NoClassDefFoundError("from the source"),
        )
        for (failure in thrown) {
            val source = object : MemorySource(clip) {
                override fun read(into: ByteArray, count: Int): Int = throw failure
            }
            val (opened, _) = attempt(source)
            assertTrue(opened.isFailure, "opened although every read threw $failure")
            val message = opened.exceptionOrNull()?.message.orEmpty()
            assertTrue(failure.javaClass.name in message, "the failure does not say what the source threw: $message")
            // An interrupted read hands the interrupt back to the thread rather than swallowing it.
            assertEquals(failure is InterruptedException, Thread.interrupted(), "interrupt flag after $failure")
        }
    }

    @Test
    fun `a source that fails partway through the clip is a failed open`() {
        for (cut in listOf(1, 16, 64, 512, clip.size / 2)) {
            val source = object : MemorySource(clip) {
                override fun read(into: ByteArray, count: Int): Int {
                    if (position() >= cut) throw StackOverflowError("cut at $cut")
                    return super.read(into, minOf(count, (cut - position()).toInt()))
                }
            }
            val (opened, decoded) = attempt(source)
            assertTrue(!decoded, "a frame came out of a clip cut at byte $cut")
            assertTrue(opened.isFailure || !decoded, "cut at $cut")
        }
    }

    @Test
    fun `seeks and lengths that throw never get past the decoder`() {
        val source = object : MemorySource(clip) {
            override fun seek(position: Long) = throw StackOverflowError("seek")
            override fun length(): Long = throw LinkageError("length")
        }
        // Whether the clip can be played without seeking is FFmpeg's call; what matters is that the
        // answer comes back here as a result, and that a clip that opened also plays.
        val (opened, decoded) = attempt(source)
        assertTrue(opened.isFailure || decoded, "opened, then gave no frame")
    }

    @Test
    fun `garbage, an empty file and a lying source are failed opens`() {
        val sources = listOf(
            "random bytes" to MemorySource(Random(7).nextBytes(256 * 1024)),
            "zeros" to MemorySource(ByteArray(64 * 1024)),
            "nothing" to MemorySource(ByteArray(0)),
            "a clip with its middle scrambled" to MemorySource(clip.copyOf().also { bytes ->
                for (i in 64 until bytes.size - 64) bytes[i] = (bytes[i].toInt() xor 0x5A).toByte()
            }),
            "more bytes than asked for" to object : MemorySource(clip) {
                override fun read(into: ByteArray, count: Int): Int {
                    super.read(into, count)
                    return count + 1
                }
            },
            "a length of minus one" to object : MemorySource(clip) {
                override fun length(): Long = -1L
                override fun read(into: ByteArray, count: Int): Int = -1
            },
        )
        for ((name, source) in sources) {
            val (opened, decoded) = attempt(source)
            assertTrue(opened.isFailure || !decoded, "$name gave a frame")
            if (name != "a clip with its middle scrambled") assertTrue(opened.isFailure, "$name opened")
        }
    }

    @Test
    fun `a source that opens another clip from inside its read is refused rather than followed`() {
        var inner: Result<Unit>? = null
        val source = object : MemorySource(clip) {
            override fun read(into: ByteArray, count: Int): Int {
                if (inner == null) {
                    // From inside FFmpeg's read: the nested input's callbacks must not run here.
                    inner = attempt(MemorySource(clip)).first
                }
                return super.read(into, count)
            }
        }
        val (opened, decoded) = attempt(source)
        assertTrue(inner!!.isFailure, "a callback ran inside another callback")
        assertTrue(opened.isSuccess && decoded, "the outer clip suffered for it: ${opened.exceptionOrNull()}")
    }

    @Test
    fun `decoders opening on many threads at once each read their own clip and never crash`() {
        val threads = 8
        val rounds = 25
        val problems = Collections.synchronizedList(mutableListOf<String>())
        val start = CountDownLatch(1)
        val workers = (0 until threads).map { worker ->
            Thread {
                start.await()
                repeat(rounds) { round ->
                    runCatching {
                        if ((worker + round) % 3 == 0) {
                            val broken = object : MemorySource(clip) {
                                override fun read(into: ByteArray, count: Int): Int = throw StackOverflowError()
                            }
                            if (attempt(broken).first.isSuccess) problems += "worker $worker round $round: a broken source opened"
                        } else {
                            val (opened, decoded) = attempt(MemorySource(clip))
                            if (!opened.isSuccess || !decoded) {
                                problems += "worker $worker round $round: ${opened.exceptionOrNull() ?: "no frame"}"
                            }
                        }
                    }.onFailure { problems += "worker $worker round $round threw $it" }
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
        assertTrue(workers.none { it.isAlive }, "a worker hung")
        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
    }

    @Test
    fun `the progressive stream fails cleanly on garbage and an erroring server, and opens a real clip`() {
        assertTrue(openStream(clip).isSuccess, "the clip did not open from the stream")
        assertTrue(openStream(Random(3).nextBytes(300 * 1024)).isFailure, "garbage opened")
        assertTrue(openStream(null).isFailure, "a server that only errors opened")
    }

    @Test
    fun `progressive streams opening on many threads at once never cross or crash`() {
        val problems = Collections.synchronizedList(mutableListOf<String>())
        val workers = (0 until 6).map { worker ->
            Thread {
                repeat(10) { round ->
                    val garbage = (worker + round) % 2 == 0
                    val result = runCatching { openStream(if (garbage) Random(round).nextBytes(128 * 1024) else clip) }
                        .getOrElse { Result.failure(it) }
                    if (result.isSuccess == garbage) problems += "worker $worker round $round: garbage=$garbage gave $result"
                }
            }.apply { isDaemon = true; start() }
        }
        workers.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
        assertTrue(workers.none { it.isAlive }, "a worker hung")
        assertTrue(problems.isEmpty(), problems.take(5).joinToString("\n"))
    }

    /** Opens [bytes] through the real progressive reader over a fake server (null: it answers 500). */
    private fun openStream(bytes: ByteArray?): Result<Unit> {
        val source = ProgressiveRangeSource(
            "https://canvas.test/clip.mp4",
            serving(bytes),
            initialChunkBytes = 64 * 1024,
            readTimeoutMillis = 10_000L,
        )
        val decoder = DesktopCanvasDecoder()
        return try {
            decoder.open(source).also { opened ->
                if (opened.isSuccess) check(decoder.nextFrame(ByteArray(decoder.width * decoder.height * 4), loop = false)) { "no frame" }
            }
        } catch (failure: Throwable) {
            Result.failure(failure)
        } finally {
            decoder.close()
            source.close()
        }
    }

    private fun serving(bytes: ByteArray?): OkHttpClient = OkHttpClient.Builder().addInterceptor { chain ->
        val request = chain.request()
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1)
        if (bytes == null) return@addInterceptor builder.code(500).message("Server Error").body(ByteArray(0).toResponseBody()).build()
        val (from, to) = request.header("Range")!!.removePrefix("bytes=").split('-').map { it.toLong() }
        if (from >= bytes.size) return@addInterceptor builder.code(416).message("Range Not Satisfiable").body(ByteArray(0).toResponseBody()).build()
        val end = minOf(to, bytes.size - 1L)
        builder.code(206).message("Partial Content")
            .header("Content-Range", "bytes $from-$end/${bytes.size}")
            .body(bytes.copyOfRange(from.toInt(), end.toInt() + 1).toResponseBody("video/mp4".toMediaType()))
            .build()
    }.build()
}
