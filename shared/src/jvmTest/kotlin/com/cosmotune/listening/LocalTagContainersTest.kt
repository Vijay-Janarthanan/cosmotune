package com.cosmotune.listening

import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

/** Ogg Vorbis/Opus and MP4/M4A tag writing against hand-built files whose audio must come back byte-identical. */
class LocalTagContainersTest {
    @get:Rule val temporary = TemporaryFolder()

    // ---- Ogg ----
    /** A bit-at-a-time Ogg CRC, deliberately unlike the table-driven one under test. */
    private fun oggCrc(page: ByteArray): Int {
        var crc = 0L
        page.forEachIndexed { i, raw ->
            val b = if (i in 22..25) 0 else raw.toInt() and 0xFF
            crc = crc xor (b.toLong() shl 24)
            repeat(8) { crc = if (crc and 0x80000000L != 0L) ((crc shl 1) xor 0x04C11DB7L) and 0xFFFFFFFFL else (crc shl 1) and 0xFFFFFFFFL }
        }
        return crc.toInt()
    }

    private fun page(type: Int, granule: Long, seq: Int, packets: List<ByteArray>): ByteArray {
        val lacing = packets.flatMap { p -> List(p.size / 255) { 255 } + (p.size % 255) }
        require(lacing.size <= 255)
        val data = packets.fold(ByteArray(0)) { a, p -> a + p }
        val raw = ByteBuffer.allocate(27 + lacing.size + data.size).order(ByteOrder.LITTLE_ENDIAN)
        raw.put("OggS".toByteArray()).put(0).put(type.toByte()).putLong(granule).putInt(0x1234).putInt(seq).putInt(0).put(lacing.size.toByte())
        lacing.forEach { raw.put(it.toByte()) }; raw.put(data)
        val bytes = raw.array()
        ByteBuffer.wrap(bytes, 22, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(oggCrc(bytes))
        return bytes
    }

    private fun comments(head: ByteArray, vararg values: String, tail: ByteArray): ByteArray {
        val encoded = values.map { it.toByteArray() }
        val vendor = "libopus 1.4".toByteArray()
        val b = ByteBuffer.allocate(head.size + 8 + vendor.size + encoded.sumOf { 4 + it.size } + tail.size).order(ByteOrder.LITTLE_ENDIAN)
        b.put(head).putInt(vendor.size).put(vendor).putInt(encoded.size); encoded.forEach { b.putInt(it.size).put(it) }; b.put(tail)
        return b.array()
    }

    private fun audioPages(firstSeq: Int) = (0 until 4).map { i ->
        page(if (i == 3) 4 else 0, 960L * (i + 1), firstSeq + i, listOf(ByteArray(300 + i) { (it * 7 + i).toByte() }, ByteArray(40) { 1 }))
    }

    private fun pages(file: File): List<ByteArray> = RandomAccessFile(file, "r").use { raf ->
        buildList {
            while (raf.filePointer < raf.length()) {
                val head = ByteArray(27).also(raf::readFully)
                val table = ByteArray(head[26].toInt() and 0xFF).also(raf::readFully)
                add(head + table + ByteArray(table.sumOf { it.toInt() and 0xFF }).also(raf::readFully))
            }
        }
    }

    private fun seq(page: ByteArray) = ByteBuffer.wrap(page, 18, 4).order(ByteOrder.LITTLE_ENDIAN).int
    private fun payload(page: ByteArray) = page.copyOfRange(5, 18) + page.copyOfRange(26, page.size)

    @Test fun opusTagsGrowPastAPageAndLaterPagesAreRenumberedWithValidChecksums() {
        val head = "OpusHead".toByteArray() + byteArrayOf(1, 2, 0x38, 1, 0x80.toByte(), 0xBB.toByte(), 0, 0, 0, 0, 0)
        // A large existing comment (a picture) brings the tag packet close to one page's 65,025 byte limit.
        val picture = "METADATA_BLOCK_PICTURE=" + "A".repeat(64_900)
        val tags = comments("OpusTags".toByteArray(), "TITLE=Old", "ARTIST=Someone", picture, tail = byteArrayOf(0))
        val file = temporary.newFile("song.opus")
        file.writeBytes(page(2, 0, 0, listOf(head)) + page(0, 0, 1, listOf(tags)) + audioPages(2).fold(ByteArray(0)) { a, p -> a + p })
        val before = pages(file)

        LocalTagEditor.write(file, LocalTags(title = "Kannalane", album = "Bombay " + "x".repeat(900)))
        val read = LocalTagEditor.read(file)
        assertEquals("Kannalane", read["TITLE"]); assertEquals("Someone", read["ARTIST"]); assertTrue(read["ALBUM"]!!.startsWith("Bombay"))
        val after = pages(file)
        assertEquals(before.size + 1, after.size) // the tags now take two pages
        assertContentEquals(before[0], after[0]) // the identification page is untouched
        after.forEachIndexed { i, p ->
            assertEquals(i, seq(p), "page sequence")
            assertEquals(oggCrc(p), ByteBuffer.wrap(p, 22, 4).order(ByteOrder.LITTLE_ENDIAN).int, "page $i checksum")
        }
        // Audio pages: same payloads, segment tables, granules and flags.
        before.drop(2).zip(after.drop(3)).forEach { (a, b) -> assertContentEquals(payload(a), payload(b)) }
        // The picture and the trailing byte survive.
        assertTrue(OggTags.read(file).any { it.startsWith("METADATA_BLOCK_PICTURE=") && it.length == picture.length })
    }

    @Test fun vorbisCommentAndSetupSharingAPageAreRewrittenInPlace() {
        val ident = byteArrayOf(1) + "vorbis".toByteArray() + ByteArray(23) { 2 }
        val tags = comments(byteArrayOf(3) + "vorbis".toByteArray(), "title=Old", "GENRE=Film", tail = byteArrayOf(1))
        val setup = byteArrayOf(5) + "vorbis".toByteArray() + ByteArray(600) { 4 }
        val file = temporary.newFile("song.ogg")
        file.writeBytes(page(2, 0, 0, listOf(ident)) + page(0, 0, 1, listOf(tags, setup)) + audioPages(2).fold(ByteArray(0)) { a, p -> a + p })
        val before = pages(file)
        LocalTagEditor.write(file, LocalTags(title = "Humma", genre = ""))
        assertEquals(mapOf("TITLE" to "Humma"), LocalTagEditor.read(file)) // the lower-case key was replaced, the genre removed
        val after = pages(file)
        assertEquals(before.size, after.size)
        // No page count change: the audio pages are copied byte for byte.
        before.drop(2).zip(after.drop(2)).forEach { (a, b) -> assertContentEquals(a, b) }
        val layout = OggTags.layout(file)
        assertContentEquals(setup, layout.packets[1]) // the setup header is untouched
    }

    @Test fun multiplexedOrDamagedOggIsRefusedAndLeftAlone() {
        val head = "OpusHead".toByteArray() + ByteArray(11)
        val other = page(2, 0, 0, listOf("OpusHead".toByteArray() + ByteArray(11))).also { ByteBuffer.wrap(it, 14, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(99) }
        val file = temporary.newFile("muxed.opus")
        file.writeBytes(page(2, 0, 0, listOf(head)) + other)
        val bytes = file.readBytes()
        assertFailsWith<TagEditUnsupported> { LocalTagEditor.write(file, LocalTags(title = "x")) }
        assertContentEquals(bytes, file.readBytes())
    }

    // ---- MP4 ----
    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val body = parts.fold(ByteArray(0)) { a, p -> a + p }
        return ByteBuffer.allocate(8).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).array() + body
    }

