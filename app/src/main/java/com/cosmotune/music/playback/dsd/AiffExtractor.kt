package com.cosmotune.music.playback.dsd

import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.ParserException
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput
import com.cosmotune.audio.AiffFormat
import java.io.EOFException

/**
 * AIFF and AIFF-C (uncompressed) for the phone, which Media3 has no extractor for: the chunks are walked to COMM and SSND, and the
 * samples go out as `audio/raw` in the player's byte order, a tenth of a second at a time, so the ordinary audio renderer and every
 * stage after it play them like a WAV. Seeking is by frame, as the samples sit at fixed offsets.
 */
@UnstableApi
class AiffExtractor : Extractor {
    private lateinit var output: ExtractorOutput
    private var track: TrackOutput? = null
    private var format: AiffFormat? = null
    private var dataStart = 0L
    private var dataEnd = 0L
    private var buffer = ByteArray(0)

    override fun sniff(input: ExtractorInput): Boolean {
        val head = ByteArray(12)
        return try {
            input.peekFully(head, 0, 12, true) && AiffFormat.formType(head) != null
        } catch (_: EOFException) {
            false
        } finally {
            input.resetPeekPosition()
        }
    }

    override fun init(output: ExtractorOutput) { this.output = output }

    override fun seek(position: Long, timeUs: Long) = Unit

    override fun release() = Unit

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int = try {
        val f = format ?: return readHeader(input)
        if (input.position < dataStart) {
            seekPosition.position = dataStart
            return Extractor.RESULT_SEEK
        }
        val left = dataEnd - input.position
        if (left <= 0) return Extractor.RESULT_END_OF_INPUT
        val chunk = (buffer.size.toLong().coerceAtMost(left) / f.blockAlign * f.blockAlign).toInt()
        if (chunk <= 0) return Extractor.RESULT_END_OF_INPUT
        val firstFrame = (input.position - dataStart) / f.blockAlign
        if (!input.readFully(buffer, 0, chunk, true)) return Extractor.RESULT_END_OF_INPUT
        AiffFormat.toPlayerOrder(f, buffer, 0, chunk)
        val out = requireNotNull(track)
        out.sampleData(ParsableByteArray(buffer.copyOf(chunk)), chunk)
        out.sampleMetadata(firstFrame * 1_000_000L / f.sampleRate, C.BUFFER_FLAG_KEY_FRAME, chunk, 0, null)
        Extractor.RESULT_CONTINUE
    } catch (failure: EOFException) {
        throw ParserException.createForMalformedContainer("Truncated AIFF file.", failure)
    } catch (failure: IllegalArgumentException) {
        throw ParserException.createForMalformedContainer(failure.message ?: "Invalid AIFF file.", failure)
    }

    /** Walks the chunks after the FORM header to COMM and SSND, then declares the track and its seek map. */
    private fun readHeader(input: ExtractorInput): Int {
        val head = ByteArray(12)
        input.readFully(head, 0, 12)
        val form = AiffFormat.formType(head) ?: throw ParserException.createForMalformedContainer("Not an AIFF file.", null)
        var found: AiffFormat? = null
        val header = ByteArray(8)
        while (true) {
            if (!input.readFully(header, 0, 8, true)) throw ParserException.createForMalformedContainer("No audio in this AIFF file.", null)
            val id = String(header, 0, 4, Charsets.ISO_8859_1)
            val size = ((header[4].toLong() and 0xFF) shl 24) or ((header[5].toLong() and 0xFF) shl 16) or ((header[6].toLong() and 0xFF) shl 8) or (header[7].toLong() and 0xFF)
            when (id) {
                "COMM" -> {
                    require(size in 18..4096) { "Bad COMM chunk." }
                    val body = ByteArray(size.toInt())
                    input.readFully(body, 0, body.size)
                    found = AiffFormat.parseComm(body, form) ?: throw ParserException.createForUnsupportedContainerFeature("This AIFF file is compressed.")
                    if (size % 2 == 1L) input.skipFully(1)
                }
                "SSND" -> {
                    val f = found ?: throw ParserException.createForMalformedContainer("SSND before COMM.", null)
                    val offsets = ByteArray(8)
                    input.readFully(offsets, 0, 8)
                    val offset = ((offsets[0].toLong() and 0xFF) shl 24) or ((offsets[1].toLong() and 0xFF) shl 16) or ((offsets[2].toLong() and 0xFF) shl 8) or (offsets[3].toLong() and 0xFF)
                    if (offset > 0) input.skipFully(offset.toInt())
                    dataStart = input.position
                    val declared = (size - 8 - offset).coerceAtLeast(0)
                    dataEnd = dataStart + minOf(declared, f.frames * f.blockAlign).let { if (it > 0) it else declared }
                    start(f)
                    return Extractor.RESULT_CONTINUE
                }
                else -> input.skipFully((size + (size and 1L)).toInt())
            }
        }
    }

    private fun start(f: AiffFormat) {
        format = f
        buffer = ByteArray(((f.sampleRate / 10).coerceAtLeast(1) * f.blockAlign))
        val encoding = when {
            f.float -> C.ENCODING_PCM_FLOAT
            f.bits == 8 -> C.ENCODING_PCM_8BIT
            f.bits == 16 -> C.ENCODING_PCM_16BIT
            f.bits == 24 -> C.ENCODING_PCM_24BIT
            else -> C.ENCODING_PCM_32BIT
        }
        track = output.track(0, C.TRACK_TYPE_AUDIO).also { track ->
            track.format(
                Format.Builder()
                    .setSampleMimeType(MimeTypes.AUDIO_RAW)
                    .setContainerMimeType("audio/aiff")
                    .setSampleRate(f.sampleRate)
                    .setChannelCount(f.channels)
                    .setPcmEncoding(encoding)
                    .setAverageBitrate(f.sampleRate * f.blockAlign * 8)
                    .setMaxInputSize(buffer.size)
                    .build(),
            )
        }
        val frames = (dataEnd - dataStart) / f.blockAlign
        output.seekMap(object : SeekMap {
            override fun isSeekable() = true
            override fun getDurationUs() = frames * 1_000_000L / f.sampleRate
            override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                val frame = (timeUs * f.sampleRate / 1_000_000L).coerceIn(0, (frames - 1).coerceAtLeast(0))
                return SeekMap.SeekPoints(SeekPoint(frame * 1_000_000L / f.sampleRate, dataStart + frame * f.blockAlign))
            }
        })
        output.endTracks()
    }
}
