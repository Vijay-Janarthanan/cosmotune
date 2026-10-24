package com.cosmotune.providers

import org.junit.Test
import kotlin.test.*

class ProviderAudioQualityTest {
    @Test fun connectionCapsCannotSelectAnUnboundedLosslessOrHigherBitrateTier() {
        val qualities = listOf(ProviderQuality("FLAC", kind = "lossless"), ProviderQuality("AAC_320"), ProviderQuality("AAC_96"))
        assertEquals("FLAC", ProviderAudioQuality.choose(qualities, lossless = true))
        assertEquals("AAC_320", ProviderAudioQuality.choose(qualities))
        assertEquals("AAC_96", ProviderAudioQuality.choose(qualities, maxKbps = 128, lossless = true))
        assertFailsWith<IllegalStateException> { ProviderAudioQuality.choose(qualities, maxKbps = 48) }
        assertEquals("AAC_320", ProviderAudioQuality.choose(qualities.drop(1), lossless = true))
        assertEquals(320, ProviderAudioQuality.bitrate("320kbps"))
    }
}
