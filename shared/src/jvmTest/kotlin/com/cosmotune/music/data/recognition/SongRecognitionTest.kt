package com.cosmotune.music.data.recognition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.PI
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SongRecognitionTest {

    private fun tones(seconds: Int, rate: Int = 16_000) = ShortArray(seconds * rate) { i ->
        val t = i.toDouble() / rate
        // A few notes that change every half second, so there are peaks to find.
        val note = 440.0 * (1 + (i / (rate / 2)) % 5 * 0.25)
        (sin(2 * PI * note * t) * 8_000 + sin(2 * PI * note * 2.01 * t) * 3_000).toInt().toShort()
    }

    @Test
    fun `a signature has Shazam's header, a valid checksum and peaks`() {
        val uri = ShazamSignatureGenerator.fromPcm16k(tones(seconds = 8))
        assertTrue(uri.startsWith("data:audio/vnd.shazam.sig;base64,"))
        val bytes = Base64.getDecoder().decode(uri.substringAfter(','))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0xcafe2580.toInt(), header.getInt(0))
        val crc = CRC32().apply { update(bytes, 8, bytes.size - 8) }.value.toInt()
        assertEquals(crc, header.getInt(4))
        assertEquals(bytes.size - 48, header.getInt(8))
        assertTrue(bytes.size > 56 + 16, "no peaks were written")
    }

    @Test
    fun `resampling to 16 kHz keeps the length in time`() {
        val second = ShortArray(44_100) { (sin(2 * PI * 1_000 * it / 44_100.0) * 10_000).toInt().toShort() }
        val out = SongRecognition.resampleTo16k(second, 44_100)
        assertEquals(16_000.0, out.size.toDouble(), 2.0)
        // A 1 kHz tone survives the low-pass at about its level.
        assertTrue(out.drop(1_000).maxOf { it.toInt() } > 8_000)
        assertTrue(SongRecognition.resampleTo16k(out, 16_000) === out)
    }

    @Test
    fun `a Shazam answer is read into a match`() {
        val body = """{"matches":[{"id":"1"}],"track":{"key":"123","title":"Kannalane","subtitle":"A. R. Rahman",
            "isrc":"INS179500123","url":"https://www.shazam.com/track/123",
            "images":{"coverart":"https://img/1.jpg","coverarthq":"https://img/1hq.jpg"},
            "sections":[{"type":"SONG","metadata":[{"title":"Album","text":"Bombay"},{"title":"Released","text":"1995"}]}],
            "hub":{"options":[{"type":"open","actions":[{"uri":"x"}]}]}}}"""
        val match = assertNotNull(SongRecognition.parse(body))
        assertEquals("Kannalane", match.title)
        assertEquals("A. R. Rahman", match.artist)
        assertEquals("Bombay", match.album)
        assertEquals("INS179500123", match.isrc)
        assertEquals("https://img/1hq.jpg", match.coverUrl)
    }

    @Test
    fun `an answer without a track is no match`() {
        assertNull(SongRecognition.parse("""{"matches":[],"tagid":"x"}"""))
        assertNull(SongRecognition.parse("not json"))
    }
}