    private fun textItem(atom: String, value: String) =
        box(atom, box("data", ByteBuffer.allocate(8).putInt(1).putInt(0).array(), value.toByteArray()))

    private val media = ByteArray(4000) { (it * 13 % 241).toByte() }

    /** ftyp, then moov (offsets pointing into mdat), optionally a free box, then mdat; or mdat before moov. */
    private fun mp4(file: File, moovFirst: Boolean, free: Int = 0, extraItems: ByteArray = ByteArray(0)) {
        val ftyp = box("ftyp", "M4A ".toByteArray(), ByteArray(4), "isomM4A ".toByteArray())
        fun moov(chunkOffsets: List<Long>) = box("moov", box("mvhd", ByteArray(100) { 1 }),
            box("trak", box("tkhd", ByteArray(84) { 2 }), box("mdia", box("mdhd", ByteArray(24)), box("minf", box("stbl",
                box("stsd", ByteArray(40) { 3 }),
                box("stco", ByteBuffer.allocate(8 + 4 * chunkOffsets.size).putInt(0).putInt(chunkOffsets.size).apply { chunkOffsets.forEach { putInt(it.toInt()) } }.array()))))),
            box("udta", box("meta", ByteArray(4), box("hdlr", ByteArray(8), "mdirappl".toByteArray(), ByteArray(9)),
                box("ilst", textItem("©nam", "Old title"), box("gnre", box("data", ByteArray(8), byteArrayOf(0, 18))), extraItems))))
        val freeBox = if (free > 0) box("free", ByteArray(free - 8)) else ByteArray(0)
        val mdat = box("mdat", media)
        val layout = if (moovFirst) {
            val placeholder = moov(listOf(0, 0))
            val start = (ftyp.size + placeholder.size + freeBox.size + 8).toLong()
            ftyp + moov(listOf(start, start + 2000)) + freeBox + mdat
        } else {
            val start = (ftyp.size + 8).toLong()
            ftyp + mdat + moov(listOf(start, start + 2000))
        }
        file.writeBytes(layout)
    }

