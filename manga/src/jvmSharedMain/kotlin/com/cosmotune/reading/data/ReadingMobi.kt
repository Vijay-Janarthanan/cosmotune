package com.cosmotune.reading.data

import com.cosmotune.reading.data.ReadingConvert.Book
import com.cosmotune.reading.data.ReadingConvert.Chapter
import com.cosmotune.reading.data.ReadingConvert.ImageSink
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import org.jsoup.Jsoup

/**
 * Kindle books without DRM: MOBI (and PRC/AZW) and KF8 (AZW3), read from their Palm database records. Text is PalmDOC or
 * HUFF/CDIC compressed; pictures are the image records. The HUFF/CDIC decoder follows calibre's (GPL-3.0, see NOTICE.md).
 */
internal object ReadingMobi {
    private class Records(val data: ByteArray) {
        val offsets: IntArray
        init {
            require(data.size >= 78 + 8) { "This is not a Kindle book." }
            val count = u16(data, 76)
            require(count in 1..65_000 && data.size >= 78 + count * 8) { "This is not a Kindle book." }
            offsets = IntArray(count) { u32(data, 78 + it * 8).toInt() }
            require(offsets.all { it in 0..data.size } && offsets.toList().zipWithNext().all { (a, b) -> a <= b }) { "This Kindle book is damaged." }
        }
        val size get() = offsets.size
        operator fun get(index: Int): ByteArray {
            require(index in 0 until size) { "This Kindle book is damaged." }
            val end = if (index + 1 < size) offsets[index + 1] else data.size
            return data.copyOfRange(offsets[index], end)
        }
    }

    private class Header(val record: ByteArray) {
        val compression = u16(record, 0)
        val textLength = u32(record, 4)
        val textRecords = u16(record, 8)
        val encryption = u16(record, 12)
        val isMobi = record.size >= 20 && String(record, 16, 4, Charsets.US_ASCII) == "MOBI"
        val headerLength = if (isMobi) u32(record, 20).toInt() else 0
        val encoding = if (isMobi) u32(record, 28).toInt() else 1252
        val version = if (isMobi && record.size >= 40) u32(record, 36).toInt() else 0
        val fullName: String = if (isMobi && record.size >= 92) {
            val offset = u32(record, 84).toInt(); val length = u32(record, 88).toInt()
            if (offset in 0..record.size && length in 0..1024 && offset + length <= record.size) String(record, offset, length, charset()) else ""
        } else ""
        val firstImage = if (isMobi && record.size >= 112) u32(record, 108).toInt() else -1
        val huffRecord = if (isMobi && record.size >= 120) u32(record, 112).toInt() else 0
        val huffCount = if (isMobi && record.size >= 120) u32(record, 116).toInt() else 0
        val hasExth = isMobi && record.size >= 132 && (u32(record, 128) and 0x40L) != 0L
        val extraFlags = if (isMobi && headerLength >= 0xE4 && record.size >= 0xF4) u16(record, 0xF2) else 0
        val fdst = if (isMobi && headerLength >= 0xB4 && record.size >= 0xC4) u32(record, 0xC0).toInt() else -1
        val exth: Map<Int, List<ByteArray>> = buildMap<Int, MutableList<ByteArray>> {
            if (!hasExth) return@buildMap
            var at = 16 + headerLength
            if (at + 12 > record.size || String(record, at, 4, Charsets.US_ASCII) != "EXTH") return@buildMap
            val count = u32(record, at + 8).toInt()
            at += 12
            repeat(count.coerceAtMost(4096)) {
                if (at + 8 > record.size) return@buildMap
                val type = u32(record, at).toInt(); val length = u32(record, at + 4).toInt()
                if (length < 8 || at + length > record.size) return@buildMap
                getOrPut(type) { ArrayList() }.add(record.copyOfRange(at + 8, at + length))
                at += length
            }
        }
        fun charset(): Charset = if (encoding == 65001) Charsets.UTF_8 else Charset.forName("windows-1252")
        fun text(type: Int) = exth[type]?.map { String(it, charset()).trim() }?.filter { it.isNotBlank() }.orEmpty()
        fun number(type: Int) = exth[type]?.firstOrNull()?.takeIf { it.size >= 4 }?.let { u32(it, 0).toInt() }
    }

