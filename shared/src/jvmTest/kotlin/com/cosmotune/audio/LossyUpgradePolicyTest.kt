package com.cosmotune.audio

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LossyUpgradePolicyTest {
    @Test
    fun crossCodecBitratesDoNotProveAnUpgrade() {
        assertFalse(LossyUpgradePolicy.worthReplacing("aac", 320, "opus", 160))
        assertFalse(LossyUpgradePolicy.worthReplacing("audio/mp4", 320, "audio/webm; codecs=\"opus\"", 160))
        assertFalse(LossyUpgradePolicy.worthReplacing("mp3", 320, "aac", 128))
    }

    @Test
    fun sameCodecRequiresKnownBitratesAndASignificantGain() {
        assertTrue(LossyUpgradePolicy.worthReplacing("aac", 320, "audio/mp4", 128))
        assertTrue(LossyUpgradePolicy.worthReplacing("opus", 256, "audio/webm; codecs=\"opus\"", 128))
        assertFalse(LossyUpgradePolicy.worthReplacing("aac", 192, "aac", 128))
        assertFalse(LossyUpgradePolicy.worthReplacing("aac", 320, null, 128))
        assertFalse(LossyUpgradePolicy.worthReplacing("aac", 320, "aac", null))
        assertFalse(LossyUpgradePolicy.worthReplacing("aac", 128, "aac", 320))
    }
}
