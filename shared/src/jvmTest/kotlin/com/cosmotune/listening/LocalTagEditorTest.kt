package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.test.*

class LocalTagEditorTest {
    @get:Rule val temporary = TemporaryFolder()
    private val audio = ByteArray(5000) { (it * 31 % 251).toByte() }

    private fun flac(file: File, vararg comments: String) {
        val out = ByteArrayOutputStream()
        out.write("fLaC".toByteArray())
        fun block(type: Int, body: ByteArray, last: Boolean = false) {
            out.write((if (last) 0x80 else 0) or type); out.write(body.size ushr 16); out.write(body.size ushr 8 and 0xFF); out.write(body.size and 0xFF); out.write(body)
        }
        block(0, ByteArray(34) { 9 })
        val encoded = comments.map { it.toByteArray() }
        val vendor = "reference encoder".toByteArray()
        val body = ByteBuffer.allocate(8 + vendor.size + encoded.sumOf { 4 + it.size }).order(ByteOrder.LITTLE_ENDIAN)
        body.putInt(vendor.size).put(vendor).putInt(encoded.size); encoded.forEach { body.putInt(it.size).put(it) }
        block(4, body.array())
        block(6, ByteArray(40) { 3 }) // a picture block that must survive
        block(1, ByteArray(200), last = true)
        out.write(audio)
        file.writeBytes(out.toByteArray())
    }

    private fun mp3v23(file: File) {
        fun frame(id: String, body: ByteArray) = id.toByteArray() + ByteBuffer.allocate(4).putInt(body.size).array() + byteArrayOf(0, 0) + body
        val frames = frame("TIT2", byteArrayOf(0) + "Old title".toByteArray()) + frame("APIC", ByteArray(64) { 5 }) + ByteArray(32)
        val size = frames.size
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0,
            (size ushr 21 and 0x7F).toByte(), (size ushr 14 and 0x7F).toByte(), (size ushr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        file.writeBytes(header + frames + byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + audio)
    }

    @Test fun flacCommentsAreEditedRemovedAndEverythingElseIsPreserved() {
        val file = temporary.newFile("song.flac").also { flac(it, "TITLE=Old", "ARTIST=Someone", "COMMENT=keep me", "GENRE=Pop") }
        LocalTagEditor.write(file, LocalTags(title = "நிலா", album = "New album", genre = ""))
        val tags = LocalTagEditor.read(file)
        assertEquals("நிலா", tags["TITLE"]); assertEquals("Someone", tags["ARTIST"]); assertEquals("New album", tags["ALBUM"]); assertNull(tags["GENRE"])
        val bytes = file.readBytes()
        assertContentEquals(audio, bytes.copyOfRange(bytes.size - audio.size, bytes.size))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("COMMENT=keep me"))
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("reference encoder"))
        assertEquals(1, Regex("\\u0006").findAll(String(bytes, 4, 40 + 34 + 200, Charsets.ISO_8859_1)).count().coerceAtMost(1))
        assertTrue(file.parentFile.listFiles()!!.none { it.name.startsWith(".cosmotune-tags-") })
    }

    @Test fun mp3TagsAreRewrittenInTheirVersionAndUntaggedFilesGainATag() {
        val tagged = temporary.newFile("a.mp3").also(::mp3v23)
        assertEquals("Old title", LocalTagEditor.read(tagged)["TITLE"])
        LocalTagEditor.write(tagged, LocalTags(title = "Nuevo título", artist = "Artista", year = "1999"))
        val tags = LocalTagEditor.read(tagged)
        assertEquals("Nuevo título", tags["TITLE"]); assertEquals("Artista", tags["ARTIST"]); assertEquals("1999", tags["YEAR"])
        val bytes = tagged.readBytes()
        assertEquals(3, bytes[3].toInt())
        assertTrue(String(bytes, Charsets.ISO_8859_1).contains("APIC"))
        assertContentEquals(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + audio, bytes.copyOfRange(bytes.size - audio.size - 2, bytes.size))

        val bare = temporary.newFile("b.mp3").apply { writeBytes(byteArrayOf(0xFF.toByte(), 0xFB.toByte()) + audio) }
        LocalTagEditor.write(bare, LocalTags(title = "Fresh"))
        assertEquals("Fresh", LocalTagEditor.read(bare)["TITLE"]); assertEquals(4, bare.readBytes()[3].toInt())
    }

    @Test fun unsupportedLayoutsAndReadOnlyFilesAreLeftUntouched() {
        val ogg = temporary.newFile("c.ogg").apply { writeBytes("OggS".toByteArray() + audio) }
        assertFailsWith<TagEditUnsupported> { LocalTagEditor.write(ogg, LocalTags(title = "x")) }
        val unsync = temporary.newFile("d.mp3").also(::mp3v23).apply { val b = readBytes(); b[5] = 0x80.toByte(); writeBytes(b) }
        val before = unsync.readBytes()
        assertFailsWith<TagEditUnsupported> { LocalTagEditor.write(unsync, LocalTags(title = "x")) }
        assertContentEquals(before, unsync.readBytes())
        val locked = temporary.newFile("e.flac").also { flac(it, "TITLE=Locked") }.apply { setWritable(false) }
        assertFailsWith<SecurityException> { LocalTagEditor.write(locked, LocalTags(title = "x")) }
        assertEquals("Locked", LocalTagEditor.read(locked)["TITLE"])
        locked.setWritable(true)
        assertFailsWith<IllegalArgumentException> { LocalTagEditor.write(locked, LocalTags()) }
    }

    @Test fun identicalCopiesMergeIntoOneKeptLocationWithoutChangingSessionIdentity() = runBlocking<Unit> {
        val one = temporary.newFile("one.flac").apply { writeBytes("fLaC".toByteArray() + audio) }
        val two = temporary.newFile("two.flac").apply { writeBytes(one.readBytes()) }
        val songs = listOf(Song("rec:a", "Same", "Artist", null, localPath = one.path), Song("rec:b", "Same", "Artist", null, localPath = two.path))
        val report = auditLocalLibrary(songs, verifyBytes = true)
        val group = report.duplicates.single { it.kind == LibraryDuplicateKind.IDENTICAL_BYTES }
        val saved = ListeningTrack.of(songs[1]).copy(entryId = "entry-7")
        val state = ListeningState(sessions = listOf(ListeningSession(id = "s", name = "S", tracks = listOf(saved, ListeningTrack.of(songs[0])), index = 0, positionMs = 50)))
        val preview = previewDuplicateMerge(state, report, group, keepIndex = group.indices.first { report.entries[it].song.localPath == one.path })
        assertEquals(1, preview.repairs.size)
        assertEquals(listOf(two.path), preview.remove.map { it.song.localPath })
        val merged = applySessionFileRepairs(state, preview.repairs) { it.path?.let(::File)?.isFile == true }
        assertEquals(one.path, merged.sessions.single().tracks[0].path)
        assertEquals("rec:b", merged.sessions.single().tracks[0].id); assertEquals("entry-7", merged.sessions.single().tracks[0].entryId)
        val metadataOnly = report.duplicates.first { it.kind == LibraryDuplicateKind.METADATA_CANDIDATES }
        assertFailsWith<IllegalArgumentException> { previewDuplicateMerge(state, report, metadataOnly, metadataOnly.indices.first()) }
        assertTrue(two.isFile) // merging never deletes; trashing is a separate host action
    }
}
