package com.cosmotune.music.ui.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaDataSource
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.text.Layout
import android.text.SpannableString
import android.text.Spanned
import android.text.StaticLayout
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import com.cosmotune.lyrics.video.LyricsVideoPlan
import com.cosmotune.music.data.Http
import com.cosmotune.music.data.innertube.StreamResolver
import com.cosmotune.music.playback.smart.AudioDecoder
import com.cosmotune.music.playback.smart.LocalAudioSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.Request
import java.io.File
import java.nio.ByteBuffer
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * A lyrics video for sharing: an MP4 (H.264 and AAC, 720×1280 at 24 frames a second) of the chosen lines over the cover,
 * each lighting up as it is sung, with that stretch of the song underneath. The frames come from the shared
 * [LyricsVideoPlan]; they are drawn with Android's canvas and encoded with MediaCodec, so nothing is uploaded anywhere.
 */
internal object LyricsVideoExport {

    private const val WIDTH = 720
    private const val HEIGHT = 1280
    private const val FPS = 24
    private const val VIDEO_BITRATE = 4_000_000
    private const val AUDIO_BITRATE = 160_000
    private const val TIMEOUT_US = 10_000L

    private class Sample(val track: Int, val data: ByteArray, val timeUs: Long, val flags: Int)

    /** Writes the video to [target]; [onProgress] goes 0..1. The audio is left out when the song cannot be read. */
    suspend fun export(context: Context, request: LyricsShareRequest, plan: LyricsVideoPlan, target: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.Default) {
            val cover = runCatching { loadCover(context, request.artworkUrl) }.getOrNull()
            val samples = ArrayList<Sample>()
            val videoFormat = encodeVideo(request, plan, cover, samples) { onProgress(it * 0.8f) }
            val audio = runCatching { decodeClip(context, request, plan) }.getOrNull()
            val audioFormat = audio?.let { (pcm, rate, channels) -> encodeAudio(pcm, rate, channels, samples) }
            onProgress(0.95f)
            val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val videoTrack = muxer.addTrack(videoFormat)
                val audioTrack = audioFormat?.let(muxer::addTrack) ?: -1
                muxer.start()
                val info = MediaCodec.BufferInfo()
                samples.sortedBy { it.timeUs }.forEach { sample ->
                    val track = if (sample.track == 0) videoTrack else audioTrack
                    if (track < 0) return@forEach
                    info.set(0, sample.data.size, sample.timeUs, sample.flags)
                    muxer.writeSampleData(track, ByteBuffer.wrap(sample.data), info)
                }
                muxer.stop()
            } finally {
                runCatching { muxer.release() }
            }
            onProgress(1f)
        }

    // ---- video ----

    private suspend fun encodeVideo(
        request: LyricsShareRequest,
        plan: LyricsVideoPlan,
        cover: Bitmap?,
        out: MutableList<Sample>,
        onProgress: (Float) -> Unit,
    ): MediaFormat {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, WIDTH, HEIGHT).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BITRATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, FPS)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        val frame = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(frame)
        val pixels = IntArray(WIDTH * HEIGHT)
        val painter = FramePainter(request, cover)
        val total = (plan.durationMs * FPS / 1000).toInt().coerceAtLeast(1)
        var outputFormat: MediaFormat? = null
        var index = 0
        var inputDone = false
        val info = MediaCodec.BufferInfo()
        try {
            while (true) {
                coroutineContext.ensureActive()
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        if (index >= total) {
                            codec.queueInputBuffer(input, 0, 0, index * 1_000_000L / FPS, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            painter.draw(canvas, plan.frameAt(index * 1_000L / FPS))
                            frame.getPixels(pixels, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
                            val image = codec.getInputImage(input) ?: error("The encoder gave no input image")
                            writeYuv(pixels, image)
                            codec.queueInputBuffer(input, 0, WIDTH * HEIGHT * 3 / 2, index * 1_000_000L / FPS, 0)
                            index++
                            onProgress(index.toFloat() / total)
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    output >= 0 -> {
                        val buffer = codec.getOutputBuffer(output)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            out += Sample(0, bytes, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } finally {
            runCatching { codec.stop() }
            codec.release()
            frame.recycle()
        }
        return outputFormat ?: error("The video encoder never described its output")
    }

    /** ARGB to the encoder's YUV 4:2:0 planes, whatever their strides (BT.601, limited range). */
    private fun writeYuv(argb: IntArray, image: android.media.Image) {
        val planes = image.planes
        val y = planes[0]; val u = planes[1]; val v = planes[2]
        val yBuffer = y.buffer; val uBuffer = u.buffer; val vBuffer = v.buffer
        val luma = ByteArray(WIDTH)
        for (row in 0 until HEIGHT) {
            val base = row * WIDTH
            for (col in 0 until WIDTH) {
                val c = argb[base + col]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                luma[col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (y.pixelStride == 1) {
                yBuffer.position(row * y.rowStride)
                yBuffer.put(luma)
            } else {
                for (col in 0 until WIDTH) yBuffer.put(row * y.rowStride + col * y.pixelStride, luma[col])
            }
        }
        for (row in 0 until HEIGHT / 2) {
            for (col in 0 until WIDTH / 2) {
                val c = argb[(row * 2) * WIDTH + col * 2]
                val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
                uBuffer.put(row * u.rowStride + col * u.pixelStride, (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte())
                vBuffer.put(row * v.rowStride + col * v.pixelStride, (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte())
            }
        }
    }

    /** Draws one frame: the cover darkened behind, the cover itself, the song, and the lines with the sung part lit. */
    private class FramePainter(private val request: LyricsShareRequest, private val cover: Bitmap?) {
        private val dim = Paint().apply { color = Color.argb(170, 0, 0, 0) }
        private val plain = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val title = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 34f; typeface = Typeface.DEFAULT_BOLD }
        private val artist = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(200, 255, 255, 255); textSize = 28f }
        private val current = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 56f; typeface = Typeface.DEFAULT_BOLD }
        private val aside = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(120, 255, 255, 255); textSize = 36f; typeface = Typeface.DEFAULT_BOLD }
        private val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(150, 255, 255, 255); textSize = 24f; textAlign = Paint.Align.CENTER }
        private val side = 64

        fun draw(canvas: Canvas, frame: LyricsVideoPlan.Frame) {
            canvas.drawColor(Color.rgb(18, 18, 22))
            cover?.let { art ->
                val scale = max(WIDTH.toFloat() / art.width, HEIGHT.toFloat() / art.height)
                val w = art.width * scale; val h = art.height * scale
                canvas.drawBitmap(art, null, RectF((WIDTH - w) / 2, (HEIGHT - h) / 2, (WIDTH + w) / 2, (HEIGHT + h) / 2), plain)
                canvas.drawRect(0f, 0f, WIDTH.toFloat(), HEIGHT.toFloat(), dim)
                val size = 400f
                val left = (WIDTH - size) / 2
                canvas.drawBitmap(art, null, RectF(left, 110f, left + size, 110f + size), plain)
            }
            var y = 560f
            y = text(canvas, request.song.title, title, y) + 6
            y = text(canvas, request.song.artist, artist, y) + 56
            frame.previous?.let { y = text(canvas, it, aside, y) + 24 }
            frame.current?.let { line ->
                val lit = (line.length * frame.sung).roundToInt().coerceIn(0, line.length)
                val spanned = SpannableString(line).apply {
                    setSpan(ForegroundColorSpan(Color.argb(110, 255, 255, 255)), lit, line.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                y = text(canvas, spanned, current, y) + 24
            }
            frame.next?.let { text(canvas, it, aside, y) }
            canvas.drawText("Cosmotune", WIDTH / 2f, HEIGHT - 48f, brand)
        }

        private fun text(canvas: Canvas, value: CharSequence, paint: TextPaint, top: Float): Float {
            val layout = StaticLayout.Builder.obtain(value, 0, value.length, paint, WIDTH - side * 2)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL).setMaxLines(4).build()
            canvas.save()
            canvas.translate(side.toFloat(), top)
            layout.draw(canvas)
            canvas.restore()
            return top + layout.height
        }
    }

    private fun loadCover(context: Context, url: String?): Bitmap? {
        url ?: return null
        val bytes = when {
            url.startsWith("http") -> Http.client.newCall(Request.Builder().url(url).build()).execute().use { it.body?.bytes() }
            else -> context.contentResolver.openInputStream(Uri.parse(url))?.use { it.readBytes() }
        } ?: return null
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    // ---- audio ----

    /** The plan's stretch of the song as interleaved 16-bit PCM, with its rate and channel count. */
    private suspend fun decodeClip(context: Context, request: LyricsShareRequest, plan: LyricsVideoPlan): Triple<ShortArray, Int, Int>? {
        val source = withContext(Dispatchers.IO) { sourceFor(context, request) } ?: return null
        val decoded = source.use {
            AudioDecoder.decodeRegionStereo(it, plan.startMs / 1000.0, plan.endMs / 1000.0)
        } ?: return null
        val (pcm, realStart) = decoded
        val rate = pcm.sampleRate.toInt()
        val skip = ((plan.startMs / 1000.0 - realStart) * rate).toInt().coerceIn(0, pcm.left.size)
        val frames = minOf(pcm.left.size - skip, (plan.durationMs * rate / 1000).toInt())
        if (frames <= 0) return null
        val out = ShortArray(frames * 2)
        for (i in 0 until frames) {
            out[2 * i] = (pcm.left[skip + i].coerceIn(-1f, 1f) * 32_767).toInt().toShort()
            out[2 * i + 1] = (pcm.right[skip + i].coerceIn(-1f, 1f) * 32_767).toInt().toShort()
        }
        return Triple(out, rate, 2)
    }

    private suspend fun sourceFor(context: Context, request: LyricsShareRequest): MediaDataSource? {
        request.song.localUri?.let { local ->
            val uri = Uri.parse(local)
            return if (LocalAudioSource.isLocal(uri)) LocalAudioSource.open(context.contentResolver, uri)
            else File(local).takeIf { it.isFile }?.let { FileSource(it) }
        }
        val stream = StreamResolver.resolveForDownload(request.song.videoId, Int.MAX_VALUE, requireM4a = true)
        return RangedHttpSource(stream.url, StreamResolver.mediaHeadersFor(stream.url))
    }

    private fun encodeAudio(pcm: ShortArray, rate: Int, channels: Int, out: MutableList<Sample>): MediaFormat? {
        val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, rate, channels).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BITRATE)
            setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
        }
        val codec = runCatching { MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC) }.getOrNull() ?: return null
        var outputFormat: MediaFormat? = null
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var at = 0
            var inputDone = false
            while (true) {
                if (!inputDone) {
                    val input = codec.dequeueInputBuffer(TIMEOUT_US)
                    if (input >= 0) {
                        val buffer = codec.getInputBuffer(input)!!
                        buffer.clear()
                        val count = minOf(buffer.capacity() / 2, pcm.size - at)
                        val timeUs = (at / channels).toLong() * 1_000_000L / rate
                        if (count <= 0) {
                            codec.queueInputBuffer(input, 0, 0, timeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            buffer.order(java.nio.ByteOrder.nativeOrder()).asShortBuffer().put(pcm, at, count)
                            codec.queueInputBuffer(input, 0, count * 2, timeUs, 0)
                            at += count
                        }
                    }
                }
                val output = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                when {
                    output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> outputFormat = codec.outputFormat
                    output >= 0 -> {
                        val buffer = codec.getOutputBuffer(output)!!
                        if (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0 && info.size > 0) {
                            val bytes = ByteArray(info.size)
                            buffer.position(info.offset)
                            buffer.get(bytes)
                            out += Sample(1, bytes, info.presentationTimeUs, info.flags)
                        }
                        codec.releaseOutputBuffer(output, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    }
                }
            }
        } catch (_: Exception) {
            out.removeAll { it.track == 1 }
            return null
        } finally {
            runCatching { codec.stop() }
            codec.release()
        }
        return outputFormat
    }

    private class FileSource(private val file: File) : MediaDataSource() {
        private val access = java.io.RandomAccessFile(file, "r")
        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (position >= access.length()) return -1
            access.seek(position)
            return access.read(buffer, offset, size)
        }
        override fun getSize(): Long = access.length()
        override fun close() = access.close()
    }

    /** A remote file read a window at a time with the minting client's headers, which googlevideo insists on. */
    private class RangedHttpSource(private val url: String, private val headers: Map<String, String>) : MediaDataSource() {
        private var windowStart = -1L
        private var window = ByteArray(0)
        private var length = -1L

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (length >= 0 && position >= length) return -1
            if (position < windowStart || position >= windowStart + window.size) fetch(position)
            if (window.isEmpty()) return -1
            val from = (position - windowStart).toInt()
            val count = minOf(size, window.size - from)
            System.arraycopy(window, from, buffer, offset, count)
            return count
        }

        override fun getSize(): Long {
            if (length < 0) fetch(0)
            return length
        }

        override fun close() = Unit

        private fun fetch(position: Long) {
            val request = Request.Builder().url(url).apply {
                headers.forEach { (name, value) -> header(name, value) }
                header("Range", "bytes=$position-${position + WINDOW - 1}")
            }.build()
            Http.client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) error("HTTP ${response.code}")
                response.header("Content-Range")?.substringAfterLast('/')?.toLongOrNull()?.let { length = it }
                window = response.body?.bytes() ?: ByteArray(0)
                windowStart = position
                if (length < 0 && response.code == 200) length = window.size.toLong()
            }
        }

        private companion object {
            const val WINDOW = 1L shl 20
        }
    }
}
