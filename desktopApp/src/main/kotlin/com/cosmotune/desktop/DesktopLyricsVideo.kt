package com.cosmotune.desktop

import com.cosmotune.lyrics.video.LyricsVideoPlan
import com.cosmotune.music.desktop.DesktopAudioDecoder
import com.cosmotune.music.desktop.DesktopPcmFormat
import com.cosmotune.music.desktop.DesktopStream
import com.cosmotune.music.desktop.DesktopStreamClient
import com.cosmotune.music.ui.player.LyricsShareRequest
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.*
import org.bytedeco.ffmpeg.global.avformat.*
import org.bytedeco.ffmpeg.global.avutil.*
import org.bytedeco.ffmpeg.global.swscale.*
import org.bytedeco.javacpp.DoublePointer
import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.awt.image.DataBufferByte
import java.io.File
import java.net.URI
import javax.imageio.ImageIO
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * The desktop's lyrics video: the same [LyricsVideoPlan] frames as the phone's, drawn with Java2D and encoded with the
 * FFmpeg libraries the desktop already ships (H.264 through whichever encoder this build of FFmpeg has, OpenH264, Media
 * Foundation or VideoToolbox, and MPEG-4 Part 2 when it has none; AAC audio), muxed into an MP4.
 */
internal object DesktopLyricsVideo {

    private const val WIDTH = 720
    private const val HEIGHT = 1280
    private const val FPS = 24
    private const val RATE = 44_100

    /** Writes the video to [target]; [onProgress] goes 0..1. The audio is left out when the song cannot be read. */
    suspend fun export(request: LyricsShareRequest, plan: LyricsVideoPlan, target: File, onProgress: (Float) -> Unit) {
        val audio = runCatching { decodeClip(request, plan) }.getOrNull()
        val cover = runCatching { loadCover(request.artworkUrl) }.getOrNull()
        encode(request, plan, cover, audio, target, onProgress)
    }

    private suspend fun decodeClip(request: LyricsShareRequest, plan: LyricsVideoPlan): FloatArray? {
        val song = request.song
        val stream = song.localPath?.takeIf { File(it).isFile }?.let { DesktopStream(url = it) }
            ?: DesktopStreamClient.resolve(song).getOrNull() ?: return null
        val decoder = DesktopAudioDecoder()
        try {
            decoder.open(
                url = stream.url,
                headers = stream.headers,
                requested = DesktopPcmFormat(RATE, channels = 2, bytesPerSample = 4, isFloat = true),
                windowed = stream.windowedReads,
                transport = stream.transport,
            ).getOrThrow()
            if (plan.startMs > 0 && !decoder.seek(plan.startMs * 1_000)) return null
            val wanted = (plan.durationMs * RATE / 1_000).toInt() * 2
            val out = FloatArray(wanted)
            var filled = 0
            while (filled < wanted) {
                val block = decoder.readSamples() ?: break
                val count = minOf(decoder.sampleCount, wanted - filled)
                System.arraycopy(block, 0, out, filled, count)
                filled += count
            }
            return if (filled == 0) null else out.copyOf(filled)
        } finally {
            decoder.close()
        }
    }

    private fun loadCover(url: String?): BufferedImage? {
        url ?: return null
        return if (url.startsWith("http")) {
            com.cosmotune.music.data.Http.client.newCall(okhttp3.Request.Builder().url(url).build()).execute()
                .use { response -> response.body?.byteStream()?.use(ImageIO::read) }
        } else {
            ImageIO.read(if (url.startsWith("file:")) File(URI(url)) else File(url))
        }
    }

