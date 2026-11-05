package com.cosmotune.music.desktop

import com.cosmotune.music.data.TelemetryProvenance
import com.cosmotune.music.data.audioFormatLabel
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The desktop engine's stream format reaches the shared player format line with the phone's bitrate rules. */
class DesktopFormatStatsTest {
    @Test fun positiveDecoderJocProfileAndProviderOfferRemainSeparateFromReceivedPcm() {
        assertEquals("eac3", observedCodecName("eac3", "Dolby Digital Plus"))
        assertEquals("eac3-joc", observedCodecName("eac3", "Dolby Digital Plus + Dolby Atmos"))
        val snapshot = desktopNerdSnapshot(DesktopStreamFormat("aac", 128, 48000), "source",
            DesktopStreamFormat("flac", sampleRateHz = 96000, bitDepth = 24), "Provider")!!
        assertEquals("Provider", snapshot.sourceName)
        assertEquals("source", snapshot.sourceId)
        assertEquals("audio/aac", snapshot.mimeType)
        assertEquals("flac", snapshot.claimed?.codec)
        assertTrue(snapshot.downgraded)
    }

    @Test fun anEncodedStreamShowsCodecBitrateAndRate() {
        val stats = desktopNerdSnapshot(DesktopStreamFormat(codec = "opus", kbps = 141, sampleRateHz = 48_000, channels = 2), "youtube")
        assertEquals(TelemetryProvenance.AUTHORITATIVE, stats?.bitrateProvenance)
        assertEquals("youtube", stats?.sourceName)
        assertEquals("Opus · 141 kbps · 48.0 kHz", stats?.audioFormatLabel())
    }

    @Test fun losslessKeepsItsEncodedBitrateAndBitDepth() {
        val stats = desktopNerdSnapshot(DesktopStreamFormat(codec = "audio/flac", kbps = 912, sampleRateHz = 96_000, bitDepth = 24, channels = 2), null)
        assertEquals("FLAC · 912 kbps · 24-bit · 96.0 kHz", stats?.audioFormatLabel())
    }

    @Test fun uncompressedPcmThroughputIsNotCalledABitrate() {
        val stats = desktopNerdSnapshot(DesktopStreamFormat(codec = "pcm_s16le", kbps = 1411, sampleRateHz = 44_100, bitDepth = 16, channels = 2), "local")
        assertEquals(TelemetryProvenance.DERIVED, stats?.bitrateProvenance)
        assertEquals(1411, stats?.pcmDataRateKbps)
        assertTrue(stats?.isLossless == true)
        assertEquals("PCM · 16-bit · 44.1 kHz", stats?.audioFormatLabel())
    }

    @Test fun unknownFiguresAreLeftOutAndNoFormatShowsNothing() {
        assertNull(desktopNerdSnapshot(null, "youtube"))
        val stats = desktopNerdSnapshot(DesktopStreamFormat(codec = "aac", kbps = 0), null)
        assertEquals(TelemetryProvenance.UNKNOWN, stats?.bitrateProvenance)
        assertEquals("AAC", stats?.audioFormatLabel())
        assertNull(desktopNerdSnapshot(DesktopStreamFormat(), null)?.audioFormatLabel())
    }
}
