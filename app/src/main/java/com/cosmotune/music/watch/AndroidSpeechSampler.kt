package com.cosmotune.music.watch

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import com.cosmotune.watch.PcmClip
import com.cosmotune.watch.WatchSpeechSampler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.nio.ByteOrder

/**
 * Sync automatically's ears on phones and TVs: the platform's own extractor and decoder read a stretch of the copy's sound
 * and mix it to one channel. A codec the device has no decoder for (DTS on most phones) gives null.
 */
internal class AndroidSpeechSampler(private val context: Context) : WatchSpeechSampler {
    override suspend fun sample(url: String, headers: Map<String, String>, fromMs: Long, lengthMs: Long): PcmClip? = withContext(Dispatchers.IO) {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            runCatching {
                if (url.startsWith("http")) extractor.setDataSource(url, headers) else extractor.setDataSource(context, Uri.parse(url), headers)
            }.getOrElse { return@withContext null }
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
                ?: return@withContext null
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            extractor.seekTo(fromMs * 1000, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val decoder = runCatching { MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!) }.getOrNull() ?: return@withContext null
            codec = decoder
            decoder.configure(format, null, null, 0)
            decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var out = FloatArray(0)
            var filled = 0
            var wanted = 0
            var startUs = -1L
            var inputDone = false
            val info = MediaCodec.BufferInfo()
            val deadline = System.currentTimeMillis() + 120_000
            while (System.currentTimeMillis() < deadline) {
                ensureActive()
                if (!inputDone) {
                    val index = decoder.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = decoder.getInputBuffer(index)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) { decoder.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(index, 0, size, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = decoder.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    decoder.outputFormat.let { rate = it.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = it.getInteger(MediaFormat.KEY_CHANNEL_COUNT) }
                } else if (index >= 0) {
                    val pcm = decoder.getOutputBuffer(index)!!.order(ByteOrder.nativeOrder()).asShortBuffer()
                    // What lies before the wanted start (the seek lands on the sync frame before it) is dropped.
                    if (info.size > 0 && info.presentationTimeUs >= fromMs * 1000 - 20_000) {
                        if (startUs < 0) { startUs = info.presentationTimeUs; wanted = (rate.toLong() * lengthMs / 1000).toInt(); out = FloatArray(wanted) }
                        val frames = info.size / 2 / channels
                        var i = 0
                        while (i < frames && filled < wanted) {
                            var sum = 0f
                            for (c in 0 until channels) sum += pcm.get(i * channels + c) / 32768f
                            out[filled++] = sum / channels
                            i++
                        }
                    }
                    decoder.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                    if (wanted > 0 && filled >= wanted) break
                }
            }
            if (startUs < 0 || filled < rate * 20) null else PcmClip(startUs / 1000, rate, out.copyOf(filled))
        } catch (_: Exception) {
            null
        } finally {
            runCatching { codec?.stop() }
            runCatching { codec?.release() }
            extractor.release()
        }
    }
}
