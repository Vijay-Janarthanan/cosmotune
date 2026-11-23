package com.cosmotune.video.downloads

import com.cosmotune.video.model.Chapter
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.util.Locale

/** What goes inside a downloaded file for other players: the words, the chapters, one subtitle track and the cover picture. */
class EmbedDetails(
    val title: String,
    val channel: String? = null,
    val description: String? = null,
    /** The upload day as "2024-05-01". */
    val date: String? = null,
    val url: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val subtitles: EmbedSubtitles? = null,
    /** A JPEG or PNG picture. */
    val cover: ByteArray? = null,
)

/** A subtitle track to put inside the file: its cues and its language tag ("en"). */
class EmbedSubtitles(val cues: List<VttCue>, val language: String)

/**
 * Puts [EmbedDetails] inside an MP4 or M4A file without touching its pictures or sound, on the phone and the desktop alike:
 * iTunes-style tags in `moov/udta/meta/ilst` (title, channel, upload day, description, address, cover), the chapters as a
 * Nero `chpl` box (what VLC, mpv and FFmpeg read), and the subtitles as a 3GPP timed-text (`tx3g`) track whose samples go in a
 * new `mdat` at the end. The file is streamed, only `moov` is held in memory. Growing `moov` moves every byte after it, so the
 * chunk offsets (`stco`/`co64`) that point past it are moved by the same amount; fragmented files (`moof`) address their data
 * from each fragment and need no change, but get no subtitle track. Any layout it does not know answers false and the
 * caller keeps the file as it was.
 */
object Mp4Embed {
    private const val MAX_MOOV = 64L shl 20

    /** Writes [input] with [details] inside to [output]; true when it was written. */
    fun embed(input: File, output: File, details: EmbedDetails): Boolean = try {
        output.delete()
        RandomAccessFile(input, "r").use { write(it, output, details) }
        RandomAccessFile(output, "r").use { check(top(it).any { box -> box.type == "moov" }) }
        true
    } catch (_: Exception) {
        output.delete()
        false
    }

