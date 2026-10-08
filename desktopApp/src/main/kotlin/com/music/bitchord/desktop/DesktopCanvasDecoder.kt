package com.music.bitchord.desktop

import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import com.opencanvas.core.stream.ProgressiveRangeSource
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FORCE
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_VIDEO
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_BGRA
import org.bytedeco.ffmpeg.global.avutil.av_dict_set
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_image_fill_arrays
import org.bytedeco.ffmpeg.global.avutil.av_image_get_buffer_size
import org.bytedeco.ffmpeg.global.swscale.SWS_BILINEAR
import org.bytedeco.ffmpeg.global.swscale.sws_freeContext
import org.bytedeco.ffmpeg.global.swscale.sws_getContext
import org.bytedeco.ffmpeg.global.swscale.sws_scale
import org.bytedeco.ffmpeg.swscale.SwsContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.IntPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer

/** The motion artwork, decoded here rather than played by a second media stack. */
internal class DesktopCanvasDecoder {

    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private var packet: AVPacket? = null
    private var frame: AVFrame? = null
    private var scaler: SwsContext? = null
    private var buffer: BytePointer? = null
    private var view: java.nio.ByteBuffer? = null
    private var planes: PointerPointer<BytePointer>? = null
    private var strides: IntPointer? = null
    private var scalerFormat = -1
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var streamIndex = -1

    // Custom I/O for a progressive source. FFmpeg holds raw function pointers to the callbacks, so
    // the objects behind them must stay reachable until the context is closed.
    private var customIo: AVIOContext? = null
    private var readCallback: Read_packet_Pointer_BytePointer_int? = null
    private var seekCallback: Seek_Pointer_long_int? = null

    /** Seconds per frame, from the stream's own rate; used to pace playback. */
    var frameIntervalMillis: Long = 40L
        private set

    /** The size frames are handed back at, after scaling. */
    var width: Int = 0
        private set

    var height: Int = 0
        private set

    fun open(url: String, headers: Map<String, String> = emptyMap()): Result<Unit> = runCatching {
        DesktopAudioDecoder.ensureNetworkReady()
        val options = AVDictionary(null)
        if (headers.isNotEmpty()) {
            av_dict_set(
                options,
                "headers",
                headers.entries.joinToString("") { (name, value) -> "$name: $value\r\n" },
                0,
            )
        }
        av_dict_set(options, "rw_timeout", "15000000", 0)

        val opened = AVFormatContext(null)
        check(avformat_open_input(opened, url, null, options) >= 0) { "could not open the clip" }
        format = opened
        prepareStream(opened)
    }.onFailure { close() }

    /**
     * Opens the clip through [source], a progressive range reader, so the first frame can be decoded
     * as soon as the first couple of seconds of the file have arrived instead of after the whole
     * download. FFmpeg pulls bytes through the read callback and repositions with the seek callback
     * (which is how a looping clip rewinds and how a moov atom at the end of a file is reached).
     */
    fun open(source: ProgressiveRangeSource): Result<Unit> = runCatching {
        val scratch = ByteArray(IO_BUFFER)
        val reader = object : Read_packet_Pointer_BytePointer_int() {
            override fun call(opaque: Pointer?, buf: BytePointer?, size: Int): Int = try {
                val n = source.read(scratch, 0, minOf(size, scratch.size))
                if (n < 0) AVERROR_EOF else { buf?.put(scratch, 0, n); n }
            } catch (_: Exception) {
                AVERROR_EIO // nothing may escape into native code
            }
        }
        val seeker = object : Seek_Pointer_long_int() {
            override fun call(opaque: Pointer?, offset: Long, whence: Int): Long = try {
                when (whence and AVSEEK_FORCE.inv()) {
                    AVSEEK_SIZE -> source.length()
                    SEEK_SET -> { source.seek(offset); offset }
                    SEEK_CUR -> { source.seek(source.position + offset); source.position }
                    SEEK_END -> { source.seek(source.length() + offset); source.position }
                    else -> -1L
                }
            } catch (_: Exception) {
                -1L
            }
        }
        readCallback = reader
        seekCallback = seeker
        val ioBuffer = BytePointer(av_malloc(IO_BUFFER.toLong()))
        val io = avio_alloc_context(ioBuffer, IO_BUFFER, 0, null, reader, null, seeker)
        if (io == null) {
            av_free(ioBuffer)
            error("no I/O context")
        }
        customIo = io
        val opened = avformat_alloc_context() ?: error("no format context")
        opened.pb(io)
        // Probe only what the first chunk holds, so the first frame is not held up by FFmpeg
        // reading megabytes it does not need to find a video stream in.
        val options = AVDictionary(null)
        av_dict_set(options, "probesize", "262144", 0)
        av_dict_set(options, "analyzeduration", "1000000", 0)
        check(avformat_open_input(opened, "opencanvas-clip.mp4", null, options) >= 0) { "could not open the clip" }
        format = opened
        prepareStream(opened)
    }.onFailure { close() }

