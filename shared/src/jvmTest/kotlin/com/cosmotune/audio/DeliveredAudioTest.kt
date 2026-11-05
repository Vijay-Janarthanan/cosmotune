package com.cosmotune.audio

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** MN-03: provider claims are compared with the bytes that arrived, using synthetic container fixtures. */
class DeliveredAudioTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun flac(rate: Int, channels: Int, bits: Int): ByteArray {
        val info = ByteArray(34)
        val packed = (rate.toLong() shl 12) or ((channels - 1).toLong() shl 9) or ((bits - 1).toLong() shl 4)
        ByteBuffer.wrap(info, 10, 4).putInt(packed.toInt())
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info + ByteArray(64)
    }

    private fun box(type: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray(Charsets.ISO_8859_1)).put(body).array()

    private fun mp4(sampleEntry: String, rate: Int = 44_100, bits: Int = 16, movieFirst: Boolean = true, mediaBytes: Int = 1024): ByteArray {
        // ALAC carries its real format in a cookie box; the 16.16 rate field only fits rates up to 65,535 Hz.
        val cookie = if (sampleEntry != "alac") ByteArray(0) else ByteBuffer.allocate(36).putInt(36).put("alac".toByteArray())
            .putInt(0).putInt(4096).put(0).put(bits.toByte()).put(40).put(10).put(14).put(2).putShort(255).putInt(0).putInt(0).putInt(rate).array()
        val entry = ByteBuffer.allocate(36 + cookie.size).putInt(36 + cookie.size).put(sampleEntry.toByteArray(Charsets.ISO_8859_1))
            .put(ByteArray(6)).putShort(1).put(ByteArray(8)).putShort(2).putShort(bits.toShort()).putShort(0).putShort(0)
            .putInt(if (rate > 65_535) 0 else rate shl 16).put(cookie).array()
        val stsd = box("stsd", ByteBuffer.allocate(8 + entry.size).putInt(0).putInt(1).put(entry).array())
        val moov = box("moov", box("trak", box("mdia", box("minf", box("stbl", stsd)))))
        val ftyp = box("ftyp", "M4A \u0000\u0000\u0000\u0000M4A ".toByteArray(Charsets.ISO_8859_1))
        val mdat = box("mdat", ByteArray(mediaBytes) { 0x55 })
        return if (movieFirst) ftyp + moov + mdat else ftyp + mdat + moov
    }

    private fun wav(tag: Int, rate: Int = 44_100, bits: Int = 16): ByteArray {
        val fmt = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN).put("fmt ".toByteArray()).putInt(16)
            .putShort(tag.toShort()).putShort(2).putInt(rate).putInt(rate * 4).putShort(4).putShort(bits.toShort()).array()
        return "RIFF".toByteArray() + ByteArray(4) + "WAVE".toByteArray() + fmt + "data".toByteArray() + ByteArray(4)
    }

    private fun ogg(payload: ByteArray): ByteArray {
        val header = ByteArray(27).also { "OggS".toByteArray().copyInto(it); it[26] = 1 }
        return header + byteArrayOf(payload.size.toByte()) + payload
    }

    @Test fun losslessContainersAreVerifiedWithTheirFormat() {
        val hires = DeliveredAudio.inspect(flac(96_000, 2, 24))!!
        assertEquals(DeliveredAudio("flac", "flac", true, 96_000, 24, 2), hires)
        assertTrue(hires.hiRes)
        assertEquals(HonestQuality(QualityEvidence.VERIFIED_LOSSLESS, "FLAC 24-bit 96 kHz", "FLAC", true), honestQuality(true, hires))
        val cd = honestQuality(true, DeliveredAudio.inspect(flac(44_100, 2, 16)))
        assertEquals("FLAC 16-bit 44.1 kHz", cd.label); assertFalse(cd.hiRes)

        assertEquals(DeliveredAudio("mp4", "alac", true, 48_000, 24, 2), DeliveredAudio.inspect(mp4("alac", 48_000, 24)))
        assertEquals("pcm", DeliveredAudio.inspect(wav(1))?.codec)
        assertEquals(true, DeliveredAudio.inspect(wav(0xFFFE, 192_000, 32))?.lossless)
        assertEquals("ogg", DeliveredAudio.inspect(ogg("\u007fFLAC\u0001\u0000\u0000\u0001".toByteArray(Charsets.ISO_8859_1) + flac(48_000, 2, 16)))?.container)
        assertEquals(true, DeliveredAudio.inspect(ogg("\u007fFLAC\u0001\u0000\u0000\u0001".toByteArray(Charsets.ISO_8859_1) + flac(48_000, 2, 16)))?.lossless)
        // An ID3 tag in front of FLAC frames (some providers prepend one) is skipped.
        val id3 = "ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 10) + ByteArray(10)
        assertEquals("flac", DeliveredAudio.inspect(id3 + flac(44_100, 2, 16))?.codec)
    }

    @Test fun aLosslessClaimOverLossyBytesNeverWearsTheLosslessLabel() {
        val aacInM4a = DeliveredAudio.inspect(mp4("mp4a"))
        assertEquals(DeliveredAudio("mp4", "aac", false, 44_100, channels = 2), aacInM4a)
        val honest = honestQuality(claimedLossless = true, aacInM4a)
        assertEquals(QualityEvidence.LOSSY_DESPITE_CLAIM, honest.evidence)
        assertEquals("AAC (offered as lossless)", honest.label)
        assertNull(honest.sourceQuality)

        val mp3 = DeliveredAudio.inspect("ID3".toByteArray() + byteArrayOf(4, 0, 0, 0, 0, 0, 0) + byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0))
        assertEquals("mp3", mp3?.codec); assertEquals(false, mp3?.lossless)
        assertEquals("aac", DeliveredAudio.inspect(byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50, 0x80.toByte()))?.codec)
        assertEquals("opus", DeliveredAudio.inspect(ogg("OpusHead\u0001\u0002".toByteArray(Charsets.ISO_8859_1)))?.codec)
        assertEquals(false, DeliveredAudio.inspect(wav(0x55))?.lossless) // MP3 inside a WAV wrapper
        assertEquals("eac3", DeliveredAudio.inspect(mp4("ec-3", 48_000))?.codec)
        assertEquals(QualityEvidence.LOSSY, honestQuality(false, mp3).evidence)
    }

    @Test fun errorPagesAndUnreadableContainersAreNotPassedOffAsLossless() {
        val page = DeliveredAudio.inspect("<!doctype html><title>Expired</title>".toByteArray())
        assertEquals(QualityEvidence.NOT_AUDIO, honestQuality(true, page).evidence)
        assertEquals(QualityEvidence.NOT_AUDIO, honestQuality(true, DeliveredAudio.inspect("""{"error":"region"}""".toByteArray())).evidence)
        // An MP4 whose movie box lies beyond the probe window proves nothing from its first bytes.
        val late = mp4("alac", movieFirst = false, mediaBytes = DeliveredAudio.PROBE_BYTES + 10)
        assertEquals(DeliveredAudio("mp4", null, null), DeliveredAudio.inspect(late.copyOf(DeliveredAudio.PROBE_BYTES)))
        val unverified = honestQuality(true, DeliveredAudio.inspect(late.copyOf(DeliveredAudio.PROBE_BYTES)))
        assertEquals(QualityEvidence.CLAIMED_UNVERIFIED, unverified.evidence)
        assertEquals("Lossless (not verified)", unverified.label); assertNull(unverified.sourceQuality)
        assertNull(DeliveredAudio.inspect(ByteArray(2)))
        assertEquals(QualityEvidence.CLAIMED_UNVERIFIED, honestQuality(true, null).evidence)
    }

    @Test fun aSavedFileIsReadInFullForATrailingMovieBox() {
        val file = temporary.newFile("late.m4a")
        file.writeBytes(mp4("alac", 96_000, 24, movieFirst = false, mediaBytes = DeliveredAudio.PROBE_BYTES + 4096))
        assertEquals(DeliveredAudio("mp4", "alac", true, 96_000, 24, 2), DeliveredAudio.inspect(file))
        val lossy = temporary.newFile("late-aac.m4a")
        lossy.writeBytes(mp4("mp4a", movieFirst = false, mediaBytes = DeliveredAudio.PROBE_BYTES + 4096))
        assertEquals(false, DeliveredAudio.inspect(lossy)?.lossless)
        val output = ByteArrayOutputStream().apply { write(flac(44_100, 2, 16)) }
        val saved = temporary.newFile("song.flac").apply { writeBytes(output.toByteArray()) }
        assertEquals("FLAC", honestQuality(true, DeliveredAudio.inspect(saved)).sourceQuality)
    }

    @Test fun downloadsKeepOnlyTheLabelTheirBytesSupport() {
        assertEquals("FLAC", verifiedDownloadLabel("FLAC", DeliveredAudio.inspect(flac(44_100, 2, 16))))
        assertEquals("ALAC", verifiedDownloadLabel(null, DeliveredAudio.inspect(mp4("alac")), claimedLossless = true))
        assertEquals("AAC", verifiedDownloadLabel("FLAC", DeliveredAudio.inspect(mp4("mp4a"))))
        assertNull(verifiedDownloadLabel("FLAC", DeliveredAudio("mp4", null, null)))
        assertEquals("DOLBY", verifiedDownloadLabel("DOLBY", DeliveredAudio.inspect(mp4("ec-3", 48_000))))
        assertEquals("AAC · 256 kbps", verifiedDownloadLabel("AAC · 256 kbps", DeliveredAudio.inspect(mp4("mp4a"))))
        assertFailsWith<IllegalStateException> { verifiedDownloadLabel("FLAC", DeliveredAudio.inspect("<html>".toByteArray())) }
    }
}
