package com.cosmotune.listening

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackIdentifiersTest {
    private val rec = "5b11f4ce-a62d-471e-81fc-a69a8278c7da"
    private val acoust = "0a1b2c3d-4e5f-4a6b-8c7d-9e0f1a2b3c4d"

    @Test fun vorbisCommentsNameTheRecording() {
        val ids = TrackIdentifiers.fromVorbis(listOf("TITLE=Song", "musicbrainz_trackid=${rec.uppercase()}", "ACOUSTID_ID=$acoust", "ISRC=us-rc1-76-07839"))
        assertEquals(TrackIdentifiers(rec, acoust, "USRC17607839"), ids)
        assertTrue(TrackIdentifiers.fromVorbis(listOf("MUSICBRAINZ_TRACKID=not-an-id", "ISRC=123")).isEmpty, "malformed ids are left out")
    }

    private fun frame(id: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(id.toByteArray(Charsets.ISO_8859_1))
        val n = body.size
        out.write(byteArrayOf((n ushr 21 and 0x7F).toByte(), (n ushr 14 and 0x7F).toByte(), (n ushr 7 and 0x7F).toByte(), (n and 0x7F).toByte(), 0, 0))
        out.write(body)
        return out.toByteArray()
    }

    private fun ufid() = "http://musicbrainz.org".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(0) + rec.toByteArray(Charsets.ISO_8859_1)
    private fun txxxUtf16(description: String, value: String) =
        byteArrayOf(1) + description.toByteArray(Charsets.UTF_16) + byteArrayOf(0, 0) + value.toByteArray(Charsets.UTF_16)

    @Test fun id3FramesNameTheRecording() {
        val ids = TrackIdentifiers.fromId3(listOf(
            "UFID" to ufid(),
            "TXXX" to txxxUtf16("Acoustid Id", acoust),
            "TSRC" to (byteArrayOf(3) + "GBAYE0601498".toByteArray()),
        ))
        assertEquals(TrackIdentifiers(rec, acoust, "GBAYE0601498"), ids)
        val other = TrackIdentifiers.fromId3(listOf("UFID" to ("http://example.org".toByteArray() + byteArrayOf(0) + rec.toByteArray())))
        assertTrue(other.isEmpty, "another owner's UFID is not MusicBrainz's")
    }

    @Test fun mp4FreeformItemsNameTheRecording() {
        val ids = TrackIdentifiers.fromFreeform(mapOf("MUSICBRAINZ TRACK ID" to rec, "ACOUSTID ID" to acoust, "ISRC" to "GBAYE0601498"))
        assertEquals(TrackIdentifiers(rec, acoust, "GBAYE0601498"), ids)
    }

    @Test fun anMp3FileIsReadForItsIds() {
        val frames = frame("TIT2", byteArrayOf(3) + "Song".toByteArray()) + frame("UFID", ufid()) + frame("TSRC", byteArrayOf(0) + "GBAYE0601498".toByteArray())
        val size = frames.size
        val header = "ID3".toByteArray() + byteArrayOf(4, 0, 0, (size ushr 21 and 0x7F).toByte(), (size ushr 14 and 0x7F).toByte(), (size ushr 7 and 0x7F).toByte(), (size and 0x7F).toByte())
        val file = Files.createTempFile("ids", ".mp3").toFile()
        try {
            file.writeBytes(header + frames + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x00) + ByteArray(400))
            assertEquals(TrackIdentifiers(rec, null, "GBAYE0601498"), LocalTagEditor.identifiers(file))
            file.writeBytes(ByteArray(16))
            assertTrue(LocalTagEditor.identifiers(file).isEmpty, "a file it cannot read has no ids")
        } finally { file.delete() }
    }
}
