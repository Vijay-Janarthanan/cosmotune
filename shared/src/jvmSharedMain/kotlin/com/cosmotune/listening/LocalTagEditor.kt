package com.cosmotune.listening

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** The fields Cosmotune edits. A null field is left exactly as the file has it; a blank one removes it. */
data class LocalTags(
    val title: String? = null, val artist: String? = null, val album: String? = null,
    val albumArtist: String? = null, val genre: String? = null, val track: String? = null, val year: String? = null,
) {
    internal fun edits(): List<Pair<TagField, String>> = listOfNotNull(
        title?.let { TagField.TITLE to it }, artist?.let { TagField.ARTIST to it }, album?.let { TagField.ALBUM to it },
        albumArtist?.let { TagField.ALBUM_ARTIST to it }, genre?.let { TagField.GENRE to it },
        track?.let { TagField.TRACK to it }, year?.let { TagField.YEAR to it },
    ).map { (field, value) -> field to value.trim().also { require(it.length <= 1000 && it.none { c -> c == '\u0000' }) { "A tag value is too long." } } }
}

internal enum class TagField(val vorbis: String, val id3: String) {
    TITLE("TITLE", "TIT2"), ARTIST("ARTIST", "TPE1"), ALBUM("ALBUM", "TALB"), ALBUM_ARTIST("ALBUMARTIST", "TPE2"),
    GENRE("GENRE", "TCON"), TRACK("TRACKNUMBER", "TRCK"), YEAR("DATE", "TDRC"),
}

class TagEditUnsupported(message: String) : Exception(message)

/**
 * Self-contained FLAC (Vorbis comment) and MP3 (ID3v2.3/2.4) tag writer. Audio bytes are copied
 * unchanged after the metadata; every other block or frame is preserved. The result is written
 * beside the original, re-read and checked, then atomically replaces it. A file that changes
 * while it is being edited is left untouched. Layouts this cannot rewrite safely are refused.
 */
object LocalTagEditor {
    private const val MAX_METADATA = 16 * 1024 * 1024

    fun read(file: File): Map<String, String> = when (kind(file)) {
        Kind.FLAC -> flacComments(file).groupBy({ it.substringBefore('=').uppercase(java.util.Locale.ROOT) }, { it.substringAfter('=') })
            .mapValues { it.value.first() }.let { all -> TagField.entries.mapNotNull { f -> all[f.vorbis]?.let { f.name to it } }.toMap() }
        Kind.MP3 -> id3(file)?.let { tag -> TagField.entries.mapNotNull { f -> tag.frames.firstOrNull { it.id == f.id3 || (f == TagField.YEAR && it.id == "TYER") }?.let { f.name to tag.text(it) } }.toMap() } ?: emptyMap()
    }

