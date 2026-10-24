package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import java.nio.ByteBuffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** MN-03 on desktop: a finished download is labelled from its own bytes (temporary files only). */
class DesktopDownloadVerificationTest {
    @get:Rule val temporary = TemporaryFolder()

    private val claimed = Song("src:addon::t1", "A song", "An artist", null, sourceQuality = "LOSSLESS", sourceHiRes = true)

    private fun flac(rate: Int, bits: Int): ByteArray {
        val info = ByteArray(34)
        ByteBuffer.wrap(info, 10, 4).putInt(((rate.toLong() shl 12) or (1L shl 9) or ((bits - 1).toLong() shl 4)).toInt())
        return "fLaC".toByteArray() + byteArrayOf(0x80.toByte(), 0, 0, 34) + info + ByteArray(256)
    }

    private fun box(type: String, body: ByteArray) = ByteBuffer.allocate(8 + body.size).putInt(8 + body.size).put(type.toByteArray()).put(body).array()

    private fun aacM4a(): ByteArray {
        val entry = ByteBuffer.allocate(36).putInt(36).put("mp4a".toByteArray()).put(ByteArray(6)).putShort(1).put(ByteArray(8))
            .putShort(2).putShort(16).putShort(0).putShort(0).putInt(44_100 shl 16).array()
        val stsd = box("stsd", ByteBuffer.allocate(44).putInt(0).putInt(1).put(entry).array())
        return box("ftyp", "M4A \u0000\u0000\u0000\u0000".toByteArray()) + box("moov", box("trak", stsd)) + box("mdat", ByteArray(512))
    }

    @Test fun aLosslessClaimOverAacLosesItsBadgeAndNamesTheCodec() {
        val file = temporary.newFile("song.flac").apply { writeBytes(aacM4a()) }
        val saved = DesktopDownloadManager.verified(claimed, file, claimedLossless = true)
        assertNull(saved.sourceQuality)
        assertFalse(saved.sourceHiRes)
        assertEquals("AAC", saved.downloadFormat)
        assertEquals(file.toString(), saved.localPath)
    }

    @Test fun verifiedFlacKeepsAMeasuredLabelAndHiResOnlyWhenTheBytesSaySo() {
        val cd = DesktopDownloadManager.verified(claimed, temporary.newFile("cd.flac").apply { writeBytes(flac(44_100, 16)) }, true)
        assertEquals("FLAC", cd.sourceQuality); assertFalse(cd.sourceHiRes); assertEquals("FLAC", cd.downloadFormat)
        val hires = DesktopDownloadManager.verified(claimed, temporary.newFile("hr.flac").apply { writeBytes(flac(96_000, 24)) }, true)
        assertTrue(hires.sourceHiRes)
    }

    @Test fun aWebPageSavedAsAudioIsRemovedAndTheDownloadFails() {
        val file = temporary.newFile("expired.flac").apply { writeText("<html><body>Link expired</body></html>") }
        assertFailsWith<IllegalStateException> { DesktopDownloadManager.verified(claimed, file, true) }
        assertFalse(file.exists())
        val existing = temporary.newFile("old.flac").apply { writeText("{\"error\":\"region\"}") }
        assertFailsWith<IllegalStateException> { DesktopDownloadManager.verified(claimed, existing, true, deleteIfNotAudio = false) }
        assertTrue(existing.exists(), "a file saved by an earlier build is never deleted by this check")
    }
}
