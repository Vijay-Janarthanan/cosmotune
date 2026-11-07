package com.cosmotune.music.desktop

import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVIOInterruptCB
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.Pointer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * What FFmpeg reads a custom input through. Any of these may throw anything: [DesktopAvio] turns
 * every failure into an FFmpeg error code, because nothing may be thrown back into native code.
 */
internal interface DesktopAvioSource {
    /** Up to [count] bytes into the start of [into]; -1 at the end. */
    fun read(into: ByteArray, count: Int): Int

    /** Moves the read position to [position]. */
    fun seek(position: Long)

    /** Where the next read comes from. */
    fun position(): Long

    /** The total size in bytes, or a negative number while it is not known. */
    fun length(): Long
}

/**
 * Every callback FFmpeg makes into the app, built so that no input, however broken, can take the
 * JVM down with it.
 *
 * JavaCPP gives each callback class ten trampolines for the whole process and hands them out, and
 * takes them back, with no lock. Two decoders building their callbacks on two threads at once (a
 * song's providers are raced while the canvas opens) can be given the same one: one then reads the
 * other's bytes, and once the other releases it, FFmpeg's next call finds no Java object behind it.
 * JavaCPP then makes a bare one, whose `call` is the native trampoline itself, so the call recurses
 * until the stack overflows; the C++ exception it ends in has no handler in FFmpeg's frames and the
 * JVM aborts (`Internal Error (0x20474343)` in KERNELBASE). A Java exception that leaves a callback
 * ends the same way.
 *
 * So there is one read, one seek and one interrupt callback for the whole process, made once and
 * never released, and each input is found by the id FFmpeg passes back to them as `opaque`. An id
 * that is not registered, a callback entered again on its own thread, and anything thrown, all come
 * back to FFmpeg as an error code.
 */
internal object DesktopAvio {

    const val DEFAULT_BUFFER_BYTES = 64 * 1024

    // POSIX whence values and FFmpeg's error codes, which are not exported as constants.
    private const val SEEK_SET = 0
    private const val SEEK_CUR = 1
    private const val SEEK_END = 2
    const val AVERROR_EOF = -541478725 // -MKTAG('E','O','F',' ')
    const val AVERROR_EIO = -5

    internal class Entry(val source: DesktopAvioSource, bufferBytes: Int) {
        val scratch = ByteArray(bufferBytes)

        /** The first thing the source threw, so the caller can say why the input failed. */
        @Volatile var failure: Throwable? = null
    }

    private val ids = AtomicLong()
    private val inputs = ConcurrentHashMap<Long, Entry>()
    private val interrupts = ConcurrentHashMap<Long, () -> Boolean>()

    /** Set while a callback runs on this thread: nothing FFmpeg calls back into calls FFmpeg. */
    private val inCallback = ThreadLocal<Boolean>()

    private val reader = object : Read_packet_Pointer_BytePointer_int() {
        override fun call(opaque: Pointer?, buf: BytePointer?, size: Int): Int = guarded(opaque, AVERROR_EIO) { entry ->
            if (buf == null || buf.isNull || size <= 0) return@guarded AVERROR_EIO
            val count = minOf(size, entry.scratch.size)
            val n = entry.source.read(entry.scratch, count)
            when {
                n <= 0 -> AVERROR_EOF
                n > count -> AVERROR_EIO
                else -> {
                    buf.put(entry.scratch, 0, n)
                    n
                }
            }
        }
    }

    private val seeker = object : Seek_Pointer_long_int() {
        override fun call(opaque: Pointer?, offset: Long, whence: Int): Long = guarded(opaque, AVERROR_EIO.toLong()) { entry ->
            val source = entry.source
            when (whence and AVSEEK_FORCE.inv()) {
                AVSEEK_SIZE -> source.length().takeIf { it >= 0 } ?: AVERROR_EIO.toLong()
                SEEK_SET -> { source.seek(offset); source.position() }
                SEEK_CUR -> { source.seek(source.position() + offset); source.position() }
                SEEK_END -> {
                    val length = source.length()
                    if (length < 0) AVERROR_EIO.toLong() else { source.seek(length + offset); source.position() }
                }
                else -> AVERROR_EIO.toLong()
            }
        }
    }

