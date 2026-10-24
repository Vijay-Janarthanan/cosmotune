package com.cosmotune.listening

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Ogg Vorbis/Opus comment rewriting, written for this project from the Ogg (RFC 3533), Vorbis I and
 * Opus (RFC 7845) specifications. Only the comment header packet changes: the header pages after the
 * identification page are re-paginated, and later pages of the same logical stream are renumbered
 * with a fresh CRC. Their payloads, segment tables and granule positions stay byte-identical.
 */
internal object OggTags {
    private const val MAX_HEADER = 16 * 1024 * 1024

    private class Page(val offset: Long, val raw: ByteArray) {
        val headerType get() = raw[5].toInt() and 0xFF
        val serial get() = ByteBuffer.wrap(raw, 14, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val seq get() = ByteBuffer.wrap(raw, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
        val segmentCount get() = raw[26].toInt() and 0xFF
        fun lacing(i: Int) = raw[27 + i].toInt() and 0xFF
        val dataStart get() = 27 + segmentCount
    }

    class Layout(val codec: String, val serial: Int, val identPage: ByteArray, val packets: List<ByteArray>,
        val headerPages: Int, val audioOffset: Long)

    private fun readPage(raf: RandomAccessFile): Page? {
        val at = raf.filePointer
        if (at + 27 > raf.length()) return null
        val head = ByteArray(27).also(raf::readFully)
        if (String(head, 0, 4, Charsets.ISO_8859_1) != "OggS" || head[4].toInt() != 0) throw TagEditUnsupported("This Ogg file is damaged.")
        val count = head[26].toInt() and 0xFF
        val table = ByteArray(count).also(raf::readFully)
        val size = table.sumOf { it.toInt() and 0xFF }
        if (raf.filePointer + size > raf.length()) throw TagEditUnsupported("This Ogg file is damaged.")
        val data = ByteArray(size).also(raf::readFully)
        return Page(at, head + table + data)
    }

    fun layout(file: File): Layout = RandomAccessFile(file, "r").use { raf ->
        val first = readPage(raf) ?: throw TagEditUnsupported("This Ogg file is damaged.")
        if (first.headerType and 0x02 == 0) throw TagEditUnsupported("This Ogg file is damaged.")
        val serial = first.serial
        val ident = first.raw.copyOfRange(first.dataStart, first.raw.size)
        val codec = when {
            ident.size >= 7 && ident[0].toInt() == 1 && String(ident, 1, 6, Charsets.ISO_8859_1) == "vorbis" -> "vorbis"
            ident.size >= 8 && String(ident, 0, 8, Charsets.ISO_8859_1) == "OpusHead" -> "opus"
            else -> throw TagEditUnsupported("Tag editing supports Ogg Vorbis and Opus files.")
        }
        if (first.segmentCount == 0 || first.lacing(first.segmentCount - 1) == 255) throw TagEditUnsupported("This Ogg layout is not supported.")
        val needed = if (codec == "vorbis") 2 else 1 // after the identification packet
        val packets = mutableListOf<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        var pages = 0
        var total = 0L
        while (packets.size < needed) {
            val page = readPage(raf) ?: throw TagEditUnsupported("This Ogg file is damaged.")
            if (page.serial != serial) throw TagEditUnsupported("Ogg files with more than one stream are not edited.")
            pages++
            total += page.raw.size
            if (total > MAX_HEADER) throw TagEditUnsupported("This Ogg tag is too large.")
            var at = page.dataStart
            for (i in 0 until page.segmentCount) {
                val n = page.lacing(i)
                current.write(page.raw, at, n); at += n
                if (n < 255) {
                    packets += current.toByteArray(); current = java.io.ByteArrayOutputStream()
                    if (packets.size == needed && i != page.segmentCount - 1) throw TagEditUnsupported("This Ogg layout is not supported.")
                }
            }
        }
        if (current.size() > 0) throw TagEditUnsupported("This Ogg layout is not supported.")
        Layout(codec, serial, first.raw, packets, pages, raf.filePointer)
    }

    private fun prefix(codec: String) = if (codec == "vorbis") 7 else 8

    /** Comments as `KEY=value` strings, plus the vendor string and any bytes after them (Vorbis framing bit, Opus extra data). */
    fun comments(layout: Layout): Triple<ByteArray, List<String>, ByteArray> {
        val packet = layout.packets[0]
        val expected = if (layout.codec == "vorbis") byteArrayOf(3) + "vorbis".toByteArray() else "OpusTags".toByteArray()
        if (packet.size < prefix(layout.codec) + 8 || !packet.copyOfRange(0, expected.size).contentEquals(expected)) throw TagEditUnsupported("This Ogg file is damaged.")
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(prefix(layout.codec))
        fun bytes(): ByteArray { val n = buffer.int; if (n < 0 || n > buffer.remaining()) throw TagEditUnsupported("This Ogg file is damaged."); return ByteArray(n).also(buffer::get) }
        val vendor = bytes()
        val count = buffer.int
        if (count < 0 || count > 100_000) throw TagEditUnsupported("This Ogg file is damaged.")
        val comments = List(count) { String(bytes(), Charsets.UTF_8) }
        val tail = ByteArray(buffer.remaining()).also(buffer::get)
        return Triple(vendor, comments, tail)
    }

    fun read(file: File): List<String> = comments(layout(file)).second

    fun rewrite(source: File, target: File, edits: List<Pair<TagField, String>>) {
        val layout = layout(source)
        val (vendor, comments, tail) = comments(layout)
        val edited = edits.map { it.first.vorbis }.toSet()
        val kept = comments.filterNot { it.substringBefore('=').uppercase(java.util.Locale.ROOT) in edited } +
            edits.filter { it.second.isNotEmpty() }.map { "${it.first.vorbis}=${it.second}" }
        val encoded = kept.map { it.toByteArray(Charsets.UTF_8) }
        val head = if (layout.codec == "vorbis") byteArrayOf(3) + "vorbis".toByteArray() else "OpusTags".toByteArray()
        val body = ByteBuffer.allocate(head.size + 8 + vendor.size + encoded.sumOf { 4 + it.size } + tail.size).order(ByteOrder.LITTLE_ENDIAN)
        body.put(head).putInt(vendor.size).put(vendor).putInt(encoded.size); encoded.forEach { body.putInt(it.size).put(it) }; body.put(tail)
        if (body.capacity() > MAX_HEADER) throw TagEditUnsupported("Tags are too large.")
        val headerPages = paginate(listOf(body.array()) + layout.packets.drop(1), layout.serial, firstSeq = 1)
        val delta = headerPages.size - layout.headerPages
        java.io.BufferedOutputStream(java.io.FileOutputStream(target)).use { out ->
            out.write(layout.identPage)
            headerPages.forEach(out::write)
            RandomAccessFile(source, "r").use { raf ->
                raf.seek(layout.audioOffset)
                while (true) {
                    val page = readPage(raf) ?: break
                    if (delta == 0 || page.serial != layout.serial) { out.write(page.raw); continue }
                    val raw = page.raw.copyOf()
                    ByteBuffer.wrap(raw, 18, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(page.seq + delta)
                    seal(raw)
                    out.write(raw)
                }
                if (raf.filePointer != raf.length()) throw TagEditUnsupported("This Ogg file is damaged.")
            }
        }
    }

    /** Payloads, segment tables, granules and serials of every page after the headers: what must not change. */
    fun audioDigest(file: File): ByteArray {
        val layout = layout(file)
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            raf.seek(layout.audioOffset)
            while (true) {
                val page = readPage(raf) ?: break
                digest.update(page.raw, 5, 13) // header type, granule, serial
                digest.update(page.raw, 26, page.raw.size - 26)
            }
        }
        return digest.digest()
    }

    private fun paginate(packets: List<ByteArray>, serial: Int, firstSeq: Int): List<ByteArray> {
        val lacing = mutableListOf<Int>()
        val ends = mutableListOf<Boolean>() // whether this lacing value ends a packet
        packets.forEach { packet ->
            var left = packet.size
            while (left >= 255) { lacing += 255; ends += false; left -= 255 }
            lacing += left; ends += true
        }
        val data = packets.fold(ByteArray(0)) { acc, p -> acc + p }
        val pages = mutableListOf<ByteArray>()
        var index = 0; var at = 0; var seq = firstSeq; var continued = false
        while (index < lacing.size) {
            val count = minOf(255, lacing.size - index)
            val segs = lacing.subList(index, index + count)
            val size = segs.sum()
            val finishes = ends.subList(index, index + count).any { it }
            val raw = ByteBuffer.allocate(27 + count + size).order(ByteOrder.LITTLE_ENDIAN)
            raw.put("OggS".toByteArray()).put(0).put(if (continued) 1 else 0)
                .putLong(if (finishes) 0L else -1L).putInt(serial).putInt(seq++).putInt(0).put(count.toByte())
            segs.forEach { raw.put(it.toByte()) }
            raw.put(data, at, size)
            pages += raw.array().also(::seal)
            continued = !ends[index + count - 1]
            index += count; at += size
        }
        return pages
    }

    private val table = IntArray(256) { i ->
        var r = i shl 24
        repeat(8) { r = if (r and 0x80000000.toInt() != 0) (r shl 1) xor 0x04C11DB7 else r shl 1 }
        r
    }

    /** Writes the Ogg CRC (polynomial 0x04C11DB7, no reflection) into a page whose checksum field is cleared here. */
    private fun seal(page: ByteArray) {
        for (i in 22..25) page[i] = 0
        var crc = 0
        for (b in page) crc = (crc shl 8) xor table[((crc ushr 24) xor (b.toInt() and 0xFF)) and 0xFF]
        ByteBuffer.wrap(page, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(crc)
    }
}

/**
 * MP4/M4A iTunes-style metadata (`moov/udta/meta/ilst`), written for this project from ISO/IEC 14496-12
 * and the published iTunes item list layout. Other items and boxes are kept. When `moov` precedes the
 * media data, a following `free` box absorbs growth where it can; otherwise every `stco`/`co64` chunk
 * offset is moved by the size change. Fragmented files are refused.
 */
internal object Mp4Tags {
    private const val MAX_MOOV = 64 * 1024 * 1024
    private val CONTAINERS = setOf("moov", "trak", "mdia", "minf", "stbl", "udta", "ilst", "meta")

    private class Box(val type: String, var payload: ByteArray = ByteArray(0), var children: MutableList<Box>? = null, val full: ByteArray? = null,
        /** A few trailing bytes some writers leave after a container's last child (QuickTime's zero terminator). */
        val tail: ByteArray = ByteArray(0)) {
        fun size(): Long = 8L + (full?.size ?: 0) + (children?.sumOf { it.size() }?.plus(tail.size) ?: payload.size.toLong())
        fun write(out: java.io.OutputStream) {
            val size = size()
            if (size > Int.MAX_VALUE) throw TagEditUnsupported("This MP4 file is too large to edit.")
            out.write(ByteBuffer.allocate(4).putInt(size.toInt()).array()); out.write(type.toByteArray(Charsets.ISO_8859_1))
            full?.let(out::write)
            children?.let { list -> list.forEach { it.write(out) }; out.write(tail) } ?: out.write(payload)
        }
        fun child(name: String) = children?.firstOrNull { it.type == name }
    }

    private class Top(val type: String, val offset: Long, val size: Long)

    private fun tops(raf: RandomAccessFile): List<Top> {
        val list = mutableListOf<Top>()
        var at = 0L
        while (at + 8 <= raf.length()) {
            raf.seek(at)
            var size = raf.readInt().toLong() and 0xFFFFFFFFL
            val type = ByteArray(4).also(raf::readFully).toString(Charsets.ISO_8859_1)
            if (size == 1L) size = raf.readLong() else if (size == 0L) size = raf.length() - at
            if (size < 8 || at + size > raf.length()) throw TagEditUnsupported("This MP4 file is damaged.")
            list += Top(type, at, size); at += size
        }
        if (at != raf.length()) throw TagEditUnsupported("This MP4 file is damaged.")
        return list
    }

    private fun parse(bytes: ByteArray, from: Int, to: Int): MutableList<Box> = parseWithTail(bytes, from, to).first

    private fun container(type: String, bytes: ByteArray, from: Int, to: Int, full: ByteArray?): Box {
        val (children, tail) = parseWithTail(bytes, from, to)
        return Box(type, children = children, full = full, tail = tail)
    }

    private fun parseWithTail(bytes: ByteArray, from: Int, to: Int): Pair<MutableList<Box>, ByteArray> {
        val boxes = mutableListOf<Box>()
        var at = from
        while (at + 8 <= to) {
            val size = ByteBuffer.wrap(bytes, at, 4).int
            val type = String(bytes, at + 4, 4, Charsets.ISO_8859_1)
            if (size < 8 || at + size > to) throw TagEditUnsupported("This MP4 file's metadata is damaged.")
            boxes += if (type in CONTAINERS) {
                // `meta` is a full box in MP4 files; QuickTime writes it without the version field.
                val full = if (type == "meta" && size >= 16 && String(bytes, at + 12, 4, Charsets.ISO_8859_1) != "hdlr") bytes.copyOfRange(at + 8, at + 12) else null
                container(type, bytes, at + 8 + (full?.size ?: 0), at + size, full)
            } else Box(type, bytes.copyOfRange(at + 8, at + size))
            at += size
        }
        val tail = bytes.copyOfRange(at, to)
        if (tail.isNotEmpty() && (tail.size >= 8 || tail.any { it != 0.toByte() })) throw TagEditUnsupported("This MP4 file's metadata is damaged.")
        return boxes to tail
    }

    private class Parsed(val tops: List<Top>, val moov: Box, val moovTop: Top)

    private fun parsed(file: File): Parsed = RandomAccessFile(file, "r").use { raf ->
        val tops = tops(raf)
        if (tops.firstOrNull()?.type != "ftyp") throw TagEditUnsupported("Tag editing supports MP4/M4A audio files.")
        if (tops.any { it.type == "moof" }) throw TagEditUnsupported("Fragmented MP4 files are not edited.")
        val moovTop = tops.singleOrNull { it.type == "moov" } ?: throw TagEditUnsupported("This MP4 file is damaged.")
        if (moovTop.size > MAX_MOOV) throw TagEditUnsupported("This MP4 file's metadata is too large.")
        raf.seek(moovTop.offset)
        val bytes = ByteArray(moovTop.size.toInt()).also(raf::readFully)
        if (ByteBuffer.wrap(bytes, 0, 4).int == 1) throw TagEditUnsupported("This MP4 layout is not supported.")
        Parsed(tops, container("moov", bytes, 8, bytes.size, null), moovTop)
    }

    private val atoms = mapOf(TagField.TITLE to "©nam", TagField.ARTIST to "©ART", TagField.ALBUM to "©alb",
        TagField.ALBUM_ARTIST to "aART", TagField.GENRE to "©gen", TagField.TRACK to "trkn", TagField.YEAR to "©day")

    private fun ilst(moov: Box) = moov.child("udta")?.child("meta")?.child("ilst")

    fun read(file: File): Map<String, String> {
        val items = ilst(parsed(file).moov)?.children.orEmpty()
        return atoms.mapNotNull { (field, atom) ->
            val data = items.firstOrNull { it.type == atom }?.let { item -> parse(item.payload, 0, item.payload.size).firstOrNull { it.type == "data" } }
                ?: return@mapNotNull null
            val value = data.payload.copyOfRange(8, data.payload.size)
            field.name to if (field == TagField.TRACK) {
                if (value.size < 6) return@mapNotNull null
                val number = ByteBuffer.wrap(value, 2, 2).short.toInt() and 0xFFFF
                val total = ByteBuffer.wrap(value, 4, 2).short.toInt() and 0xFFFF
                if (total > 0) "$number/$total" else "$number"
            } else String(value, Charsets.UTF_8)
        }.toMap()
    }

    private fun item(field: TagField, value: String): Box {
        val atom = atoms.getValue(field)
        val (type, bytes) = if (field == TagField.TRACK) {
            val parts = value.split('/').map { it.trim() }
            val number = parts[0].toIntOrNull()?.takeIf { it in 0..65535 } ?: throw IllegalArgumentException("Track must be a number, like 3 or 3/12.")
            val total = parts.getOrNull(1)?.let { it.toIntOrNull()?.takeIf { t -> t in 0..65535 } ?: throw IllegalArgumentException("Track must be a number, like 3 or 3/12.") } ?: 0
            0 to ByteBuffer.allocate(8).putShort(0).putShort(number.toShort()).putShort(total.toShort()).putShort(0).array()
        } else 1 to value.toByteArray(Charsets.UTF_8)
        val data = Box("data", ByteBuffer.allocate(8).putInt(type).putInt(0).array() + bytes)
        val out = java.io.ByteArrayOutputStream(); data.write(out)
        return Box(atom, out.toByteArray())
    }

    fun rewrite(source: File, target: File, edits: List<Pair<TagField, String>>) {
        val parsed = parsed(source)
        val moov = parsed.moov
        val udta = moov.child("udta") ?: Box("udta", children = mutableListOf()).also { moov.children!!.add(it) }
        val meta = udta.child("meta") ?: Box("meta", children = mutableListOf(
            Box("hdlr", ByteArray(4) + ByteArray(4) + "mdirappl".toByteArray() + ByteArray(9)),
        ), full = ByteArray(4)).also { udta.children!!.add(it) }
        val ilst = meta.child("ilst") ?: Box("ilst", children = mutableListOf()).also { meta.children!!.add(it) }
        val edited = edits.map { atoms.getValue(it.first) }.toSet() + if (edits.any { it.first == TagField.GENRE }) setOf("gnre") else emptySet()
        ilst.children = (ilst.children!!.filterNot { it.type in edited } + edits.filter { it.second.isNotEmpty() }.map { (f, v) -> item(f, v) }).toMutableList()

        val oldEnd = parsed.moovTop.offset + parsed.moovTop.size
        var delta = moov.size() - parsed.moovTop.size
        val mediaAfter = parsed.tops.any { it.type == "mdat" && it.offset >= oldEnd }
        // A `free` box right after moov can absorb the change, so no chunk offset moves.
        val next = parsed.tops.getOrNull(parsed.tops.indexOf(parsed.tops.first { it.type == "moov" }) + 1)
        var shrinkFree: Top? = null
        if (mediaAfter && delta != 0L && next != null && next.type == "free" && next.size - delta >= 8 && next.size - delta <= Int.MAX_VALUE) {
            shrinkFree = next; delta = 0
        }
        if (mediaAfter && delta != 0L) shiftChunkOffsets(moov, oldEnd, delta)
        java.io.BufferedOutputStream(java.io.FileOutputStream(target)).use { out ->
            RandomAccessFile(source, "r").use { raf ->
                parsed.tops.forEach { top ->
                    when {
                        top.type == "moov" -> moov.write(out)
                        top === shrinkFree -> {
                            val size = top.size - (moov.size() - parsed.moovTop.size)
                            out.write(ByteBuffer.allocate(8).putInt(size.toInt()).put("free".toByteArray()).array()); out.write(ByteArray((size - 8).toInt()))
                        }
                        else -> { raf.seek(top.offset); copy(raf, out, top.size) }
                    }
                }
            }
        }
    }

    private fun shiftChunkOffsets(moov: Box, after: Long, delta: Long) {
        moov.children!!.filter { it.type == "trak" }.forEach { trak ->
            val stbl = trak.child("mdia")?.child("minf")?.child("stbl") ?: return@forEach
            stbl.children!!.filter { it.type == "stco" || it.type == "co64" }.forEach { box ->
                val buffer = ByteBuffer.wrap(box.payload)
                val count = buffer.getInt(4)
                val width = if (box.type == "stco") 4 else 8
                if (8L + count.toLong() * width != box.payload.size.toLong()) throw TagEditUnsupported("This MP4 file is damaged.")
                for (i in 0 until count) {
                    val at = 8 + i * width
                    val old = if (width == 4) buffer.getInt(at).toLong() and 0xFFFFFFFFL else buffer.getLong(at)
                    if (old < after) continue
                    val moved = old + delta
                    if (width == 4) { if (moved > 0xFFFFFFFFL) throw TagEditUnsupported("This MP4 file is too large to edit."); buffer.putInt(at, moved.toInt()) }
                    else buffer.putLong(at, moved)
                }
            }
        }
    }

    /** The media bytes each track's first chunk points at, plus every `mdat` payload: what must not change. */
    fun audioDigest(file: File): ByteArray {
        val parsed = parsed(file)
        val digest = MessageDigest.getInstance("SHA-256")
        RandomAccessFile(file, "r").use { raf ->
            parsed.tops.filter { it.type == "mdat" }.forEach { top -> raf.seek(top.offset); copy(raf, DigestSink(digest), top.size, skip = 8) }
            parsed.moov.children!!.filter { it.type == "trak" }.forEach { trak ->
                val box = trak.child("mdia")?.child("minf")?.child("stbl")?.children?.firstOrNull { it.type == "stco" || it.type == "co64" } ?: return@forEach
                val buffer = ByteBuffer.wrap(box.payload)
                if (buffer.getInt(4) == 0) return@forEach
                val first = if (box.type == "stco") buffer.getInt(8).toLong() and 0xFFFFFFFFL else buffer.getLong(8)
                if (first >= raf.length()) throw TagEditUnsupported("This MP4 file is damaged.")
                raf.seek(first); digest.update(ByteArray(minOf(64L, raf.length() - first).toInt()).also(raf::readFully))
            }
        }
        return digest.digest()
    }

    private class DigestSink(val digest: MessageDigest) : java.io.OutputStream() {
        override fun write(b: Int) = digest.update(b.toByte())
        override fun write(b: ByteArray, off: Int, len: Int) = digest.update(b, off, len)
    }

    private fun copy(raf: RandomAccessFile, out: java.io.OutputStream, size: Long, skip: Long = 0) {
        raf.seek(raf.filePointer + skip)
        var left = size - skip
        val buffer = ByteArray(64 * 1024)
        while (left > 0) {
            val n = raf.read(buffer, 0, minOf(buffer.size.toLong(), left).toInt())
            if (n < 0) throw TagEditUnsupported("This MP4 file is damaged.")
            out.write(buffer, 0, n); left -= n
        }
    }
}