    /** Whether [file] starts like an MP4 (an `ftyp` box first). */
    fun isMp4(file: File): Boolean = try {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(8)
            raf.length() >= 8 && raf.read(head) == 8 && String(head, 4, 4, Charsets.ISO_8859_1) == "ftyp"
        }
    } catch (_: Exception) {
        false
    }

    internal class Box(val type: String, val offset: Long, val header: Int, val size: Long) {
        val end: Long get() = offset + size
        val content: Long get() = offset + header
    }

    internal fun top(raf: RandomAccessFile): List<Box> {
        val length = raf.length()
        val out = ArrayList<Box>()
        val head = ByteArray(16)
        var at = 0L
        while (at < length) {
            require(length - at >= 8) { "a stray tail" }
            raf.seek(at)
            raf.readFully(head, 0, 8)
            var size = u32(head, 0)
            var header = 8
            if (size == 1L) {
                raf.readFully(head, 8, 8)
                size = u64(head, 8)
                header = 16
            } else if (size == 0L) {
                size = length - at
            }
            require(size >= header && at + size <= length) { "a box that does not fit" }
            out += Box(String(head, 4, 4, Charsets.ISO_8859_1), at, header, size)
            at += size
        }
        return out
    }

    internal fun children(b: ByteArray, start: Int, end: Int): List<Box> {
        val out = ArrayList<Box>()
        var at = start
        while (at + 8 <= end) {
            var size = u32(b, at)
            var header = 8
            if (size == 1L) { size = u64(b, at + 8); header = 16 } else if (size == 0L) size = (end - at).toLong()
            require(size >= header && at + size <= end) { "a child box that does not fit" }
            out += Box(String(b, at + 4, 4, Charsets.ISO_8859_1), at.toLong(), header, size)
            at += size.toInt()
        }
        return out
    }

    private fun child(b: ByteArray, parent: Box, type: String): Box? =
        children(b, parent.content.toInt(), parent.end.toInt()).firstOrNull { it.type == type }

    private fun write(raf: RandomAccessFile, output: File, details: EmbedDetails) {
        val boxes = top(raf)
        val moovBox = boxes.singleOrNull { it.type == "moov" } ?: error("no moov")
        require(moovBox.size <= MAX_MOOV) { "a moov too large" }
        val fragmented = boxes.any { it.type == "moof" }
        if (fragmented) {
            // data addressed from the start of the file would move: refuse it, as any fragment or index before moov
            require(boxes.none { (it.type == "moof" || it.type == "sidx") && it.offset < moovBox.offset }) { "fragments before moov" }
            boxes.filter { it.type == "moof" }.forEach { require(!absoluteFragment(raf, it)) { "a fragment with an absolute base" } }
        }
        val old = ByteArray(moovBox.size.toInt())
        raf.seek(moovBox.offset)
        raf.readFully(old)
        val kids = children(old, moovBox.header, old.size)
        val mvhd = kids.firstOrNull { it.type == "mvhd" } ?: error("no mvhd")
        val (timescale, movieDuration) = movieTime(old, mvhd)
        val tracks = kids.filter { it.type == "trak" }
        val maxTrackId = tracks.maxOfOrNull { trackId(old, it) } ?: 0L
        val nextId = maxOf(u32(old, (mvhd.end - 4).toInt()), maxTrackId + 1)

        val samples = if (fragmented || timescale <= 0) null else details.subtitles?.let { subtitleSamples(it.cues, movieDuration * 1000 / timescale) }
        val subtitleTrack = samples?.takeIf { it.isNotEmpty() }?.let { textTrack(it, nextId, timescale, details.subtitles!!.language, raf.length() > 0xC0000000L) }

        // the new moov: the old children in order with the new track after the last one and our udta at the end
        val moov = Out()
        val oldUdta = kids.firstOrNull { it.type == "udta" }
        var subtitleAt = -1
        moov.box("moov") {
            for (kid in kids) {
                if (kid.type == "udta") continue
                bytes(old, kid.offset.toInt(), kid.size.toInt())
                if (subtitleTrack != null && kid === tracks.last()) {
                    subtitleAt = size()
                    bytes(subtitleTrack.bytes)
                }
            }
            bytes(udta(old, oldUdta, details))
        }
        val bytes = moov.toByteArray()
        val delta = bytes.size - old.size.toLong()

        // move every chunk offset that points past the old moov by the growth, and fill the new track's own offset
        val newKids = children(bytes, moovBox.header, bytes.size)
        if (delta != 0L) newKids.filter { it.type == "trak" && it.offset.toInt() != subtitleAt }.forEach { shiftOffsets(bytes, it, moovBox.offset, delta) }
        newKids.firstOrNull { it.type == "mvhd" }?.let { if (subtitleTrack != null) put32(bytes, (it.end - 4).toInt(), nextId + 1) }
        val mdatAt = raf.length() + delta
        if (subtitleTrack != null) {
            val chunk = mdatAt + 8
            val at = subtitleAt + subtitleTrack.offsetAt
            if (subtitleTrack.wide) put64(bytes, at, chunk) else { require(chunk <= 0xFFFFFFFFL); put32(bytes, at, chunk) }
        }

        FileOutputStream(output).use { stream ->
            val channel = stream.channel
            val source = raf.channel
            for (box in boxes) {
                if (box === moovBox) {
                    stream.write(bytes)
                } else {
                    var done = 0L
                    while (done < box.size) done += source.transferTo(box.offset + done, box.size - done, channel)
                }
            }
            if (samples != null && subtitleTrack != null) {
                val total = samples.sumOf { it.bytes.size.toLong() }
                stream.write(Out().apply { u32(total + 8); type("mdat") }.toByteArray())
                samples.forEach { stream.write(it.bytes) }
            }
        }
    }

    private fun absoluteFragment(raf: RandomAccessFile, moof: Box): Boolean {
        require(moof.size <= MAX_MOOV)
        val b = ByteArray(moof.size.toInt())
        raf.seek(moof.offset)
        raf.readFully(b)
        return children(b, moof.header, b.size).filter { it.type == "traf" }.any { traf ->
            val tfhd = child(b, traf, "tfhd") ?: return@any false
            (u32(b, tfhd.content.toInt()) and 0x000001L) != 0L
        }
    }

    private fun movieTime(b: ByteArray, mvhd: Box): Pair<Long, Long> {
        val at = mvhd.content.toInt()
        return if (b[at].toInt() == 1) u32(b, at + 20) to u64(b, at + 24) else u32(b, at + 12) to u32(b, at + 16)
    }

    private fun trackId(b: ByteArray, trak: Box): Long {
        val tkhd = child(b, trak, "tkhd") ?: return 0
        val at = tkhd.content.toInt()
        return if (b[at].toInt() == 1) u32(b, at + 20) else u32(b, at + 12)
    }

    private fun shiftOffsets(b: ByteArray, trak: Box, moovAt: Long, delta: Long) {
        val stbl = child(b, trak, "mdia")?.let { child(b, it, "minf") }?.let { child(b, it, "stbl") } ?: return
        child(b, stbl, "stco")?.let { box ->
            val count = u32(b, box.content.toInt() + 4).toInt()
            for (i in 0 until count) {
                val at = box.content.toInt() + 8 + i * 4
                val value = u32(b, at)
                if (value >= moovAt) {
                    val moved = value + delta
                    require(moved in 0..0xFFFFFFFFL) { "an offset past 4 GB" }
                    put32(b, at, moved)
                }
            }
        }
        child(b, stbl, "co64")?.let { box ->
            val count = u32(b, box.content.toInt() + 4).toInt()
            for (i in 0 until count) {
                val at = box.content.toInt() + 8 + i * 8
                val value = u64(b, at)
                if (value >= moovAt) put64(b, at, value + delta)
            }
        }
    }

    // ---- udta: the tags and the chapters ---------------------------------------------------------------------------------

    private fun udta(old: ByteArray, oldUdta: Box?, details: EmbedDetails): ByteArray = Out().box("udta") {
        oldUdta?.let { box ->
            children(old, box.content.toInt(), box.end.toInt()).filter { it.type != "meta" && it.type != "chpl" }
                .forEach { bytes(old, it.offset.toInt(), it.size.toInt()) }
        }
        val chapters = details.chapters.sortedBy { it.startMs }.take(255)
        if (chapters.isNotEmpty()) box("chpl") {
            u32(0x01000000L) // version 1, no flags
            u32(0)
            u8(chapters.size)
            for (chapter in chapters) {
                u64(chapter.startMs.coerceAtLeast(0) * 10_000)
                val title = utf8Within(chapter.title, 255)
                u8(title.size)
                bytes(title)
            }
        }
        box("meta") {
            u32(0)
            box("hdlr") { u32(0); u32(0); type("mdir"); type("appl"); u32(0); u32(0); u8(0) }
            box("ilst") {
                text("©nam", details.title)
                details.channel?.let { text("©ART", it) }
                details.date?.let { text("©day", it) }
                details.description?.takeIf { it.isNotBlank() }?.let { text("desc", it) }
                details.url?.let { text("©cmt", it) }
                details.cover?.let { cover ->
                    val kind = when {
                        cover.size > 3 && cover[0] == 0xFF.toByte() && cover[1] == 0xD8.toByte() -> 13
                        cover.size > 8 && cover[0] == 0x89.toByte() && cover[1] == 'P'.code.toByte() -> 14
                        else -> 0
                    }
                    if (kind != 0) box("covr") { box("data") { u32(kind.toLong()); u32(0); bytes(cover) } }
                }
            }
        }
    }.toByteArray()

    private fun Out.text(type: String, value: String) {
        if (value.isBlank()) return
        box(type) { box("data") { u32(1); u32(0); bytes(value.toByteArray(Charsets.UTF_8)) } }
    }

    private fun utf8Within(text: String, max: Int): ByteArray {
        var s = text
        while (s.toByteArray(Charsets.UTF_8).size > max) s = s.dropLast(1)
        return s.toByteArray(Charsets.UTF_8)
    }

    // ---- the subtitle track -----------------------------------------------------------------------------------------------

    internal class Sample(val bytes: ByteArray, val durationMs: Long)

    /** The cues as back-to-back samples from 0 (an empty sample fills each gap), cut at [limitMs] when it is known. */
    internal fun subtitleSamples(cues: List<VttCue>, limitMs: Long): List<Sample> {
        val out = ArrayList<Sample>()
        var at = 0L
        val limit = if (limitMs > 0) limitMs else Long.MAX_VALUE
        for (cue in cues.sortedBy { it.startMs }) {
            val start = maxOf(cue.startMs, at)
            val end = minOf(cue.endMs, limit)
            if (end <= start) continue
            if (start > at) out += Sample(byteArrayOf(0, 0), start - at)
            val text = utf8Within(cue.text, 0xFFFF)
            out += Sample(Out().apply { u16(text.size); bytes(text) }.toByteArray(), end - start)
            at = end
        }
        return out
    }

    private class Track(val bytes: ByteArray, val offsetAt: Int, val wide: Boolean)

    private fun textTrack(samples: List<Sample>, id: Long, movieTimescale: Long, language: String, wide: Boolean): Track {
        val durationMs = samples.sumOf { it.durationMs }
        val movieDuration = durationMs * movieTimescale / 1000
        var offsetAt = -1
        val out = Out()
        out.box("trak") {
            fullBox("tkhd", 0, 3) {
                u32(0); u32(0); u32(id); u32(0); u32(movieDuration)
                u32(0); u32(0)
                u16(0); u16(0); u16(0); u16(0)
                for (m in longArrayOf(0x00010000, 0, 0, 0, 0x00010000, 0, 0, 0, 0x40000000)) u32(m)
                u32(0); u32(0)
            }
            box("mdia") {
                fullBox("mdhd", 0, 0) { u32(0); u32(0); u32(1000); u32(durationMs); u16(packedLanguage(language)); u16(0) }
                fullBox("hdlr", 0, 0) { u32(0); type("sbtl"); u32(0); u32(0); u32(0); bytes("SubtitleHandler".toByteArray()); u8(0) }
                box("minf") {
                    fullBox("nmhd", 0, 0) {}
                    box("dinf") { fullBox("dref", 0, 0) { u32(1); fullBox("url ", 0, 1) {} } }
                    box("stbl") {
                        fullBox("stsd", 0, 0) {
                            u32(1)
                            box("tx3g") {
                                repeat(6) { u8(0) }
                                u16(1)
                                u32(0) // display flags
                                u8(1); u8(0xFF) // centred, at the bottom
                                u32(0) // no background
                                u16(0); u16(0); u16(0); u16(0) // the text box: the whole picture
                                u16(0); u16(0); u16(1); u8(0); u8(18); u32(0xFFFFFFFFL) // the style: font 1, 18 px, white
                                box("ftab") { u16(1); u16(1); val name = "Sans-Serif".toByteArray(); u8(name.size); bytes(name) }
                            }
                        }
                        val runs = ArrayList<Pair<Int, Long>>()
                        for (s in samples) {
                            val last = runs.lastOrNull()
                            if (last != null && last.second == s.durationMs) runs[runs.size - 1] = (last.first + 1) to last.second else runs += 1 to s.durationMs
                        }
                        fullBox("stts", 0, 0) { u32(runs.size.toLong()); runs.forEach { (count, delta) -> u32(count.toLong()); u32(delta) } }
                        fullBox("stsc", 0, 0) { u32(1); u32(1); u32(samples.size.toLong()); u32(1) }
                        fullBox("stsz", 0, 0) { u32(0); u32(samples.size.toLong()); samples.forEach { u32(it.bytes.size.toLong()) } }
                        fullBox(if (wide) "co64" else "stco", 0, 0) {
                            u32(1)
                            offsetAt = size()
                            if (wide) u64(0) else u32(0)
                        }
                    }
                }
            }
        }
        return Track(out.toByteArray(), offsetAt, wide)
    }

    /** ISO 639-2/T in three 5-bit letters ("en" is "eng"); "und" when the tag has none. */
    internal fun packedLanguage(tag: String): Int {
        val iso = try { Locale.forLanguageTag(tag).isO3Language } catch (_: Exception) { "" }
        val three = iso.lowercase().takeIf { it.length == 3 && it.all { c -> c in 'a'..'z' } } ?: "und"
        return three.fold(0) { acc, c -> (acc shl 5) or (c - 0x60).code }
    }

    // ---- bytes ------------------------------------------------------------------------------------------------------------

    private class Out : ByteArrayOutputStream() {
        fun u8(v: Int) = write(v and 0xFF)
        fun u16(v: Int) { write((v shr 8) and 0xFF); write(v and 0xFF) }
        fun u32(v: Long) { for (s in intArrayOf(24, 16, 8, 0)) write(((v shr s) and 0xFF).toInt()) }
        fun u64(v: Long) { u32(v ushr 32); u32(v and 0xFFFFFFFFL) }
        fun type(t: String) = write(t.toByteArray(Charsets.ISO_8859_1))
        fun bytes(b: ByteArray) = write(b)
        fun bytes(b: ByteArray, from: Int, length: Int) = write(b, from, length)

        /** A box of [type] around what [body] writes; its size is filled in once the body is known. */
        fun box(type: String, body: Out.() -> Unit): Out {
            val start = count
            u32(0)
            type(type)
            body()
            val size = (count - start).toLong()
            for ((i, s) in intArrayOf(24, 16, 8, 0).withIndex()) buf[start + i] = ((size shr s) and 0xFF).toByte()
            return this
        }

        fun fullBox(type: String, version: Int, flags: Int, body: Out.() -> Unit) = box(type) {
            u32((version.toLong() shl 24) or flags.toLong())
            body()
        }
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)

    private fun put32(b: ByteArray, at: Int, v: Long) { for ((i, s) in intArrayOf(24, 16, 8, 0).withIndex()) b[at + i] = ((v shr s) and 0xFF).toByte() }

    private fun put64(b: ByteArray, at: Int, v: Long) { put32(b, at, v ushr 32); put32(b, at + 4, v and 0xFFFFFFFFL) }
}
