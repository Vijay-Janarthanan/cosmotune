package com.opencanvas.core.sync

/**
 * Random access to the bytes of a media file, wherever they live: an HTTP server answering range
 * requests, a local file or an in-memory array.
 */
interface RangeReader {
    /** Size of the whole file in bytes, or `-1` when unknown. */
    val length: Long

    /** Up to [count] bytes starting at [from]; fewer (possibly none) when the file ends first. */
    suspend fun read(from: Long, count: Int): ByteArray
}

/**
 * The audio frames of an MP4 file: the compressed size of each frame in bytes and how long one frame
 * lasts. An AAC encoder spends bits where the music is loud and busy, so this sequence follows the
 * music closely enough to line two encodings of the same song up with each other (see
 * [FrameEnvelope]) without decoding a single sample.
 *
 * @property videoKbps Average bitrate of the file's video track in kbit/s, `0.0` when it has none or
 *   the header does not say. A still image with a soundtrack (an "audio" or art-track upload) needs
 *   a few kbit/s; a real music video needs a hundred or more.
 * @property videoMotion Median over mean of the video frame sizes, `1.0` when unknown. A poster or a
 *   slideshow with a soundtrack costs almost nothing between its key frames, so the median frame is a
 *   sliver of the mean (a few percent); moving pictures keep their frames of comparable size.
 */
class AudioFrames(
    val sizes: IntArray,
    val frameSeconds: Double,
    val videoKbps: Double = 0.0,
    val videoMotion: Double = 1.0,
    val keyIrregularity: Double = 0.0,
)

/**
 * Reads [AudioFrames] from the headers of an MP4 file, with the fewest bytes the layout allows.
 *
 * * A progressive file (the muxed 360p stream of YouTube is one) keeps its sample tables in the
 *   `moov` box at the start, so a few hundred kilobytes describe the whole song.
 * * A fragmented file (DASH audio-only streams) spreads the sizes over `moof` boxes through the
 *   file, so it is read whole.
 * * Whatever precedes `moov` (a large `mdat`, when the file was written moov-last) is skipped with
 *   one more range read.
 */
object Mp4FrameSizes {

    private const val MAX_MOOV_BYTES = 16 * 1024 * 1024L
    private const val MAX_FILE_BYTES = 64 * 1024 * 1024L

    /** The audio frames of the first audio track, or null when there is none or the layout is unsupported. */
    suspend fun read(reader: RangeReader): AudioFrames? = try {
        readHeaders(reader)
    } catch (_: IndexOutOfBoundsException) {
        null // a damaged or truncated box
    }

    private class Track(val id: Long, val timescale: Long, val sizes: IntArray?, val frameSeconds: Double, val bytes: Long = 0L, val seconds: Double = 0.0, val motion: Double = 1.0, val irregularity: Double = 0.0)

    /** What `moov` says about the first audio track and the average bitrate of the first video track. */
    private class Moov(val audio: Track, val videoKbps: Double, val videoMotion: Double, val irregularity: Double, val defaults: Defaults)

    private suspend fun readHeaders(reader: RangeReader): AudioFrames? {
        var position = 0L
        val limit = if (reader.length >= 0L) reader.length else Long.MAX_VALUE
        while (position + 8L <= limit) {
            val header = reader.read(position, 16)
            if (header.size < 8) return null
            var size = u32(header, 0)
            var headerLength = 8
            if (size == 1L) {
                if (header.size < 16) return null
                size = u64(header, 8)
                headerLength = 16
            } else if (size == 0L) {
                size = limit - position
            }
            if (size < headerLength) return null
            if (type(header, 4) == "moov") {
                val bodyLength = size - headerLength
                if (bodyLength > MAX_MOOV_BYTES) return null
                val body = reader.read(position + headerLength, bodyLength.toInt())
                if (body.size < bodyLength) return null
                val moov = parseMoov(body) ?: return null
                val sizes = moov.audio.sizes
                return if (sizes != null && sizes.isNotEmpty()) {
                    AudioFrames(sizes, moov.audio.frameSeconds, moov.videoKbps, moov.videoMotion, moov.irregularity)
                } else {
                    readFragments(reader, moov.audio, moov.defaults)
                }
            }
            position += size
        }
        return null
    }

    /** Defaults a `trex` box declares per track id: `(sample duration, sample size)`. */
    private class Defaults(val duration: Map<Long, Long>, val size: Map<Long, Long>)

