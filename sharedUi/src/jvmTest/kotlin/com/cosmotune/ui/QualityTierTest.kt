package com.cosmotune.ui

import com.cosmotune.music.data.NerdStats
import com.cosmotune.music.ui.components.SourceQuality
import com.cosmotune.ui.nowplaying.qualityTier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The tier the player's format line names, read off what the decoder is really fed. */
class QualityTierTest {

    private fun stream(mime: String?, kbps: Int? = null, rate: Int? = null, bits: Int? = null) =
        NerdStats.Snapshot(mimeType = mime, bitrateKbps = kbps, sampleRateHz = rate, channels = 2, bitDepth = bits)

    @Test fun losslessPastCdQualityIsHiRes() {
        assertEquals(SourceQuality.HI_RES_LOSSLESS, stream("audio/flac", rate = 96_000, bits = 24).qualityTier())
        assertEquals(SourceQuality.HI_RES_LOSSLESS, stream("audio/flac", rate = 44_100, bits = 24).qualityTier(), "24-bit alone is past CD")
        assertEquals(SourceQuality.HI_RES_LOSSLESS, stream("audio/flac", rate = 96_000, bits = 16).qualityTier(), "96 kHz alone is past CD")
    }

    @Test fun cdQualityLosslessIsLossless() {
        assertEquals(SourceQuality.LOSSLESS, stream("audio/flac", rate = 44_100, bits = 16).qualityTier())
        assertEquals(SourceQuality.LOSSLESS, stream("audio/flac", rate = 48_000, bits = 16).qualityTier(), "48 kHz is still CD-class")
    }

    @Test fun aLossyStreamIsNamedOnlyWhenItIsHighBitrate() {
        assertEquals(SourceQuality.HIGH_QUALITY, stream("audio/mp4a-latm", kbps = 320).qualityTier())
        assertNull(stream("audio/opus", kbps = 160).qualityTier())
        assertNull(stream("audio/mp4a-latm", kbps = 128).qualityTier())
    }

    @Test fun dolbyAtmosIsNeverCalledLossless() {
        assertEquals(SourceQuality.DOLBY_ATMOS, stream("audio/eac3-joc", kbps = 768).qualityTier())
    }

    @Test fun nothingIsClaimedBeforeTheDecoderHasMeasuredAnything() {
        assertNull(stream(null).qualityTier())
    }
}
