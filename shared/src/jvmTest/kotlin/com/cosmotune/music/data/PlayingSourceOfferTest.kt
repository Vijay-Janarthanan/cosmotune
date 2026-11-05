package com.cosmotune.music.data

import com.cosmotune.music.data.sources.AudioSourceComparisons
import com.cosmotune.music.data.sources.TrackMatcher
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlayingSourceOfferTest {
    @Test fun failureCodesRemainVisibleUntilARealStreamResolves() {
        val target = TrackMatcher.Target("HTTP fixture", "Artist")
        val ticket = AudioSourceComparisons.start(target, "fixture", "Fixture")
        AudioSourceComparisons.failed(ticket, IllegalStateException("Provider error · HTTP 404"))
        AudioSourceComparisons.finish(ticket, AudioSourceComparisons.Status.UNAVAILABLE)
        assertEquals(404, AudioSourceComparisons.state.value[AudioSourceComparisons.key(target)]!!.offers.single().httpStatus)
        AudioSourceComparisons.finish(ticket, AudioSourceComparisons.Status.AVAILABLE)
        assertNull(AudioSourceComparisons.state.value[AudioSourceComparisons.key(target)]!!.offers.single().httpStatus)
    }

    @Test fun aFailedPackageWithTheSameNameIsNotThePlayingNativeSource() {
        val failed = AudioSourceComparisons.Offer("sflx-youtube-music", "YouTube Music", AudioSourceComparisons.Status.REQUEST_FAILED)
        val report = AudioSourceComparisons.Report(listOf(failed))
        assertNull(AudioSourceComparisons.playingOffer(report, "youtube", "YouTube Music"))
        assertTrue(AudioSourceComparisons.playingOffer(report, failed.id, "YouTube Music") === failed)
    }

    @Test fun theKnownSingerAliasDoesNotTurnASharedFirstNameIntoAMatch() {
        assertTrue(TrackMatcher.sharesArtist("Srikanth Deva, Udit Narayanan & Shalini Singh", "Udit Narayan, Pop Shalini, Sri"))
        assertFalse(TrackMatcher.sharesArtist("Udit Narayanan", "Udit Sharma"))
        assertFalse(TrackMatcher.sharesArtist("Udit Narayanan", "Narayan"))
    }
}
