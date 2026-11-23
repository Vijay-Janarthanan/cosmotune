package com.cosmotune.audio

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class AiffHeaderTest {
    // 44100 Hz as an 80-bit extended float.
    private val rate44k = byteArrayOf(0x40, 0x0E, 0xAC.toByte(), 0x44, 0, 0, 0, 0, 0, 0)
    private val rate96k = byteArrayOf(0x40, 0x0F, 0xBB.toByte(), 0x80.toByte(), 0, 0, 0, 0, 0, 0)

    private fun comm(channels: Int, frames: Int, bits: Int, rate: ByteArray, compression: String? = null) =
        byteArrayOf(0, channels.toByte(), (frames ushr 24).toByte(), (frames ushr 16).toByte(), (frames ushr 8).toByte(), frames.toByte(), 0, bits.toByte()) +
            rate + (compression?.toByteArray(Charsets.ISO_8859_1) ?: ByteArray(0))

    @Test fun theCommonChunkGivesTheFormat() {
        assertEquals("AIFF", AiffFormat.formType("FORM".toByteArray() + ByteArray(4) + "AIFF".toByteArray()))
        assertNull(AiffFormat.formType("RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray()))
        val cd = AiffFormat.parseComm(comm(2, 441_000, 16, rate44k), "AIFF")!!
        assertEquals(AiffFormat(2, 441_000, 16, 44_100, littleEndian = false, float = false), cd)
        assertEquals(4, cd.blockAlign)
        assertEquals(10_000_000L, cd.durationUs)
        assertEquals(96_000, AiffFormat.parseComm(comm(2, 1, 24, rate96k), "AIFF")!!.sampleRate)
        assertEquals(true, AiffFormat.parseComm(comm(2, 1, 16, rate44k, "sowt"), "AIFC")!!.littleEndian)
        assertNull(AiffFormat.parseComm(comm(2, 1, 16, rate44k, "ulaw"), "AIFC"), "compressed audio is refused")
    }

    @Test fun samplesAreTurnedToThePlayersOrder() {
        val format = AiffFormat(1, 2, 24, 48_000, littleEndian = false, float = false)
        val data = byteArrayOf(1, 2, 3, 4, 5, 6)
        AiffFormat.toPlayerOrder(format, data, 0, 6)
        assertEquals(listOf<Byte>(3, 2, 1, 6, 5, 4), data.toList())
        val eight = byteArrayOf(0, 127, -128)
        AiffFormat.toPlayerOrder(AiffFormat(1, 3, 8, 8_000, false, false), eight, 0, 3)
        assertEquals(listOf<Byte>(-128, -1, 0), eight.toList())
    }
}