    private fun parseMoov(moov: ByteArray): Moov? {
        var audio: Track? = null
        var videoKbps = 0.0
        var videoMotion = 1.0
        var irregularity = 0.0
        val durations = HashMap<Long, Long>()
        val sizes = HashMap<Long, Long>()
        for (box in children(moov, 0, moov.size)) {
            when (box.type) {
                "trak" -> {
                    val (track, isVideo) = parseTrak(moov, box.start, box.end)
                    if (track != null && audio == null && !isVideo) audio = track
                    if (track != null && isVideo && videoKbps == 0.0 && track.seconds > 0.0) videoKbps = track.bytes * 8.0 / 1000.0 / track.seconds
                    if (track != null && isVideo && videoMotion == 1.0) { videoMotion = track.motion; irregularity = track.irregularity }
                }
                "mvex" -> for (trex in children(moov, box.start, box.end)) {
                    if (trex.type == "trex") {
                        val id = u32(moov, trex.start + 4)
                        durations[id] = u32(moov, trex.start + 12)
                        sizes[id] = u32(moov, trex.start + 16)
                    }
                }
            }
        }
        return audio?.let { Moov(it, videoKbps, videoMotion, irregularity, Defaults(durations, sizes)) }
    }

    /** The audio or video track in `data[start, end)` (null for any other kind), and whether it is video. */
    private fun parseTrak(data: ByteArray, start: Int, end: Int): Pair<Track?, Boolean> {
        var id = 0L
        var timescale = 0L
        var isAudio = false
        var isVideo = false
        var sizes: IntArray? = null
        var bytes = 0L
        var motion = 1.0
        var irregularity = 0.0
        var totalDuration = 0L
        var totalSamples = 0L
        for (box in children(data, start, end)) {
            when (box.type) {
                "tkhd" -> id = if (data[box.start].toInt() == 1) u32(data, box.start + 20) else u32(data, box.start + 12)
                "mdia" -> for (inner in children(data, box.start, box.end)) {
                    when (inner.type) {
                        "mdhd" -> timescale = if (data[inner.start].toInt() == 1) u32(data, inner.start + 20) else u32(data, inner.start + 12)
                        "hdlr" -> {
                            isAudio = type(data, inner.start + 8) == "soun"
                            isVideo = type(data, inner.start + 8) == "vide"
                        }
                        "minf" -> for (stbl in children(data, inner.start, inner.end)) {
                            if (stbl.type != "stbl") continue
                            for (table in children(data, stbl.start, stbl.end)) {
                                when (table.type) {
                                    "stsz" -> {
                                        sizes = if (isAudio) readStsz(data, table.start) else null
                                        bytes = sumStsz(data, table.start)
                                        if (isVideo) motion = motionOf(data, table.start)
                                    }
                                    "stss" -> irregularity = irregularityOf(data, table.start)
                                    "stts" -> {
                                        val entries = u32(data, table.start + 4).toInt()
                                        for (i in 0 until entries) {
                                            val count = u32(data, table.start + 8 + 8 * i)
                                            totalSamples += count
                                            totalDuration += count * u32(data, table.start + 12 + 8 * i)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        if ((!isAudio && !isVideo) || timescale <= 0L) return null to false
        val frameSeconds = if (totalSamples > 0L) totalDuration.toDouble() / totalSamples / timescale else 0.0
        val track = Track(id, timescale, if (isAudio) sizes else null, frameSeconds, bytes, totalDuration.toDouble() / timescale, motion, irregularity)
        return track to isVideo
    }

    /** Share of the gaps between key frames (from a `stss` box) that differ from the most common gap; 0.0 when it cannot tell. */
    private fun irregularityOf(data: ByteArray, start: Int): Double {
        val count = u32(data, start + 4).toInt()
        if (count < 6) return 0.0
        val gaps = IntArray(count - 1) { (u32(data, start + 12 + 4 * it) - u32(data, start + 8 + 4 * it)).toInt() }
        val modal = gaps.toList().groupingBy { it }.eachCount().maxByOrNull { it.value }!!.key
        return gaps.count { kotlin.math.abs(it - modal) > 1 }.toDouble() / gaps.size
    }

    /** Median over mean of the sample sizes a `stsz` box lists (see [AudioFrames.videoMotion]); 1.0 when it cannot tell. */
    private fun motionOf(data: ByteArray, start: Int): Double {
        val count = u32(data, start + 8).toInt()
        if (u32(data, start + 4) != 0L || count < 60) return 1.0
        val sizes = LongArray(count) { u32(data, start + 12 + 4 * it) }
        val mean = sizes.average()
        if (mean <= 0.0) return 1.0
        sizes.sort()
        return sizes[count / 2] / mean
    }

    /** Total size in bytes of all samples a `stsz` box lists. */
    private fun sumStsz(data: ByteArray, start: Int): Long {
        val fixed = u32(data, start + 4)
        val count = u32(data, start + 8)
        if (fixed != 0L) return fixed * count
        var total = 0L
        for (i in 0 until count.toInt()) total += u32(data, start + 12 + 4 * i)
        return total
    }

    private fun readStsz(data: ByteArray, start: Int): IntArray? {
        val fixed = u32(data, start + 4)
        val count = u32(data, start + 8).toInt()
        if (fixed != 0L || count <= 0) return null // a fixed sample size is raw audio, not a coded stream
        return IntArray(count) { u32(data, start + 12 + 4 * it).toInt() }
    }

    private suspend fun readFragments(reader: RangeReader, track: Track, defaults: Defaults): AudioFrames? {
        if (reader.length <= 0L || reader.length > MAX_FILE_BYTES) return null
        val file = reader.read(0, reader.length.toInt())
        val sizes = ArrayList<Int>()
        var frameSeconds = defaults.duration[track.id]?.takeIf { it > 0L }?.let { it.toDouble() / track.timescale } ?: 0.0
        for (moof in children(file, 0, file.size)) {
            if (moof.type != "moof") continue
            for (traf in children(file, moof.start, moof.end)) {
                if (traf.type != "traf") continue
                var defaultSize = defaults.size[track.id] ?: 0L
                var defaultDuration = 0L
                var forThisTrack = false
                for (part in children(file, traf.start, traf.end)) {
                    when (part.type) {
                        "tfhd" -> {
                            val flags = u32(file, part.start) and 0xFFFFFFL
                            forThisTrack = u32(file, part.start + 4) == track.id
                            var at = part.start + 8
                            if (flags and 0x1L != 0L) at += 8
                            if (flags and 0x2L != 0L) at += 4
                            if (flags and 0x8L != 0L) { defaultDuration = u32(file, at); at += 4 }
                            if (flags and 0x10L != 0L) defaultSize = u32(file, at)
                        }
                        "trun" -> if (forThisTrack) {
                            val flags = u32(file, part.start) and 0xFFFFFFL
                            val count = u32(file, part.start + 4).toInt()
                            var at = part.start + 8
                            if (flags and 0x1L != 0L) at += 4
                            if (flags and 0x4L != 0L) at += 4
                            for (i in 0 until count) {
                                var duration = defaultDuration
                                var size = defaultSize
                                if (flags and 0x100L != 0L) { duration = u32(file, at); at += 4 }
                                if (flags and 0x200L != 0L) { size = u32(file, at); at += 4 }
                                if (flags and 0x400L != 0L) at += 4
                                if (flags and 0x800L != 0L) at += 4
                                sizes += size.toInt()
                                if (frameSeconds == 0.0 && duration > 0L) frameSeconds = duration.toDouble() / track.timescale
                            }
                        }
                    }
                }
            }
        }
        return if (sizes.isEmpty() || frameSeconds <= 0.0) null else AudioFrames(sizes.toIntArray(), frameSeconds)
    }

    private class Box(val type: String, val start: Int, val end: Int)

    /** The boxes stored back to back in `data[from, to)`; [Box.start] is where a box's payload begins. */
    private fun children(data: ByteArray, from: Int, to: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = from
        while (at + 8 <= to) {
            var size = u32(data, at)
            var headerLength = 8
            if (size == 1L) {
                size = u64(data, at + 8)
                headerLength = 16
            } else if (size == 0L) {
                size = (to - at).toLong()
            }
            if (size < headerLength || at + size > to) {
                // a box cut off by the end of the data: keep what is usable up to it
                if (size >= headerLength) out += Box(type(data, at + 4), at + headerLength, to)
                break
            }
            out += Box(type(data, at + 4), at + headerLength, (at + size).toInt())
            at += size.toInt()
        }
        return out
    }

    private fun u32(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)

    private fun type(data: ByteArray, at: Int): String =
        CharArray(4) { (data[at + it].toInt() and 0xFF).toChar() }.concatToString()
}
