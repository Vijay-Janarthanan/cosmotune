package com.cosmotune.listening

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri
import com.cosmotune.audio.LocalPcmDecoder
import com.cosmotune.audio.LocalPcmStream
import com.cosmotune.music.data.model.Song
import java.io.File
import java.nio.ByteOrder

/**
 * Library analysis through the platform MediaCodec decoders, for files and content-provider
 * grants alike. Output is read as 16-bit or float PCM, whichever the decoder produces.
 */
class PhonePcmDecoder(context: Context) : LocalPcmDecoder {
    private val app = context.applicationContext

    override fun open(song: Song, file: LocalLibraryFile): LocalPcmStream {
        val extractor = MediaExtractor()
        val location = File(file.identity).takeIf { it.isAbsolute && it.isFile }
        val uri = if (location == null) Uri.parse(file.identity).takeIf { it.scheme == "content" || it.scheme == "file" }
            ?: throw UnsupportedOperationException("Only local files are analysed") else null
        try {
            if (location != null) extractor.setDataSource(location.path) else extractor.setDataSource(app, uri!!, null)
        } catch (e: Exception) { extractor.release(); throw UnsupportedOperationException("This file could not be opened") }
        val track = (0 until extractor.trackCount).firstOrNull {
            extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
        } ?: run { extractor.release(); throw UnsupportedOperationException("No audio track") }
        extractor.selectTrack(track)
        val input = extractor.getTrackFormat(track)
        val mime = input.getString(MediaFormat.KEY_MIME)!!
        val codec = try { MediaCodec.createDecoderByType(mime).apply { configure(input, null, null, 0); start() } }
        catch (e: Exception) { extractor.release(); throw UnsupportedOperationException("No decoder for $mime") }
        val genre = runCatching {
            MediaMetadataRetriever().run {
                try {
                    if (location != null) setDataSource(location.path) else setDataSource(app, uri!!)
                    extractMetadata(MediaMetadataRetriever.METADATA_KEY_GENRE)
                } finally { release() }
            }
        }.getOrNull()?.trim()?.take(80)?.takeIf { it.isNotEmpty() }
        return Stream(extractor, codec, input, mime, genre)
    }

    private class Stream(
        private val extractor: MediaExtractor, private val decoder: MediaCodec, input: MediaFormat,
        mime: String, genre: String?,
    ) : LocalPcmStream {
        override var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            private set
        override var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            private set
        override val codec = mime.substringAfter("audio/").let { if (it == "raw") "PCM" else it }
        override val bitsPerSample = if (input.containsKey("bits-per-sample")) input.getInteger("bits-per-sample") else null
        override val bitrate = if (input.containsKey(MediaFormat.KEY_BIT_RATE)) input.getInteger(MediaFormat.KEY_BIT_RATE).toLong() else null
        override val tags = listOfNotNull(genre?.let { "genre" to it }).toMap()
        override var count = 0
            private set
        private var encoding = AudioFormat.ENCODING_PCM_16BIT
        private var out = FloatArray(0)
        private var inputDone = false
        private var outputDone = false
        private val info = MediaCodec.BufferInfo()

        override fun read(): FloatArray? {
            while (!outputDone) {
                if (!inputDone) {
                    val slot = decoder.dequeueInputBuffer(10_000)
                    if (slot >= 0) {
                        val buffer = decoder.getInputBuffer(slot)!!
                        val size = extractor.readSampleData(buffer, 0)
                        if (size < 0) {
                            decoder.queueInputBuffer(slot, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true
                        } else {
                            decoder.queueInputBuffer(slot, 0, size, extractor.sampleTime, 0); extractor.advance()
                        }
                    }
                }
                val slot = decoder.dequeueOutputBuffer(info, 10_000)
                when {
                    slot == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> decoder.outputFormat.let { format ->
                        sampleRate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (format.containsKey(MediaFormat.KEY_PCM_ENCODING)) format.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    }
                    slot >= 0 -> {
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        val buffer = decoder.getOutputBuffer(slot)!!.order(ByteOrder.nativeOrder())
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val samples = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) info.size / 4 else info.size / 2
                        if (out.size < samples) out = FloatArray(samples)
                        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) buffer.asFloatBuffer().get(out, 0, samples)
                        else { val shorts = buffer.asShortBuffer(); for (i in 0 until samples) out[i] = shorts.get(i) / 32768f }
                        decoder.releaseOutputBuffer(slot, false)
                        if (samples > 0) { count = samples; return out }
                    }
                }
            }
            return null
        }

        override fun close() {
            runCatching { decoder.stop() }
            decoder.release()
            extractor.release()
        }
    }
}