    fun convert(bytes: ByteArray, fallbackTitle: String): Book {
        val records = Records(bytes)
        require(String(bytes, 60, 8, Charsets.US_ASCII) in setOf("BOOKMOBI", "TEXtREAd")) { "This is not a Kindle book Read can open." }
        val first = Header(records[0])
        require(first.encryption == 0) { "This Kindle book is protected (DRM). Open it in the Kindle app." }
        val boundary = first.number(121)?.takeIf { it in 1 until records.size }
        // A dual book keeps its old MOBI text first; prefer it (plain HTML). A KF8-only book (AZW3) is read from its KF8 part.
        val kf8Base = when {
            first.version >= 8 -> 0
            boundary != null && first.textRecords == 0 -> boundary
            else -> null
        }
        val base = kf8Base ?: 0
        val header = if (base == 0) first else Header(records[base])
        require(header.encryption == 0) { "This Kindle book is protected (DRM). Open it in the Kindle app." }
        val text = text(records, base, header)
        val title = first.text(503).firstOrNull() ?: first.fullName.ifBlank { fallbackTitle }
        val authors = first.text(100).distinct()
        val description = first.text(103).firstOrNull()?.let { Jsoup.parse(it).text() }.orEmpty()
        val language = first.text(524).firstOrNull().orEmpty()
        val images = ImageSink()
        val firstImage = header.firstImage.takeIf { it > 0 }?.let { base + it }
        fun imageRecord(index: Int): String? {
            val record = (firstImage ?: return null) + index
            if (record !in 0 until records.size) return null
            return images.add("record:$record", records[record])
        }
        val cover = first.number(201)?.let { imageRecord(it) }
        val (chapters, css) = if (kf8Base != null) kf8(records, base, header, text, ::imageRecord) else mobi6(text, header, ::imageRecord, title) to emptyList()
        return Book(title, authors, language, description, chapters, images.images, cover, css)
    }