    fun write(file: File, tags: LocalTags) {
        val edits = tags.edits()
        require(edits.isNotEmpty()) { "Change at least one tag." }
        require(file.isAbsolute && file.isFile) { "Only local files can be edited." }
        if (!file.canWrite()) throw SecurityException("Cosmotune cannot write to this file.")
        val length = file.length(); val modified = file.lastModified()
        val kind = kind(file)
        val temp = Files.createTempFile(file.absoluteFile.parentFile.toPath(), ".cosmotune-tags-", ".tmp").toFile()
        try {
            val audioOffset: Long
            val metadata: ByteArray
            when (kind) {
                Kind.FLAC -> { val (blocks, offset) = flacBlocks(file); audioOffset = offset; metadata = flacWithComments(blocks, edits) }
                Kind.MP3 -> { val tag = id3(file); audioOffset = tag?.totalSize ?: 0L; metadata = id3With(tag, edits) }
            }
            java.io.FileOutputStream(temp).use { out ->
                out.write(metadata)
                file.inputStream().use { input -> input.skipFully(audioOffset); input.copyTo(out, 64 * 1024) }
                out.fd.sync()
            }
            // The audio after the metadata must be byte-identical, and the new tags must read back.
            require(temp.length() - metadata.size == length - audioOffset) { "The edited file failed verification." }
            val readBack = read(temp)
            edits.forEach { (field, value) -> require(readBack[field.name].orEmpty() == value) { "The edited file failed verification." } }
            if (file.length() != length || file.lastModified() != modified) throw IllegalStateException("The file changed while editing. Try again.")
            try { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE) }
            catch (_: java.nio.file.AtomicMoveNotSupportedException) { Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING) }
        } finally { temp.delete() }
    }

    private enum class Kind { FLAC, MP3 }

    private fun kind(file: File): Kind {
        val head = ByteArray(4)
        val read = file.inputStream().use { it.read(head) }
        return when {
            read == 4 && String(head, Charsets.ISO_8859_1) == "fLaC" -> Kind.FLAC
            read >= 3 && String(head, 0, 3, Charsets.ISO_8859_1) == "ID3" -> Kind.MP3
            read >= 2 && (head[0].toInt() and 0xFF) == 0xFF && (head[1].toInt() and 0xE0) == 0xE0 -> Kind.MP3
            else -> throw TagEditUnsupported("Tag editing supports FLAC and MP3 files.")
        }
    }

    private fun java.io.InputStream.skipFully(n: Long) { var left = n; while (left > 0) { val s = skip(left); if (s <= 0) { if (read() < 0) error("Truncated file"); left-- } else left -= s } }

    // ---- FLAC ----
    private class Block(val type: Int, val body: ByteArray)

    private fun flacBlocks(file: File): Pair<List<Block>, Long> = RandomAccessFile(file, "r").use { raf ->
        raf.seek(4)
        val blocks = mutableListOf<Block>()
        var total = 0L
        while (true) {
            val header = raf.readInt()
            val last = header ushr 31 == 1
            val type = (header ushr 24) and 0x7F
            val size = header and 0xFFFFFF
            total += size
            if (type == 127 || total > MAX_METADATA) throw TagEditUnsupported("This FLAC file's metadata is not supported.")
            blocks += Block(type, ByteArray(size).also(raf::readFully))
            if (last) break
        }
        require(blocks.firstOrNull()?.type == 0) { "This FLAC file is damaged." }
        blocks to raf.filePointer
    }

    private fun flacComments(file: File): List<String> {
        val block = flacBlocks(file).first.firstOrNull { it.type == 4 } ?: return emptyList()
        val buffer = ByteBuffer.wrap(block.body).order(ByteOrder.LITTLE_ENDIAN)
        buffer.position(4 + buffer.getInt(0))
        return List(buffer.int) { val n = buffer.int; String(ByteArray(n).also(buffer::get), Charsets.UTF_8) }
    }

    private fun flacWithComments(blocks: List<Block>, edits: List<Pair<TagField, String>>): ByteArray {
        val existing = blocks.firstOrNull { it.type == 4 }
        var vendor = "Cosmotune".toByteArray()
        val comments = mutableListOf<String>()
        if (existing != null) {
            val buffer = ByteBuffer.wrap(existing.body).order(ByteOrder.LITTLE_ENDIAN)
            vendor = ByteArray(buffer.int).also(buffer::get)
            repeat(buffer.int) { val n = buffer.int; comments += String(ByteArray(n).also(buffer::get), Charsets.UTF_8) }
        }
        val edited = edits.map { it.first.vorbis }.toSet()
        val kept = comments.filterNot { it.substringBefore('=').uppercase(java.util.Locale.ROOT) in edited } +
            edits.filter { it.second.isNotEmpty() }.map { "${it.first.vorbis}=${it.second}" }
        val encoded = kept.map { it.toByteArray(Charsets.UTF_8) }
        val body = ByteBuffer.allocate(8 + vendor.size + encoded.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(vendor.size).put(vendor).putInt(encoded.size); encoded.forEach { body.putInt(it.size).put(it) }
        val comment = Block(4, body.array())
        require(comment.body.size < 0xFFFFFF) { "Tags are too large." }
        val others = blocks.filter { it.type != 4 && it.type != 1 }
        val padding = blocks.filter { it.type == 1 }.sumOf { it.body.size }
        val ordered = listOf(others.first(), comment) + others.drop(1)
        // Reuse the existing padding when the new comment still fits, so most players see no size change.
        val grown = comment.body.size - (existing?.body?.size ?: 0)
        val finalBlocks = if (padding - grown >= 4) ordered + Block(1, ByteArray(padding - grown)) else ordered + Block(1, ByteArray(1024))
        val out = java.io.ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        finalBlocks.forEachIndexed { i, block ->
            val last = if (i == finalBlocks.lastIndex) 0x80 else 0
            out.write(last or block.type); out.write(block.body.size ushr 16); out.write(block.body.size ushr 8 and 0xFF); out.write(block.body.size and 0xFF)
            out.write(block.body)
        }
        return out.toByteArray()
    }

    // ---- ID3v2 ----
    private class Frame(val id: String, val flags: Int, val body: ByteArray)
    private class Id3(val major: Int, val frames: List<Frame>, val totalSize: Long) {
        fun text(frame: Frame): String {
            if (frame.body.isEmpty()) return ""
            val data = frame.body.copyOfRange(1, frame.body.size)
            val charset = when (frame.body[0].toInt()) { 0 -> Charsets.ISO_8859_1; 1 -> Charsets.UTF_16; 2 -> Charsets.UTF_16BE; else -> Charsets.UTF_8 }
            return String(data, charset).trimEnd('\u0000').substringBefore('\u0000')
        }
    }

    private fun syncsafe(b: ByteArray, at: Int) = ((b[at].toInt() and 0x7F) shl 21) or ((b[at + 1].toInt() and 0x7F) shl 14) or ((b[at + 2].toInt() and 0x7F) shl 7) or (b[at + 3].toInt() and 0x7F)
    private fun syncsafeBytes(n: Int) = byteArrayOf((n ushr 21 and 0x7F).toByte(), (n ushr 14 and 0x7F).toByte(), (n ushr 7 and 0x7F).toByte(), (n and 0x7F).toByte())

    private fun id3(file: File): Id3? = RandomAccessFile(file, "r").use { raf ->
        val header = ByteArray(10)
        if (raf.length() < 10) return null
        raf.readFully(header)
        if (String(header, 0, 3, Charsets.ISO_8859_1) != "ID3") return null
        val major = header[3].toInt()
        val flags = header[5].toInt() and 0xFF
        if (major !in 3..4) throw TagEditUnsupported("Only ID3v2.3 and ID3v2.4 tags can be edited.")
        if (flags and 0xC0 != 0) throw TagEditUnsupported("This MP3 tag uses unsynchronisation or an extended header, which is not edited.")
        val size = syncsafe(header, 6)
        if (size > MAX_METADATA) throw TagEditUnsupported("This MP3 tag is too large.")
        val body = ByteArray(size).also(raf::readFully)
        val frames = mutableListOf<Frame>()
        var at = 0
        while (at + 10 <= body.size && body[at] != 0.toByte()) {
            val id = String(body, at, 4, Charsets.ISO_8859_1)
            val length = if (major == 4) syncsafe(body, at + 4) else ByteBuffer.wrap(body, at + 4, 4).int
            val frameFlags = ((body[at + 8].toInt() and 0xFF) shl 8) or (body[at + 9].toInt() and 0xFF)
            if (length < 0 || at + 10 + length > body.size) throw TagEditUnsupported("This MP3 tag is damaged.")
            frames += Frame(id, frameFlags, body.copyOfRange(at + 10, at + 10 + length))
            at += 10 + length
        }
        val footer = if (major == 4 && flags and 0x10 != 0) 10 else 0
        Id3(major, frames, 10L + size + footer)
    }

    private fun id3With(tag: Id3?, edits: List<Pair<TagField, String>>): ByteArray {
        val major = tag?.major ?: 4
        val edited = edits.flatMap { (f, _) -> if (f == TagField.YEAR) listOf("TDRC", "TYER") else listOf(f.id3) }.toSet()
        val kept = tag?.frames.orEmpty().filterNot { it.id in edited }
        val yearId = if (major == 3) "TYER" else "TDRC"
        val written = edits.filter { it.second.isNotEmpty() }.map { (field, value) ->
            val body = if (major == 3) byteArrayOf(1) + value.toByteArray(Charsets.UTF_16) else byteArrayOf(3) + value.toByteArray(Charsets.UTF_8)
            Frame(if (field == TagField.YEAR) yearId else field.id3, 0, body)
        }
        val frames = java.io.ByteArrayOutputStream()
        (kept + written).forEach { frame ->
            frames.write(frame.id.toByteArray(Charsets.ISO_8859_1))
            frames.write(if (major == 4) syncsafeBytes(frame.body.size) else ByteBuffer.allocate(4).putInt(frame.body.size).array())
            frames.write(frame.flags ushr 8); frames.write(frame.flags and 0xFF)
            frames.write(frame.body)
        }
        frames.write(ByteArray(1024)) // padding for later edits
        val size = frames.size()
        require(size < 1 shl 28) { "Tags are too large." }
        return byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), major.toByte(), 0, 0) + syncsafeBytes(size) + frames.toByteArray()
    }
}