    private fun prepareStream(opened: AVFormatContext) {
        check(avformat_find_stream_info(opened, null as AVDictionary?) >= 0) { "no stream info" }
        streamIndex = av_find_best_stream(
            opened, AVMEDIA_TYPE_VIDEO, -1, -1, null as org.bytedeco.ffmpeg.avcodec.AVCodec?, 0,
        )
        check(streamIndex >= 0) { "no video stream" }

        val stream = opened.streams(streamIndex)
        val parameters = stream.codecpar()
        val decoder = avcodec_find_decoder(parameters.codec_id()) ?: error("no decoder for this clip")
        val context = avcodec_alloc_context3(decoder)
        check(avcodec_parameters_to_context(context, parameters) >= 0) { "bad codec parameters" }
        // Left at one thread a 720p AV1 clip decodes slower than it plays; with the cores used it
        // runs at hundreds of frames a second, which is also what makes a frame-accurate seek (decode
        // from the previous keyframe to the target) cost a fraction of a second.
        context.thread_count(0)
        check(avcodec_open2(context, decoder, null as AVDictionary?) >= 0) { "decoder refused to open" }
        codec = context

        val timeBase = stream.time_base()
        timeBaseNum = timeBase.num().toLong().coerceAtLeast(1L)
        timeBaseDen = timeBase.den().toLong().coerceAtLeast(1L)
        durationMs = when {
            stream.duration() > 0 && stream.duration() != AV_NOPTS_VALUE -> ptsToMs(stream.duration())
            opened.duration() > 0 && opened.duration() != AV_NOPTS_VALUE -> opened.duration() / 1_000L
            else -> 0L
        }

        sourceWidth = context.width()
        sourceHeight = context.height()
        check(sourceWidth > 0 && sourceHeight > 0) { "the clip has no size" }

        // Scaled down on the way out.
        val scale = minOf(1.0, MAX_EDGE.toDouble() / maxOf(sourceWidth, sourceHeight))
        width = ((sourceWidth * scale).toInt() / 2) * 2
        height = ((sourceHeight * scale).toInt() / 2) * 2

        val rate = stream.avg_frame_rate()
        if (rate.num() > 0 && rate.den() > 0) {
            // Never faster than [MIN_INTERVAL_MS]: a sleeve does not need sixty frames a second,
            // and the cost of one is a bitmap.
            frameIntervalMillis = (1_000L * rate.den() / rate.num()).coerceIn(MIN_INTERVAL_MS, 200L)
        }

        // Wired by FFmpeg rather than by hand.
        val size = av_image_get_buffer_size(AV_PIX_FMT_BGRA, width, height, 1)
        buffer = BytePointer(size.toLong())
        view = buffer?.asByteBuffer()
        planes = PointerPointer<BytePointer>(4)
        strides = IntPointer(4L)
        check(
            av_image_fill_arrays(planes, strides, buffer, AV_PIX_FMT_BGRA, width, height, 1) >= 0,
        ) { "could not lay out the frame buffer" }
        packet = av_packet_alloc()
        frame = av_frame_alloc()
    }

    private var timeBaseNum = 1L
    private var timeBaseDen = 1_000L

    /** The clip's length in ms, or 0 when the container does not say. */
    var durationMs: Long = 0L
        private set

    /** Presentation time of the frame most recently decoded, in ms. */
    var lastPtsMs: Long = 0L
        private set

    // After a seek the decoder starts at the keyframe before the target; frames earlier than this
    // are decoded but neither converted nor returned, so the first frame handed back is the target.
    private var skipUntilMs = -1L

    private fun ptsToMs(pts: Long): Long = pts * 1_000L * timeBaseNum / timeBaseDen

    /**
     * Repositions to [ms], frame-accurately: seeks to the keyframe at or before it and discards
     * frames up to it, so the next [nextFrame] returns the frame at [ms]. Over a progressive source
     * the seek makes the reader fetch the bytes at the target first.
     */
    fun seekToMs(ms: Long): Boolean {
        val container = format ?: return false
        val context = codec ?: return false
        val target = ms.coerceAtLeast(0L)
        val pts = target * timeBaseDen / (1_000L * timeBaseNum)
        if (av_seek_frame(container, streamIndex, pts, AVSEEK_FLAG_BACKWARD) < 0) return false
        avcodec_flush_buffers(context)
        skipUntilMs = target - frameIntervalMillis / 2
        lastPtsMs = target
        return true
    }

