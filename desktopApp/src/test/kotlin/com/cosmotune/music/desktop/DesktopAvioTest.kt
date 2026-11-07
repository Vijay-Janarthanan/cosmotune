package com.cosmotune.music.desktop

import org.bytedeco.ffmpeg.avformat.AVInputFormat
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avio_r8
import java.io.File
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertFails
import kotlin.test.assertTrue

/**
 * The callbacks every FFmpeg user in the app goes through (the canvas and the music video, the
 * audio decoder's windowed stream, the provider tools' cancel check). A regression here can kill
 * the test JVM rather than fail an assertion; either way the run fails.
 */
class DesktopAvioTest {

    private val clip = File(javaClass.getResource("/canvas/full-hd.mp4")!!.toURI())

    /** A source of [byte], forever. */
    private class Constant(private val byte: Byte) : DesktopAvioSource {
        override fun read(into: ByteArray, count: Int): Int { into.fill(byte, 0, count); return count }
        override fun seek(position: Long) = Unit
        override fun position(): Long = 0L
        override fun length(): Long = -1L
    }

    @Test
    fun `inputs opened on many threads at once each read their own source`() {
        // Each thread's input says who it belongs to in every byte; with per-decoder callbacks two
        // threads could share one trampoline and read each other's (and then crash once one let go).
        val threads = 12
        val crossed = AtomicInteger()
        val failures = Collections.synchronizedList(mutableListOf<Throwable>())
        val start = CountDownLatch(1)
        val workers = (1..threads).map { id ->
            Thread {
                start.await()
                repeat(2_000) {
                    runCatching {
                        val input = DesktopAvio.open(Constant(id.toByte()), 4096)
                        try {
                            if (avio_r8(input.context) != id) crossed.incrementAndGet()
                        } finally {
                            input.close()
                        }
                    }.onFailure { failures += it }
                }
            }.apply { isDaemon = true; start() }
        }
        start.countDown()
        workers.forEach { it.join(TimeUnit.MINUTES.toMillis(2)) }
        assertTrue(workers.none { it.isAlive }, "a worker hung")
        assertTrue(failures.isEmpty(), "inputs failed: ${failures.take(3)}")
        assertTrue(crossed.get() == 0, "${crossed.get()} reads came from another thread's source")
    }

    @Test
    fun `an input whose source throws an Error reads as an error, not a crash`() {
        val input = DesktopAvio.open(object : DesktopAvioSource {
            override fun read(into: ByteArray, count: Int): Int = throw StackOverflowError("read")
            override fun seek(position: Long) = throw OutOfMemoryError("seek")
            override fun position(): Long = throw LinkageError("position")
            override fun length(): Long = throw IllegalStateException("length")
        })
        try {
            val format = avformat_alloc_context()
            format.pb(input.context)
            assertTrue(avformat_open_input(format, "x", null as AVInputFormat?, null as AVDictionary?) < 0)
            assertTrue(input.failure is Error, "the input did not keep what the source threw: ${input.failure}")
        } finally {
            input.close()
        }
    }

    @Test
    fun `a cancel check that throws stops FFmpeg instead of crashing it`() {
        val format = avformat_alloc_context()
        val interrupt = DesktopAvio.interruptWhen(format) { throw StackOverflowError("cancel check") }
        try {
            assertTrue(avformat_open_input(format, clip.absolutePath, null as AVInputFormat?, null as AVDictionary?) < 0)
        } finally {
            if (!format.isNull) avformat_close_input(format)
            interrupt.close()
        }
        // And a check that says carry on lets the same file open.
        val open = avformat_alloc_context()
        val carryOn = DesktopAvio.interruptWhen(open) { false }
        try {
            assertTrue(avformat_open_input(open, clip.absolutePath, null as AVInputFormat?, null as AVDictionary?) >= 0)
        } finally {
            if (!open.isNull) avformat_close_input(open)
            carryOn.close()
        }
    }

    @Test
    fun `a provider job whose cancel check throws inside FFmpeg fails like any other`() {
        val asked = AtomicInteger()
        // The first ask is the job's own checkpoint, in Kotlin; every later one comes from FFmpeg.
        assertFails {
            DesktopProviderMediaTools.probe(fixture("clear.m4a")) {
                if (asked.getAndIncrement() > 0) throw StackOverflowError("cancel check") else false
            }
        }
        assertTrue(asked.get() > 1, "FFmpeg never asked the cancel check")
    }

    @Test
    fun `only DesktopAvio makes callbacks for FFmpeg`() {
        // A JavaCPP callback of anyone's own shares the unlocked trampolines with DesktopAvio's
        // and can be given one of them twice; every custom input and cancel check goes through it.
        val callback = Regex("""object\s*:\s*[\w.]*(Read_packet|Write_packet|Seek_Pointer|Callback_Pointer|_Pointer_)\w*\s*\(""")
        val roots = listOf("src/main/kotlin", "../video/src/jvmMain/kotlin", "desktopApp/src/main/kotlin", "video/src/jvmMain/kotlin")
            .map(::File).filter { it.isDirectory }
        assertTrue(roots.isNotEmpty(), "no sources found from ${File(".").absolutePath}")
        val offenders = roots.flatMap { root -> root.walkTopDown().filter { it.extension == "kt" }.toList() }
            .filter { it.name != "DesktopAvio.kt" && callback.containsMatchIn(it.readText()) }
            .map { it.path }
        assertTrue(offenders.isEmpty(), "FFmpeg callbacks made outside DesktopAvio: $offenders")
    }

    private fun fixture(name: String) = File(javaClass.getResource("/provider-media/$name")!!.toURI())
}