    private fun encode(request: LyricsShareRequest, plan: LyricsVideoPlan, cover: BufferedImage?, audio: FloatArray?, target: File, onProgress: (Float) -> Unit) {
        val output = AVFormatContext(null as org.bytedeco.javacpp.Pointer?)
        check(avformat_alloc_output_context2(output, null, "mp4", target.absolutePath) >= 0 && !output.isNull) { "No MP4 muxer" }
        val io = AVIOContext(null as org.bytedeco.javacpp.Pointer?)
        var opened = false
        val packet = av_packet_alloc()
        var video: AVCodecContext? = null
        var sound: AVCodecContext? = null
        val frames = ArrayList<AVFrame>()
        var scaler: org.bytedeco.ffmpeg.swscale.SwsContext? = null
        try {
            val globalHeader = (output.oformat().flags() and AVFMT_GLOBALHEADER) != 0
            val videoCodec = openVideo(globalHeader) ?: error("This FFmpeg has no video encoder for MP4")
            video = videoCodec
            val videoStream = avformat_new_stream(output, null)
            check(avcodec_parameters_from_context(videoStream.codecpar(), videoCodec) >= 0)
            videoStream.time_base(videoCodec.time_base())

            var audioStream: AVStream? = null
            if (audio != null) {
                sound = openAudio(globalHeader)
                sound?.let { codec ->
                    audioStream = avformat_new_stream(output, null).also { stream ->
                        check(avcodec_parameters_from_context(stream.codecpar(), codec) >= 0)
                        stream.time_base(codec.time_base())
                    }
                }
            }
            check(avio_open(io, target.absolutePath, AVIO_FLAG_WRITE) >= 0) { "Cannot write ${target.name}" }
            opened = true
            output.pb(io)
            check(avformat_write_header(output, null as AVDictionary?) >= 0)

            fun drain(codec: AVCodecContext, stream: AVStream) {
                while (true) {
                    val status = avcodec_receive_packet(codec, packet)
                    if (status == AVERROR_EOF || status == AVERROR_EAGAIN()) return
                    check(status >= 0)
                    try {
                        av_packet_rescale_ts(packet, codec.time_base(), stream.time_base())
                        packet.stream_index(stream.index())
                        check(av_interleaved_write_frame(output, packet) >= 0)
                    } finally {
                        av_packet_unref(packet)
                    }
                }
            }

            // Video: each frame drawn in BGR, converted to the encoder's YUV.
            val bgr = av_frame_alloc().also(frames::add)
            bgr.format(AV_PIX_FMT_BGR24).width(WIDTH).height(HEIGHT)
            check(av_frame_get_buffer(bgr, 1) >= 0)
            val yuv = av_frame_alloc().also(frames::add)
            yuv.format(videoCodec.pix_fmt()).width(WIDTH).height(HEIGHT)
            check(av_frame_get_buffer(yuv, 0) >= 0)
            scaler = sws_getContext(WIDTH, HEIGHT, AV_PIX_FMT_BGR24, WIDTH, HEIGHT, videoCodec.pix_fmt(), SWS_BICUBIC, null, null, null as DoublePointer?)
            val image = BufferedImage(WIDTH, HEIGHT, BufferedImage.TYPE_3BYTE_BGR)
            val pixels = (image.raster.dataBuffer as DataBufferByte).data
            val painter = Painter(request, cover)
            val total = (plan.durationMs * FPS / 1000).toInt().coerceAtLeast(1)
            val audioFrames = audio?.let { it.size / 2 } ?: 0
            var audioAt = 0
            for (index in 0 until total) {
                painter.draw(image, plan.frameAt(index * 1_000L / FPS))
                check(av_frame_make_writable(bgr) >= 0 && av_frame_make_writable(yuv) >= 0)
                val stride = bgr.linesize(0)
                val plane = bgr.data(0)
                for (row in 0 until HEIGHT) plane.position(row.toLong() * stride).put(pixels, row * WIDTH * 3, WIDTH * 3)
                plane.position(0)
                sws_scale(scaler, bgr.data(), bgr.linesize(), 0, HEIGHT, yuv.data(), yuv.linesize())
                yuv.pts(index.toLong())
                check(avcodec_send_frame(videoCodec, yuv) >= 0)
                drain(videoCodec, videoStream)
                // Audio kept level with the video so the muxer interleaves without holding much.
                val codec = sound
                val stream = audioStream
                if (codec != null && stream != null && audio != null) {
                    val upTo = ((index + 1).toLong() * RATE / FPS).toInt().coerceAtMost(audioFrames)
                    audioAt = sendAudio(codec, audio, audioAt, upTo, frames) { drain(codec, stream) }
                }
                onProgress((index + 1).toFloat() / total * 0.98f)
            }
            check(avcodec_send_frame(videoCodec, null) >= 0)
            drain(videoCodec, videoStream)
            val codec = sound
            val stream = audioStream
            if (codec != null && stream != null && audio != null) {
                audioAt = sendAudio(codec, audio, audioAt, audioFrames, frames) { drain(codec, stream) }
                check(avcodec_send_frame(codec, null) >= 0)
                drain(codec, stream)
            }
            check(av_write_trailer(output) >= 0)
            onProgress(1f)
        } finally {
            scaler?.let(::sws_freeContext)
            frames.forEach { av_frame_free(it) }
            av_packet_free(packet)
            video?.let { avcodec_free_context(it) }
            sound?.let { avcodec_free_context(it) }
            if (opened) avio_closep(io)
            avformat_free_context(output)
        }
    }

