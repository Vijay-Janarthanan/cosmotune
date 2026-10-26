package com.cosmotune.providers

import org.junit.Test
import kotlin.test.*

class ProviderAudioQualityTest {
    @Test fun higherLosslessTierWinsAndHighDoesNotAccidentallyPickLosslessOrAtmos() {
        val tiers = listOf(ProviderQuality("LOSSLESS", "CD", kind = "lossless"),
            ProviderQuality("DOLBY_ATMOS", "Dolby Atmos"), ProviderQuality("HI_RES_LOSSLESS", "24-Bit", kind = "lossless"),
            ProviderQuality("HIGH", "High", kind = "lossy"))
        assertEquals("HI_RES_LOSSLESS", ProviderAudioQuality.choose(tiers, lossless = true))
        assertEquals("HIGH", ProviderAudioQuality.choose(tiers))
        assertFailsWith<IllegalStateException> { ProviderAudioQuality.choose(tiers.take(1)) }
    }

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
