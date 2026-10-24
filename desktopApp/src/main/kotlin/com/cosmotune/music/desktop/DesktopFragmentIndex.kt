package com.cosmotune.music.desktop

import java.io.File
import java.io.OutputStream
import java.io.RandomAccessFile

/** Builds the small seek table from fragment headers; audio payloads stay on disk. */
internal object DesktopFragmentIndex {
    private data class Box(val start: Long, val size: Long, val header: Int, val type: String) { val end get() = start + size }
    private data class Fragment(val box: Box, val ticks: Long, val decodeTime: Long)
    private const val MAX_HEADER = 4 * 1024 * 1024

    fun copyIndexed(file: File, init: ByteArray, sink: OutputStream, checkCancelled: () -> Unit) {
        require(file.length() <= DesktopManifestDownload.MAX_DOWNLOAD_BYTES)
        RandomAccessFile(file, "r").use { input ->
            val boxes = mutableListOf<Box>()
            var at = 0L
            while (at < input.length()) {
                checkCancelled()
                require(boxes.size <= DesktopManifestDownload.MAX_SEGMENTS * 4) { "Too many MP4 boxes" }
                require(input.length() - at >= 8) { "Incomplete MP4 box" }
                input.seek(at)
                var size = input.readInt().toLong() and 0xffffffffL
                val type = ByteArray(4).also(input::readFully).toString(Charsets.ISO_8859_1)
                var header = 8
                if (size == 1L) { require(input.length() - at >= 16); size = input.readLong(); header = 16 }
                else if (size == 0L) size = input.length() - at
                require(size >= header && size <= input.length() - at) { "Invalid MP4 box length" }
                boxes += Box(at, size, header, type); at += size
            }
            val moov = boxes.firstOrNull { it.type == "moov" }
            val moofs = boxes.filter { it.type == "moof" }
            val initial = DesktopManifestDownload.Mp4.boxes(init)
            val initialMoov = initial.firstOrNull { it.type == "moov" }
            val singleTrack = initialMoov != null && DesktopManifestDownload.Mp4.boxes(init, initialMoov.content, initialMoov.end).count { it.type == "trak" } == 1
            val track = if (singleTrack) DesktopManifestDownload.Mp4.track(init)?.takeIf { it.timescale in 1..0xffffffffL } else null
            val index = if (track != null && moov != null && moofs.isNotEmpty() && moofs.first().start >= moov.end && boxes.none { it.type == "sidx" }) {
                require(moofs.size <= DesktopManifestDownload.MAX_SEGMENTS)
                val fragments = moofs.map { box ->
                    checkCancelled()
                    require(box.size <= MAX_HEADER) { "MP4 fragment header is too large" }
                    input.seek(box.start)
                    val bytes = ByteArray(box.size.toInt()).also(input::readFully)
                    fragment(box, bytes, track)
                }
                if (fragments.all { it != null }) table(fragments.filterNotNull(), moov.end, input.length(), track) else null
            } else null
            input.seek(0)
            val buffer = ByteArray(32 * 1024)
            fun copy(until: Long) {
                while (input.filePointer < until) {
                    checkCancelled()
                    val size = minOf(buffer.size.toLong(), until - input.filePointer).toInt()
                    input.readFully(buffer, 0, size); sink.write(buffer, 0, size)
                }
            }
            if (index != null) { copy(requireNotNull(moov).end); sink.write(index) }
            copy(input.length())
        }
    }

    private fun fragment(box: Box, bytes: ByteArray, track: DesktopManifestDownload.Mp4.Track): Fragment? {
        val root = DesktopManifestDownload.Mp4.boxes(bytes).single()
        var ticks = 0L
        var firstTime = 0L
        for (traf in DesktopManifestDownload.Mp4.boxes(bytes, root.content, root.end).filter { it.type == "traf" }) {
            val parts = DesktopManifestDownload.Mp4.boxes(bytes, traf.content, traf.end)
            val tfhd = parts.firstOrNull { it.type == "tfhd" } ?: return null
            fun read(position: Int, end: Int = bytes.size): Long {
                require(position >= 0 && position <= end - 4) { "Incomplete MP4 fragment fields" }
                return DesktopManifestDownload.Mp4.u32(bytes, position)
            }
            val flags = read(tfhd.content, tfhd.end).toInt() and 0xffffff
            if (read(tfhd.content + 4, tfhd.end) != track.id) continue
            if (flags and 1 != 0) return null // Absolute offsets cannot survive insertion of a front index.
            var position = tfhd.content + 8
            if (flags and 2 != 0) position += 4
            val defaultDuration = if (flags and 8 != 0) read(position, tfhd.end) else track.defaultDuration
            parts.firstOrNull { it.type == "tfdt" }?.let { time ->
                require(time.end - time.content >= 8)
                firstTime = if (bytes[time.content].toInt() == 1) {
                    require(time.end - time.content >= 12)
                    val high = read(time.content + 4, time.end)
                    require(high <= 0x7fffffff)
                    (high shl 32) or read(time.content + 8, time.end)
                } else read(time.content + 4, time.end)
            }
            for (run in parts.filter { it.type == "trun" }) {
                val runFlags = read(run.content, run.end).toInt() and 0xffffff
                val count = read(run.content + 4, run.end)
                require(count <= 2_000_000) { "Too many MP4 samples" }
                var cursor = run.content + 8 + (if (runFlags and 1 != 0) 4 else 0) + (if (runFlags and 4 != 0) 4 else 0)
                val stride = listOf(0x100, 0x200, 0x400, 0x800).count { runFlags and it != 0 } * 4
                require(cursor <= run.end && count * stride <= run.end - cursor) { "Incomplete MP4 sample table" }
                if (runFlags and 0x100 == 0) {
                    if (defaultDuration <= 0) return null
                    ticks = Math.addExact(ticks, Math.multiplyExact(count, defaultDuration))
                } else repeat(count.toInt()) {
                    ticks = Math.addExact(ticks, read(cursor, run.end)); cursor += stride
                }
            }
        }
        return if (ticks in 1..0xffffffffL) Fragment(box, ticks, firstTime) else null
    }

    private fun table(fragments: List<Fragment>, moovEnd: Long, length: Long, track: DesktopManifestDownload.Mp4.Track): ByteArray {
        val output = java.nio.ByteBuffer.allocate(40 + fragments.size * 12)
        output.putInt(output.capacity()); output.put("sidx".toByteArray(Charsets.ISO_8859_1)); output.putInt(0x01000000)
        output.putInt(track.id.toInt()); output.putInt(track.timescale.toInt()); output.putLong(fragments.first().decodeTime)
        output.putLong(fragments.first().box.start - moovEnd); output.putShort(0); output.putShort(fragments.size.toShort())
        fragments.forEachIndexed { index, fragment ->
            val end = fragments.getOrNull(index + 1)?.box?.start ?: length
            val size = end - fragment.box.start
            require(size in 1..0x7fffffffL) { "MP4 seek reference is too large" }
            output.putInt(size.toInt()); output.putInt(fragment.ticks.toInt()); output.putInt(0x90000000.toInt())
        }
        return output.array()
    }
}