    /** Sends whole encoder frames of [audio] (interleaved stereo) from frame [from] up to [to]; answers where it stopped. */
    private fun sendAudio(codec: AVCodecContext, audio: FloatArray, from: Int, to: Int, frames: MutableList<AVFrame>, drain: () -> Unit): Int {
        val size = codec.frame_size().takeIf { it > 0 } ?: 1024
        val frame = frames.getOrNull(2) ?: av_frame_alloc().also { created ->
            created.format(codec.sample_fmt()).sample_rate(RATE).nb_samples(size)
            check(av_channel_layout_copy(created.ch_layout(), codec.ch_layout()) >= 0 && av_frame_get_buffer(created, 0) >= 0)
            frames.add(created)
        }
        var at = from
        val last = to == audio.size / 2
        while (to - at >= size || (last && at < to)) {
            val count = minOf(size, to - at)
            check(av_frame_make_writable(frame) >= 0)
            val left = frame.data(0).capacity(size * 4L).asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            val right = frame.data(1).capacity(size * 4L).asByteBuffer().order(java.nio.ByteOrder.nativeOrder()).asFloatBuffer()
            for (i in 0 until size) {
                left.put(i, if (i < count) audio[2 * (at + i)] else 0f)
                right.put(i, if (i < count) audio[2 * (at + i) + 1] else 0f)
            }
            frame.pts(at.toLong())
            check(avcodec_send_frame(codec, frame) >= 0)
            drain()
            at += count
        }
        return at
    }

