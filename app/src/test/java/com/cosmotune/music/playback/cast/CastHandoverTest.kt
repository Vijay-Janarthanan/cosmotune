package com.cosmotune.music.playback.cast

import com.cosmotune.music.playback.cast.CastFormatSupport.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Cast hand-overs (phone to receiver, and back to the device) and the receiver format gate. */
class CastHandoverTest {

    @Test
    fun aNewSessionLoadsThePhonesTrackAtItsPositionAndPlaysOnlyIfThePhoneWas() {
        val playing = CastHandover.LocalState("a", 61_000, playing = true)
        assertEquals(CastHandover.Start.Load("a", 61_000, play = true), CastHandover.onStart(playing, resumed = false, receiverMediaId = null))
        val paused = playing.copy(playing = false)
        assertEquals(CastHandover.Start.Load("a", 61_000, play = false), CastHandover.onStart(paused, resumed = false, receiverMediaId = "a"))
        // A negative position (nothing measured yet) starts at the beginning.
        assertEquals(CastHandover.Start.Load("a", 0, true), CastHandover.onStart(playing.copy(positionMs = -1), false, null))
    }

    @Test
    fun aResumedSessionAlreadyOnTheSameTrackIsLeftPlaying() {
        val local = CastHandover.LocalState("a", 10_000, playing = false)
        assertEquals(CastHandover.Start.KeepReceiver, CastHandover.onStart(local, resumed = true, receiverMediaId = "a"))
        // On another track, or with nothing on the phone, the phone's track is loaded instead.
        assertTrue(CastHandover.onStart(local, resumed = true, receiverMediaId = "b") is CastHandover.Start.Load)
        assertTrue(CastHandover.onStart(local.copy(currentMediaId = null), resumed = true, receiverMediaId = null) is CastHandover.Start.Load)
    }

    @Test
    fun returningToTheDeviceRestoresTheReceiversTrackAndSecond() {
        val plan = CastHandover.onEnd(
            end = CastHandover.ReceiverEnd("c", 95_500, playing = true),
            localQueue = listOf("a", "b", "c", "d"),
            localCurrentIndex = 1,
            listenerChoseThisDevice = true,
        )
        assertEquals(CastHandover.Return(queueIndex = 2, positionMs = 95_500, play = true), plan)
    }

    @Test
    fun soundFollowsOnlyWhenTheListenerChoseThisDeviceAndTheReceiverWasPlaying() {
        val queue = listOf("a", "b")
        // Receiver switched off or Wi-Fi dropped: the place is kept, the phone stays quiet.
        assertEquals(false, CastHandover.onEnd(CastHandover.ReceiverEnd("a", 1, true), queue, 0, listenerChoseThisDevice = false).play)
        // Chosen, but the receiver was paused: still quiet.
        assertEquals(false, CastHandover.onEnd(CastHandover.ReceiverEnd("a", 1, false), queue, 0, listenerChoseThisDevice = true).play)
    }

    @Test
    fun aTrackQueuedTwiceReturnsToTheCopyNearestThePhonesRowAndAnUnknownTrackKeepsTheRow() {
        val queue = listOf("x", "a", "y", "z", "a")
        assertEquals(4, CastHandover.onEnd(CastHandover.ReceiverEnd("a", 0, false), queue, 3, false).queueIndex)
        assertEquals(1, CastHandover.onEnd(CastHandover.ReceiverEnd("a", 0, false), queue, 0, false).queueIndex)
        assertEquals(null, CastHandover.onEnd(CastHandover.ReceiverEnd("gone", 5_000, true), queue, 2, true).queueIndex)
        assertEquals(null, CastHandover.onEnd(CastHandover.ReceiverEnd(null, 5_000, true), queue, 2, true).queueIndex)
    }

    @Test
    fun receiverFormatsFromGooglesListPassAndTheRestAreRefusedBeforeSending() {
        listOf("audio/mp4" to "m4a", "audio/webm" to null, "audio/mpeg" to "mp3", "audio/ogg" to "opus", "audio/wav" to "wav", "audio/flac" to "flac")
            .forEach { (mime, ext) -> assertTrue("$mime/$ext", CastFormatSupport.check(mime, ext) is Verdict.Supported) }
        listOf("dsf", "dff", "aiff", "ape", "wv").forEach { ext ->
            assertTrue(ext, CastFormatSupport.check("audio/mp4", ext) is Verdict.Unsupported)
        }
        assertTrue(CastFormatSupport.check("audio/x-alac", null) is Verdict.Unsupported)
        // ALAC hides inside an .m4a; only the codec says so.
        assertTrue(CastFormatSupport.check("audio/mp4", "m4a", codec = "alac") is Verdict.Unsupported)
        // FLAC is listed up to 96 kHz.
        assertTrue(CastFormatSupport.check("audio/flac", "flac", sampleRateHz = 96_000) is Verdict.Supported)
        assertEquals(
            Verdict.Unsupported("FLAC above 96 kHz is not a Cast receiver format"),
            CastFormatSupport.check("audio/flac", "flac", sampleRateHz = 192_000),
        )
        assertEquals(Verdict.Supported("audio/webm"), CastFormatSupport.check("audio/webm; codecs=\"opus\"", null))
    }
}
