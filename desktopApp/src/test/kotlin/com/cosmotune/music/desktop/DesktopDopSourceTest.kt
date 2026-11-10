package com.cosmotune.music.desktop

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class DesktopDopSourceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun nativeAlsaWordsPreserveChannelOrderAndStripMarkers() {
        val frames = floatArrayOf(
            DesktopDopSource.sample(0x81, 0x42, true), DesktopDopSource.sample(0x24, 0x18, true),
            DesktopDopSource.sample(0x03, 0x06, false), DesktopDopSource.sample(0x0c, 0x30, false),
            DesktopDopSource.sample(0x80, 0x01, true), DesktopDopSource.sample(0x10, 0x08, true),
        )
        fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
        assertContentEquals(bytes(0x81, 0x42, 0x03, 0x06, 0x24, 0x18, 0x0c, 0x30, 0x80, 0x01, 0x69, 0x69, 0x10, 0x08, 0x69, 0x69),
            AlsaDsdWord.DSD_U32_BE.pack(frames, frames.size, 2))
        assertContentEquals(bytes(0x06, 0x03, 0x42, 0x81, 0x30, 0x0c, 0x18, 0x24, 0x69, 0x69, 0x01, 0x80, 0x69, 0x69, 0x08, 0x10),
            AlsaDsdWord.DSD_U32_LE.pack(frames, frames.size, 2))
        assertContentEquals(bytes(0x81, 0x42, 0x24, 0x18), AlsaDsdWord.DSD_U16_BE.pack(frames, 2, 2))
        assertContentEquals(bytes(0x42, 0x81, 0x18, 0x24), AlsaDsdWord.DSD_U16_LE.pack(frames, 2, 2))
    }

    @Test fun coreAudioKeepsDopMarkerPhaseAcrossUnderflowAndRingWrap() {
        val fifo = DoPCarrierFifo(channels = 1, capacity = 2)
        val samples = byteArrayOf(1, 2, -6, 3, 4, 5, 6, 7, -6)
        val output = ByteArray(9)
        assertEquals(2, fifo.append(samples, 0, 3))
        fifo.render(output, 3)
        assertContentEquals(byteArrayOf(1, 2, 5, 3, 4, -6, 0x69, 0x69, 5), output)
        assertEquals(2L, fifo.framesPlayed())
        assertEquals(1, fifo.append(samples, 2, 1))
        fifo.render(output, 1)
        assertContentEquals(byteArrayOf(6, 7, -6), output.copyOf(3))
        fifo.clear()
        assertTrue(fifo.empty())
        fifo.render(output, 1)
        assertContentEquals(byteArrayOf(0x69, 0x69, 5), output.copyOf(3))
        assertEquals(3L, fifo.framesPlayed())
    }

    @Test fun everyPayloadAndBothMarkersSurviveTheExisting24BitPcmWriterExactly() {
        for (first in listOf(true, false)) {
            val samples = FloatArray(65_536) { payload -> DesktopDopSource.sample(payload ushr 8, payload and 255, first) }
            val bytes = encodeDesktopPcm(samples, samples.size, DesktopPcmFormat(176_400, 1, 3), 1f)
            for (payload in samples.indices) {
                assertEquals(payload and 255, bytes[payload * 3].toInt() and 255)
                assertEquals(payload ushr 8, bytes[payload * 3 + 1].toInt() and 255)
                assertEquals(if (first) 0x05 else 0xfa, bytes[payload * 3 + 2].toInt() and 255)
            }
        }
    }

    private fun dsf(bits: Int = 32): ByteArray {
        val b = ByteBuffer.allocate(92 + 8192).order(ByteOrder.LITTLE_ENDIAN)
        fun id(s: String) { b.put(s.toByteArray(Charsets.US_ASCII)) }
        id("DSD "); b.putLong(28); b.putLong(b.capacity().toLong()); b.putLong(0)
        id("fmt "); b.putLong(52)
        b.putInt(1); b.putInt(0); b.putInt(2); b.putInt(2); b.putInt(2_822_400); b.putInt(1)
        b.putLong(bits.toLong()); b.putInt(4096); b.putInt(0)
        id("data"); b.putLong(12L + 8192)
        b.put(byteArrayOf(1, 2, 4, 8)); b.position(92 + 4096); b.put(byteArrayOf(16, 32, 64, -128))
        return b.array()
    }

    @Test fun dsfPlanarLsbDataBecomesStereoDopWithSharedMarkersAndSeekParity() {
        val file = temporary.newFile("recording.dsf").apply { writeBytes(dsf()) }
        val source = DesktopDopSource.open(file.path).getOrThrow()
        try {
            assertEquals(176_400, source.format.sampleRate)
            val values = source.readSamples()!!.copyOf(source.sampleCount)
            val bytes = encodeDesktopPcm(values, values.size, source.format, 1f)
            assertContentEquals(byteArrayOf(0x40, 0x80.toByte(), 0x05, 0x04, 0x08, 0x05,
                0x10, 0x20, 0xfa.toByte(), 0x01, 0x02, 0xfa.toByte()), bytes)
            source.seek(6)
            val seek = source.readSamples()!!.copyOf(source.sampleCount)
            assertContentEquals(values.copyOfRange(2, 4), seek)
            source.seek(source.info.durationUs)
            assertNull(source.readSamples())
        } finally { source.close() }
    }

    @Test fun rawDffIsFramedButTruncatedOrCompressedContainersAreRefused() {
        fun chunk(id: String, bytes: ByteArray): ByteArray = ByteArrayOutputStream().use { out ->
            out.write(id.toByteArray(Charsets.US_ASCII)); out.write(ByteBuffer.allocate(8).putLong(bytes.size.toLong()).array())
            out.write(bytes); if (bytes.size and 1 != 0) out.write(0); out.toByteArray()
        }
        val props = "SND ".toByteArray() + chunk("FS  ", ByteBuffer.allocate(4).putInt(2_822_400).array()) +
            chunk("CHNL", ByteBuffer.allocate(10).putShort(2).put("SLFTSRGT".toByteArray()).array()) + chunk("CMPR", "DSD \u0000".toByteArray())
        val form = "DSD ".toByteArray() + chunk("PROP", props) + chunk("DSD ", byteArrayOf(0x80.toByte(), 0x20, 0x40, 0x10))
        val bytes = chunk("FRM8", form)
        val file = temporary.newFile("recording.dff").apply { writeBytes(bytes) }
        DesktopDopSource.open(file.path).getOrThrow().let { source -> try {
            val values = source.readSamples()!!.copyOf(source.sampleCount)
            assertContentEquals(byteArrayOf(0x40, 0x80.toByte(), 5, 0x10, 0x20, 5), encodeDesktopPcm(values, values.size, source.format, 1f))
        } finally { source.close() } }
        file.writeBytes(bytes.copyOf(bytes.size - 1))
        assertTrue(DesktopDopSource.open(file.path).isFailure)
        file.writeBytes(bytes.toString(Charsets.ISO_8859_1).replace("DSD \u0000", "DST \u0000").toByteArray(Charsets.ISO_8859_1))
        assertTrue(DesktopDopSource.open(file.path).isFailure)
    }
}