    /** Old MOBI markup: page breaks split chapters; pictures point at image records by number. */
    private fun mobi6(text: ByteArray, header: Header, image: (Int) -> String?, title: String): List<Chapter> {
        val markup = String(text, header.charset())
        val parts = markup.split(Regex("(?i)<mbp:pagebreak[^>]*>"))
        val chapters = ArrayList<Chapter>()
        parts.forEach { part ->
            val body = Jsoup.parse(part).body()
            body.select("img[recindex]").forEach { img ->
                val name = img.attr("recindex").toIntOrNull()?.let { image(it - 1) }
                if (name == null) img.remove() else { img.attributes().asList().map { it.key }.forEach(img::removeAttr); img.attr("src", "images/$name") }
            }
            body.select("a[filepos]").forEach { it.removeAttr("filepos") }
            body.getAllElements().forEach { element -> if (element.tagName().startsWith("mbp:")) element.unwrap() }
            if (body.text().isBlank() && body.select("img").isEmpty()) return@forEach
            val heading = body.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() && it.length <= 120 }
            chapters += Chapter(heading ?: if (chapters.isEmpty()) title else "Chapter ${chapters.size + 1}", body.html())
        }
        return chapters
    }

    /** KF8 markup: the first flow is the text (later flows are its CSS); each skeleton is a chapter, pictures are kindle:embed. */
    private fun kf8(records: Records, base: Int, header: Header, text: ByteArray, image: (Int) -> String?): Pair<List<Chapter>, List<String>> {
        val flows = ArrayList<ByteArray>()
        val fdst = header.fdst.takeIf { it > 0 && it != -1 && base + it < records.size }?.let { records[base + it] }
        if (fdst != null && fdst.size >= 12 && String(fdst, 0, 4, Charsets.US_ASCII) == "FDST") {
            val count = u32(fdst, 8).toInt()
            for (index in 0 until count.coerceAtMost(4096)) {
                if (12 + index * 8 + 8 > fdst.size) break
                val start = u32(fdst, 12 + index * 8).toInt(); val end = u32(fdst, 16 + index * 8).toInt()
                if (start in 0..end && end <= text.size) flows += text.copyOfRange(start, end)
            }
        }
        if (flows.isEmpty()) flows += text
        val markup = String(flows[0], header.charset())
        val css = flows.drop(1).map { String(it, header.charset()) }.filter { !it.trimStart().startsWith("<") }
        val documents = markup.split(Regex("(?=<\\?xml)|(?=<html[\\s>])", RegexOption.IGNORE_CASE)).filter { it.isNotBlank() }
        val embed = Regex("kindle:embed:([0-9A-V]{4})", RegexOption.IGNORE_CASE)
        val chapters = ArrayList<Chapter>()
        documents.forEach { part ->
            val body = Jsoup.parse(part).body()
            body.select("img[src]").forEach { img ->
                val name = embed.find(img.attr("src"))?.groupValues?.get(1)?.toInt(32)?.let { image(it - 1) }
                if (name == null) img.remove() else { img.attributes().asList().map { it.key }.filter { it != "alt" }.forEach(img::removeAttr); img.attr("src", "images/$name") }
            }
            body.select("a[href]").forEach { link -> if (link.attr("href").startsWith("kindle:")) link.removeAttr("href") }
            if (body.text().isBlank() && body.select("img").isEmpty()) return@forEach
            val heading = body.selectFirst("h1, h2, h3")?.text()?.takeIf { it.isNotBlank() && it.length <= 120 }
            chapters += Chapter(heading ?: "Chapter ${chapters.size + 1}", body.html())
        }
        return chapters to css
    }

    private fun text(records: Records, base: Int, header: Header): ByteArray {
        val output = ByteArrayOutputStream()
        val huff = if (header.compression == 17480) Huff(records, base + header.huffRecord, header.huffCount) else null
        require(header.compression in setOf(1, 2, 17480)) { "This Kindle book uses a compression Read cannot open." }
        for (index in 1..header.textRecords) {
            if (base + index >= records.size) break
            var record = records[base + index]
            record = record.copyOf((record.size - trailing(record, header.extraFlags)).coerceAtLeast(0))
            output.write(when (header.compression) { 2 -> palmDoc(record); 17480 -> huff!!.unpack(record, 0); else -> record })
            require(output.size() <= 256 * 1024 * 1024) { "This Kindle book expands beyond the size limit." }
        }
        val all = output.toByteArray()
        return if (header.textLength in 1..all.size.toLong()) all.copyOf(header.textLength.toInt()) else all
    }

    /** The bytes a text record carries after its text (indexing data), by the header's extra-data flags. */
    internal fun trailing(record: ByteArray, flags: Int): Int {
        var size = 0
        var bits = flags shr 1
        while (bits != 0) {
            if (bits and 1 != 0) {
                // A backward variable-width number at the current end.
                var value = 0; var shift = 0; var at = record.size - size
                while (at > 0) {
                    val byte = record[at - 1].toInt() and 0xFF
                    value = value or ((byte and 0x7F) shl shift)
                    shift += 7; at--
                    if (byte and 0x80 != 0 || shift >= 28) break
                }
                size += value
            }
            bits = bits shr 1
        }
        if (flags and 1 != 0 && record.size - size - 1 >= 0) size += (record[record.size - size - 1].toInt() and 0x3) + 1
        return size.coerceIn(0, record.size)
    }

    /** PalmDOC's LZ77 variant. */
    internal fun palmDoc(data: ByteArray): ByteArray {
        var out = ByteArray(maxOf(16, data.size * 3))
        var size = 0
        fun put(byte: Byte) { if (size == out.size) out = out.copyOf(out.size * 2); out[size++] = byte }
        var i = 0
        while (i < data.size) {
            val c = data[i++].toInt() and 0xFF
            when {
                c in 1..8 -> repeat(c) { if (i < data.size) put(data[i++]) }
                c < 0x80 -> put(c.toByte())
                c >= 0xC0 -> { put(' '.code.toByte()); put((c xor 0x80).toByte()) }
                else -> {
                    if (i >= data.size) break
                    val pair = (c shl 8) or (data[i++].toInt() and 0xFF)
                    val distance = (pair shr 3) and 0x7FF
                    val length = (pair and 7) + 3
                    if (distance == 0 || distance > size) continue
                    repeat(length) { put(out[size - distance]) }
                }
            }
        }
        return out.copyOf(size)
    }

    /** HUFF/CDIC decompression (Mobipocket's dictionary compression), after calibre's HuffReader. */
    private class Huff(records: Records, start: Int, count: Int) {
        private val dict1 = LongArray(256 * 3)
        private val minCode = LongArray(33)
        private val maxCode = LongArray(33)
        private val dictionary = ArrayList<ByteArray?>()
        private val literal = ArrayList<Boolean>()
        init {
            require(count >= 2 && start + count <= records.size) { "This Kindle book is damaged." }
            val huff = records[start]
            require(huff.size >= 24 && String(huff, 0, 4, Charsets.US_ASCII) == "HUFF") { "This Kindle book is damaged." }
            val off1 = u32(huff, 8).toInt(); val off2 = u32(huff, 12).toInt()
            require(off1 + 1024 <= huff.size && off2 + 256 <= huff.size) { "This Kindle book is damaged." }
            for (i in 0 until 256) {
                val v = u32(huff, off1 + i * 4)
                val length = (v and 0x1F); val term = (v and 0x80); var max = v ushr 8
                max = (((max + 1) shl (32 - length.toInt())) - 1) and 0xFFFFFFFFL
                dict1[i * 3] = length; dict1[i * 3 + 1] = term; dict1[i * 3 + 2] = max
            }
            for (length in 1..32) {
                minCode[length] = (u32(huff, off2 + (length - 1) * 8) shl (32 - length)) and 0xFFFFFFFFL
                maxCode[length] = (((u32(huff, off2 + (length - 1) * 8 + 4) + 1) shl (32 - length)) - 1) and 0xFFFFFFFFL
            }
            for (index in start + 1 until start + count) {
                val cdic = records[index]
                require(cdic.size >= 16 && String(cdic, 0, 4, Charsets.US_ASCII) == "CDIC") { "This Kindle book is damaged." }
                val phrases = u32(cdic, 8).toInt(); val bits = u32(cdic, 12).toInt()
                val n = minOf(1 shl bits.coerceIn(0, 20), phrases - dictionary.size)
                for (j in 0 until n.coerceAtLeast(0)) {
                    if (16 + j * 2 + 2 > cdic.size) break
                    val offset = u16(cdic, 16 + j * 2)
                    if (16 + offset + 2 > cdic.size) { dictionary += ByteArray(0); literal += true; continue }
                    val blen = u16(cdic, 16 + offset)
                    val length = blen and 0x7FFF
                    val from = 18 + offset
                    dictionary += cdic.copyOfRange(from, minOf(cdic.size, from + length))
                    literal += (blen and 0x8000) != 0
                }
            }
        }
        fun unpack(input: ByteArray, depth: Int): ByteArray {
            require(depth < 32) { "This Kindle book is damaged." }
            val data = input + ByteArray(8)
            var bitsLeft = input.size.toLong() * 8
            var pos = 0
            var x = u64(data, pos)
            var n = 32
            val out = ByteArrayOutputStream()
            while (true) {
                if (n <= 0) { pos += 4; if (pos + 8 > data.size) break; x = u64(data, pos); n += 32 }
                val code = (x ushr n) and 0xFFFFFFFFL
                val entry = (code ushr 24).toInt()
                var length = dict1[entry * 3].toInt()
                var max = dict1[entry * 3 + 2]
                if (dict1[entry * 3 + 1] == 0L) {
                    while (length < 32 && code < minCode[length]) length++
                    max = maxCode[length]
                }
                if (length == 0) break
                n -= length
                bitsLeft -= length
                if (bitsLeft < 0) break
                val index = ((max - code) ushr (32 - length)).toInt()
                if (index !in dictionary.indices) break
                var slice = dictionary[index] ?: break
                if (!literal[index]) {
                    dictionary[index] = null
                    slice = unpack(slice, depth + 1)
                    dictionary[index] = slice; literal[index] = true
                }
                out.write(slice)
            }
            return out.toByteArray()
        }
    }

    private fun u16(data: ByteArray, at: Int): Int = if (at + 2 > data.size) 0 else ((data[at].toInt() and 0xFF) shl 8) or (data[at + 1].toInt() and 0xFF)
    private fun u32(data: ByteArray, at: Int): Long = if (at + 4 > data.size) 0 else
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)
    private fun u64(data: ByteArray, at: Int): Long = (u32(data, at) shl 32) or u32(data, at + 4)
}
