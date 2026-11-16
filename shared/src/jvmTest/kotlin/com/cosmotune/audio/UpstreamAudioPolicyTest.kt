package com.cosmotune.audio
import kotlin.test.*
class UpstreamAudioPolicyTest {
    @Test fun storedPolicyAndUpgradePermissions() {
        assertEquals(PlaybackStartPolicy.FAST_START, PlaybackStartPolicy.fromStored("future"))
        assertEquals(PlaybackStartPolicy.BEST_QUALITY_FIRST, PlaybackStartPolicy.fromStored("BEST_QUALITY_FIRST"))
        assertTrue(PlaybackStartPolicy.FAST_START.allowsAutomaticUpgrade)
        assertFalse(PlaybackStartPolicy.NO_UPGRADE.allowsAutomaticUpgrade)
        assertFalse(PlaybackStartPolicy.BEST_QUALITY_FIRST.allowsAutomaticUpgrade)
    }
    @Test fun decoderTimingMustProveTheSameRecording() {
        assertTrue(RecordingUpgradePolicy.compatibleDuration(180_000,181_000))
        assertFalse(RecordingUpgradePolicy.compatibleDuration(180_000,189_000))
        assertFalse(RecordingUpgradePolicy.compatibleDuration(180_000,null))
        assertFalse(RecordingUpgradePolicy.compatibleDuration(null,180_000))
    }
    @Test fun flacTicksAreConvertedExactlyWithoutOverflowOrTruncation() {
        assertEquals(88_200L, FlacTiming.totalSamples(2_000,44_100,1_000))
        assertEquals(24_576L, FlacTiming.totalSamples(24_576,44_100,44_100))
        assertFailsWith<IllegalArgumentException> { FlacTiming.totalSamples(1,44_100,1_000) }
        assertFailsWith<IllegalArgumentException> { FlacTiming.totalSamples(Long.MAX_VALUE,192_000,1) }
        assertFailsWith<IllegalArgumentException> { FlacTiming.totalSamples(100,48_000,0) }
    }
}
