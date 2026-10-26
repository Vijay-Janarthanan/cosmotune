package com.cosmotune.music.data

import com.cosmotune.music.data.sources.*
import kotlin.test.*

class AudioSourceComparisonsTest {
    @Test fun accessFailureIsUsefulWithoutRetainingAnExceptionOrSecretUrl() {
        val target = TrackMatcher.Target("private-failure", "Artist")
        val ticket = AudioSourceComparisons.start(target, "source", "Provider")
        AudioSourceComparisons.failed(ticket, IllegalStateException("HTTP 403 https://private.example/token=secret"))
        AudioSourceComparisons.finish(ticket, AudioSourceComparisons.Status.NO_MATCH)
        val report = AudioSourceComparisons.state.value.getValue(AudioSourceComparisons.key(target))
        assertEquals(AudioSourceComparisons.Status.ACCESS_REQUIRED, report.offers.single().status)
        assertFalse(report.toString().contains("secret"))
    }

    @Test fun aLateLookupCannotOverwriteANewerAttemptForTheSameSource() {
        val target = TrackMatcher.Target("comparison-generation", "Artist")
        val old = AudioSourceComparisons.start(target, "source", "Provider")
        val fresh = AudioSourceComparisons.start(target, "source", "Provider")
        AudioSourceComparisons.finish(fresh, AudioSourceComparisons.Status.AVAILABLE, listOf(StreamFormat("flac", bitDepth = 24)))
        AudioSourceComparisons.finish(old, AudioSourceComparisons.Status.NO_MATCH)
        val offer = AudioSourceComparisons.state.value.getValue(AudioSourceComparisons.key(target)).offers.single()
        assertEquals(AudioSourceComparisons.Status.AVAILABLE, offer.status)
        assertEquals(24, offer.formats.single().bitDepth)
    }

    @Test fun reportsAreBoundedAndRecordingEditionsRemainSeparate() {
        repeat(30) { AudioSourceComparisons.start(TrackMatcher.Target("bounded-$it", "Artist"), "source", "Provider") }
        assertTrue(AudioSourceComparisons.state.value.size <= 12)
        val album = TrackMatcher.Target("Song", "Artist", album = "Original")
        assertNotEquals(AudioSourceComparisons.key(album), AudioSourceComparisons.key(album.copy(album = "Live")))
        assertNotEquals(AudioSourceComparisons.key(album), AudioSourceComparisons.key(album.copy(isExplicit = true)))
    }

    @Test fun losslessDepthAndRateWinButDifferentLossyCodecsAreNotRankedByBitrate() {
        assertTrue(StreamQualityPolicy.better(StreamFormat("flac", sampleRateHz = 96000, bitDepth = 24), StreamFormat("alac", sampleRateHz = 44100, bitDepth = 16)))
        assertFalse(StreamQualityPolicy.better(StreamFormat("flac", sampleRateHz = 44100, bitDepth = 16), StreamFormat("flac", sampleRateHz = 96000, bitDepth = 24)))
        assertTrue(StreamQualityPolicy.better(StreamFormat("aac", 320), StreamFormat("mp4", 128)))
        assertFalse(StreamQualityPolicy.better(StreamFormat("mp3", 320), StreamFormat("opus", 160)))
        assertFalse(NerdStats.isDolbyAtmosMime("audio/eac3"))
        assertTrue(NerdStats.isDolbyAtmosMime("audio/eac3-joc"))
    }
}