    private fun openVideo(globalHeader: Boolean): AVCodecContext? {
        val candidates = listOf("libopenh264", "h264_mf", "h264_videotoolbox", "libx264")
            .mapNotNull { avcodec_find_encoder_by_name(it)?.takeIf { codec -> !codec.isNull } } +
            listOfNotNull(avcodec_find_encoder(AV_CODEC_ID_MPEG4)?.takeIf { !it.isNull })
        for (codec in candidates) {
            val context = avcodec_alloc_context3(codec) ?: continue
            context.width(WIDTH).height(HEIGHT).pix_fmt(AV_PIX_FMT_YUV420P).bit_rate(4_000_000L).gop_size(FPS).max_b_frames(0)
            context.time_base().num(1).den(FPS)
            context.framerate().num(FPS).den(1)
            if (globalHeader) context.flags(context.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
            if (avcodec_open2(context, codec, null as AVDictionary?) >= 0) return context
            avcodec_free_context(context)
        }
        return null
    }

    private fun openAudio(globalHeader: Boolean): AVCodecContext? {
        val codec: AVCodec = avcodec_find_encoder(AV_CODEC_ID_AAC)?.takeIf { !it.isNull } ?: return null
        val context = avcodec_alloc_context3(codec) ?: return null
        context.sample_fmt(AV_SAMPLE_FMT_FLTP).sample_rate(RATE).bit_rate(160_000L)
        av_channel_layout_default(context.ch_layout(), 2)
        context.time_base().num(1).den(RATE)
        if (globalHeader) context.flags(context.flags() or AV_CODEC_FLAG_GLOBAL_HEADER)
        if (avcodec_open2(context, codec, null as AVDictionary?) >= 0) return context
        avcodec_free_context(context)
        return null
    }

    /** The phone's layout in Java2D: the cover darkened behind, the cover, the song, and the lines with the sung part lit. */
    private class Painter(private val request: LyricsShareRequest, private val cover: BufferedImage?) {
        private val side = 64
        private val title = Font(Font.SANS_SERIF, Font.BOLD, 34)
        private val artist = Font(Font.SANS_SERIF, Font.PLAIN, 28)
        private val current = Font(Font.SANS_SERIF, Font.BOLD, 56)
        private val aside = Font(Font.SANS_SERIF, Font.BOLD, 36)
        private val brand = Font(Font.SANS_SERIF, Font.PLAIN, 24)

        fun draw(image: BufferedImage, frame: LyricsVideoPlan.Frame) {
            val g = image.createGraphics()
            try {
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                g.color = Color(18, 18, 22)
                g.fillRect(0, 0, WIDTH, HEIGHT)
                cover?.let { art ->
                    val scale = max(WIDTH.toDouble() / art.width, HEIGHT.toDouble() / art.height)
                    val w = (art.width * scale).toInt(); val h = (art.height * scale).toInt()
                    g.drawImage(art, (WIDTH - w) / 2, (HEIGHT - h) / 2, w, h, null)
                    g.composite = AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.67f)
                    g.color = Color.BLACK
                    g.fillRect(0, 0, WIDTH, HEIGHT)
                    g.composite = AlphaComposite.SrcOver
                    g.drawImage(art, (WIDTH - 400) / 2, 110, 400, 400, null)
                }
                var y = 560
                y = text(g, request.song.title, title, Color.WHITE, null, y) + 6
                y = text(g, request.song.artist, artist, Color(255, 255, 255, 200), null, y) + 56
                frame.previous?.let { y = text(g, it, aside, Color(255, 255, 255, 120), null, y) + 24 }
                frame.current?.let { line ->
                    val lit = (line.length * frame.sung).roundToInt().coerceIn(0, line.length)
                    y = text(g, line, current, Color.WHITE, lit, y) + 24
                }
                frame.next?.let { text(g, it, aside, Color(255, 255, 255, 120), null, y) }
                g.font = brand
                g.color = Color(255, 255, 255, 150)
                val width = g.fontMetrics.stringWidth("Cosmotune")
                g.drawString("Cosmotune", (WIDTH - width) / 2, HEIGHT - 48)
            } finally {
                g.dispose()
            }
        }

        /** Wraps [value] to the frame, at most four rows; with [lit], only that many characters keep [color]. Answers the bottom. */
        private fun text(g: Graphics2D, value: String, font: Font, color: Color, lit: Int?, top: Int): Int {
            g.font = font
            val metrics = g.fontMetrics
            val rows = ArrayList<Pair<Int, Int>>()
            var start = 0
            while (start < value.length && rows.size < 4) {
                var end = start
                var lastBreak = -1
                while (end < value.length && metrics.stringWidth(value.substring(start, end + 1)) <= WIDTH - side * 2) {
                    if (value[end] == ' ') lastBreak = end
                    end++
                }
                if (end < value.length && lastBreak > start) end = lastBreak + 1
                if (end == start) end = start + 1
                rows += start to end
                start = end
            }
            var y = top
            val dim = Color(color.red, color.green, color.blue, 110)
            for ((from, to) in rows) {
                y += metrics.ascent
                val row = value.substring(from, to)
                if (lit == null) {
                    g.color = color
                    g.drawString(row, side, y)
                } else {
                    val split = (lit - from).coerceIn(0, row.length)
                    g.color = color
                    g.drawString(row.substring(0, split), side, y)
                    g.color = dim
                    g.drawString(row.substring(split), side + metrics.stringWidth(row.substring(0, split)), y)
                }
                y += metrics.descent + metrics.leading
            }
            return y
        }
    }
}
