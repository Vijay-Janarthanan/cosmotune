package com.cosmotune.music.data

import com.cosmotune.music.data.sources.StreamFormat
import kotlin.test.*

class AudioTelemetryGenerationTest {
    @AfterTest fun clear() { NerdStats.forgetLastSession() }
    private fun snapshot(id: String?) = NerdStats.Snapshot("audio/opus", 132, 48_000, 2, mediaId = id)
    @Test fun aLateSnapshotCannotRepopulateStatisticsAfterATrackTransition() {
        NerdStats.onTrackTransition("one")
        val generation = NerdStats.currentGeneration
        assertTrue(NerdStats.publish(snapshot("one"), generation))
        NerdStats.onTrackTransition("two")
        assertFalse(NerdStats.publish(snapshot("one"), generation))
        assertFalse(NerdStats.publish(snapshot("one"), NerdStats.currentGeneration))
        assertFalse(NerdStats.publish(snapshot(null), NerdStats.currentGeneration))
        assertNull(NerdStats.current.value)
        assertTrue(NerdStats.publish(snapshot("two"), NerdStats.currentGeneration))
    }
    @Test fun streamUpgradeDoesNotInheritThePreviousBitrateOrARequestedTierBadge() {
        NerdStats.onStreamPicked("song", 160)
        NerdStats.onSourceStream("song", StreamFormat("flac"))
        assertNull(NerdStats.sourceBitrate("song") { error("lossless should not need an encoded estimate") })
        NerdStats.onSourceStream("song", StreamFormat("aac"))
        assertNull(NerdStats.sourceBitrate("song") { null })
        NerdStats.onSourceStream("song", StreamFormat("aac", 320, kbpsInferred = true))
        assertEquals(128 to TelemetryProvenance.MEASURED, NerdStats.sourceBitrate("song") { 128 })
        assertEquals(320 to TelemetryProvenance.DERIVED, NerdStats.sourceBitrate("song") { null })
        assertFalse(NerdStats.Snapshot("audio/aac", 320, 44_100, 2, bitrateProvenance = TelemetryProvenance.DERIVED).isHiQuality)
    }
}