    private val interrupter = object : AVIOInterruptCB.Callback_Pointer() {
        // A check that is gone, or that fails, stops the work: it is only ever asked whether to stop.
        override fun call(opaque: Pointer?): Int = try {
            if (inCallback.get() == true) {
                1
            } else {
                val cancelled = interrupts[key(opaque)]
                if (cancelled == null) 1 else within { if (cancelled()) 1 else 0 }
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            1
        }
    }

    /** An id handed to FFmpeg as `opaque`: FFmpeg only passes it back, it never reads through it. */
    private class Opaque(id: Long) : Pointer() {
        init {
            address = id
        }
    }

    private fun key(opaque: Pointer?): Long = opaque?.address() ?: 0L

    private inline fun <T> within(block: () -> T): T {
        inCallback.set(true)
        try {
            return block()
        } finally {
            inCallback.set(false)
        }
    }

    /**
     * Runs [block] for the input [opaque] names, and returns [failed] instead when that input is not
     * registered, when a callback is already running on this thread, or when anything is thrown.
     */
    private inline fun <T> guarded(opaque: Pointer?, failed: T, block: (Entry) -> T): T {
        var entry: Entry? = null
        return try {
            if (inCallback.get() == true) {
                failed
            } else {
                val found = inputs[key(opaque)]
                entry = found
                if (found == null) failed else within { block(found) }
            }
        } catch (failure: Throwable) {
            if (failure is InterruptedException) Thread.currentThread().interrupt()
            entry?.let { if (it.failure == null) it.failure = failure }
            failed
        }
    }

    /** A custom input open for FFmpeg to read; close it after the format context reading it. */
    class Input internal constructor(context: AVIOContext, private val id: Long, private val entry: Entry) {
        private var io: AVIOContext? = context

        /** The context to hand to `AVFormatContext.pb`. */
        val context: AVIOContext get() = io ?: error("the input is closed")

        /** What the source threw, if it threw; FFmpeg itself was only told "I/O error". */
        val failure: Throwable? get() = entry.failure

        fun close() {
            val context = io ?: return
            io = null
            // The buffer may have been reallocated by FFmpeg, so free whatever the context holds now.
            av_free(context.buffer())
            context.buffer(null as BytePointer?)
            avio_context_free(context)
            // Only once FFmpeg holds nothing that could still call back with this id.
            inputs.remove(id)
        }
    }

    /** A read-only, seekable FFmpeg input over [source]. */
    fun open(source: DesktopAvioSource, bufferBytes: Int = DEFAULT_BUFFER_BYTES): Input {
        require(bufferBytes > 0) { "no buffer" }
        val id = ids.incrementAndGet()
        val entry = Entry(source, bufferBytes)
        inputs[id] = entry
        val buffer = av_malloc(bufferBytes.toLong())
        if (buffer == null || buffer.isNull) {
            inputs.remove(id)
            error("no memory for the I/O buffer")
        }
        val context = avio_alloc_context(BytePointer(buffer), bufferBytes, 0, Opaque(id), reader, null, seeker)
        if (context == null || context.isNull) {
            av_free(buffer)
            inputs.remove(id)
            error("no I/O context")
        }
        return Input(context, id, entry)
    }

    /**
     * Has FFmpeg give up whatever it is doing on [context] once [cancelled] says so (or fails). Close
     * what this returns after the context has been closed.
     */
    fun interruptWhen(context: AVFormatContext, cancelled: () -> Boolean): AutoCloseable {
        val id = ids.incrementAndGet()
        interrupts[id] = cancelled
        context.interrupt_callback().opaque(Opaque(id)).callback(interrupter)
        return AutoCloseable { interrupts.remove(id) }
    }

    /** FFmpeg's own words for an error code. */
    fun describe(status: Int): String {
        val text = ByteArray(256)
        av_strerror(status, text, text.size.toLong())
        return String(text, Charsets.UTF_8).substringBefore('\u0000').trim().ifBlank { "error $status" }
    }
}