    /** Drops decoded frames until [ms] without converting them - how a late decoder catches up. */
    fun fastForwardTo(ms: Long) {
        skipUntilMs = ms - frameIntervalMillis / 2
    }

    /**
     * The next frame as BGRA bytes. At the end of the clip it rewinds and carries on when [loop] is
     * true, and otherwise reports false.
     */
    fun nextFrame(into: ByteArray, loop: Boolean = true): Boolean {
        val container = format ?: return false
        val context = codec ?: return false
        val pkt = packet ?: return false
        val decoded = frame ?: return false
        var looped = false
        while (true) {
            if (avcodec_receive_frame(context, decoded) == 0) {
                val stamp = decoded.best_effort_timestamp()
                if (stamp != AV_NOPTS_VALUE) lastPtsMs = ptsToMs(stamp)
                if (lastPtsMs < skipUntilMs) continue
                skipUntilMs = -1L
                val converter = scalerFor(decoded.format()) ?: return false
                sws_scale(converter, decoded.data(), decoded.linesize(), 0, sourceHeight, planes, strides)
                // Read through an explicit NIO view rewound each time rather than through the
                // pointer's own `get`, whose starting offset is its position.
                view?.let { pixels ->
                    pixels.rewind()
                    pixels.get(into, 0, minOf(into.size, width * height * 4))
                }
                return true
            }
            if (av_read_frame(container, pkt) < 0) {
                if (!loop || looped) return false
                looped = true
                av_seek_frame(container, streamIndex, 0, AVSEEK_FLAG_BACKWARD)
                avcodec_flush_buffers(context)
                continue
            }
            if (pkt.stream_index() == streamIndex) avcodec_send_packet(context, pkt)
            av_packet_unref(pkt)
        }
    }

    /** The converter for the format frames are actually arriving in. */
    private fun scalerFor(pixelFormat: Int): SwsContext? {
        scaler?.let { if (pixelFormat == scalerFormat) return it }
        if (pixelFormat < 0) return null
        scaler?.let { sws_freeContext(it) }
        // BGRA because that is the order Skia's N32 bitmaps are laid out in, so the scaled frame
        // can be handed over without a second pass.
        val built = sws_getContext(
            sourceWidth, sourceHeight, pixelFormat,
            width, height, AV_PIX_FMT_BGRA,
            SWS_BILINEAR, null, null, null as DoublePointer?,
        )
        if (built == null) {
            DesktopTrackLog.log("canvas: no converter for pixel format $pixelFormat")
            scaler = null
            return null
        }
        scaler = built
        scalerFormat = pixelFormat
        return built
    }

    fun close() {
        frame?.let { av_frame_free(it) }
        packet?.let { av_packet_free(it) }
        scaler?.let { sws_freeContext(it) }
        codec?.let { avcodec_free_context(it) }
        format?.let { avformat_close_input(it) }
        // A custom I/O context is not released by closing the input; its buffer may also have been
        // reallocated by FFmpeg, so free whatever the context points at now.
        customIo?.let { io ->
            av_free(io.buffer())
            avio_context_free(io)
        }
        customIo = null
        readCallback = null
        seekCallback = null
        buffer?.deallocate()
        strides?.deallocate()
        frame = null
        packet = null
        scaler = null
        scalerFormat = -1
        codec = null
        format = null
        buffer = null
        view = null
        planes = null
        strides = null
        streamIndex = -1
        sourceWidth = 0
        sourceHeight = 0
    }

    private companion object {
        /** Plenty for a sleeve, and a quarter of the pixels of the source. */
        const val MAX_EDGE = 540

        /** Thirty frames a second is motion; sixty is just more bitmaps. */
        const val MIN_INTERVAL_MS = 33L

        const val IO_BUFFER = 64 * 1024

        // POSIX whence values and FFmpeg's error codes, which are not exported as constants.
        const val SEEK_SET = 0
        const val SEEK_CUR = 1
        const val SEEK_END = 2
        const val AVERROR_EOF = -541478725 // -MKTAG('E','O','F',' ')
        const val AVERROR_EIO = -5
    }
}

private typealias DoublePointer = org.bytedeco.javacpp.DoublePointer