    /** The media bytes each chunk offset points at, read through the file's own stco. */
    private fun chunks(file: File): List<ByteArray> {
        val bytes = file.readBytes()
        val at = (0..bytes.size - 4).first { String(bytes, it, 4, Charsets.ISO_8859_1) == "stco" }
        val buffer = ByteBuffer.wrap(bytes, at + 4, bytes.size - at - 4)
        buffer.int; val count = buffer.int
        return List(count) { val offset = buffer.int; bytes.copyOfRange(offset, offset + 100) }
    }

    @Test fun mp4WithMoovBeforeMediaMovesChunkOffsetsAndKeepsOtherItems() {
        val file = temporary.newFile("song.m4a")
        mp4(file, moovFirst = true, extraItems = box("covr", box("data", ByteBuffer.allocate(8).putInt(13).putInt(0).array(), ByteArray(300) { 9 })))
        val before = chunks(file)
        assertContentEquals(media.copyOfRange(0, 100), before[0])
        LocalTagEditor.write(file, LocalTags(title = "Kannalane", artist = "A. R. Rahman", track = "3/12", year = "1995", genre = "Soundtrack"))
        val read = LocalTagEditor.read(file)
        assertEquals("Kannalane", read["TITLE"]); assertEquals("A. R. Rahman", read["ARTIST"]); assertEquals("3/12", read["TRACK"])
        assertEquals("1995", read["YEAR"]); assertEquals("Soundtrack", read["GENRE"])
        before.zip(chunks(file)).forEach { (a, b) -> assertContentEquals(a, b) }
        val text = file.readBytes().toString(Charsets.ISO_8859_1)
        assertTrue("covr" in text); assertFalse("gnre" in text) // the numeric genre is replaced by the text one
        assertFailsWith<IllegalArgumentException> { LocalTagEditor.write(file, LocalTags(track = "three")) }
    }

    @Test fun aFreeBoxAfterMoovAbsorbsGrowthAndMoovAfterMediaNeedsNoOffsets() {
        val padded = temporary.newFile("padded.m4a")
        mp4(padded, moovFirst = true, free = 2048)
        val size = padded.length()
        val offsets = chunks(padded)
        LocalTagEditor.write(padded, LocalTags(album = "Bombay"))
        assertEquals(size, padded.length()) // grown into the padding
        offsets.zip(chunks(padded)).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals("Bombay", LocalTagEditor.read(padded)["ALBUM"])

        val trailing = temporary.newFile("trailing.m4a")
        mp4(trailing, moovFirst = false)
        val media = chunks(trailing)
        LocalTagEditor.write(trailing, LocalTags(title = "Humma", albumArtist = "Various"))
        media.zip(chunks(trailing)).forEach { (a, b) -> assertContentEquals(a, b) }
        assertEquals("Various", LocalTagEditor.read(trailing)["ALBUM_ARTIST"])
    }

    @Test fun fragmentedMp4IsRefused() {
        val file = temporary.newFile("frag.mp4")
        file.writeBytes(box("ftyp", "isom".toByteArray(), ByteArray(4)) + box("moov", box("mvhd", ByteArray(100))) + box("moof", ByteArray(16)) + box("mdat", ByteArray(64)))
        val bytes = file.readBytes()
        assertFailsWith<TagEditUnsupported> { LocalTagEditor.write(file, LocalTags(title = "x")) }
        assertContentEquals(bytes, file.readBytes())
    }
}
