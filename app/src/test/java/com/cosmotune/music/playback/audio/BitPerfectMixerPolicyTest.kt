package com.cosmotune.music.playback.audio

import android.media.AudioFormat
import com.cosmotune.music.playback.audio.BitPerfectMixerPolicy.MixerFormat
import com.cosmotune.music.playback.audio.BitPerfectMixerPolicy.Status
import com.cosmotune.music.playback.audio.BitPerfectMixerPolicy.TrackFormat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Android 14's bit-perfect USB mixer: when it is asked for, and when the readout may claim it. */
class BitPerfectMixerPolicyTest {

    private val pcm16At44 = MixerFormat(AudioFormat.ENCODING_PCM_16BIT, 44_100, 2, bitPerfect = true)
    private val pcm32At96 = MixerFormat(AudioFormat.ENCODING_PCM_32BIT, 96_000, 2, bitPerfect = true)
    private val mixedFloat = MixerFormat(AudioFormat.ENCODING_PCM_FLOAT, 96_000, 2, bitPerfect = false)
    private val dac = listOf(mixedFloat, pcm16At44, pcm32At96)
    private val cdTrack = TrackFormat(AudioFormat.ENCODING_PCM_16BIT, 44_100, 2)

    @Test
    fun offByDefaultAndOffAsksForNothing() {
        val state = BitPerfectMixerPolicy.decide(false, 35, true, dac, cdTrack)
        assertEquals(Status.OFF, state.status)
        assertNull(state.requested)
        assertFalse(state.confirmed)
    }

    @Test
    fun olderAndroidAndNonUsbRoutesFallBackWithTheirReason() {
        assertEquals(Status.ANDROID_TOO_OLD, BitPerfectMixerPolicy.decide(true, 33, true, dac, cdTrack).status)
        assertEquals(Status.NOT_USB, BitPerfectMixerPolicy.decide(true, 34, false, dac, cdTrack).status)
        assertEquals(
            Status.NO_BIT_PERFECT_FORMATS,
            BitPerfectMixerPolicy.decide(true, 34, true, listOf(mixedFloat), cdTrack).status,
        )
        assertEquals(Status.NO_TRACK_YET, BitPerfectMixerPolicy.decide(true, 34, true, dac, null).status)
    }

    @Test
    fun requestsOnlyTheBitPerfectFormatThatExactlyMatchesTheOpenTrack() {
        val state = BitPerfectMixerPolicy.decide(true, 34, true, dac, cdTrack)
        assertEquals(Status.REQUESTED_UNCONFIRMED, state.status)
        assertEquals(pcm16At44, state.requested)
        // A request is not confirmation; the readout must not claim it yet.
        assertFalse(state.confirmed)
        assertEquals(listOf(pcm16At44, pcm32At96), state.bitPerfectFormats)
    }

    @Test
    fun aFloatTrackForAHiResSourceDoesNotMatchAnIntegerOnlyDac() {
        // Media3 writes 24-bit sources as float; a DAC listing PCM32 alone cannot take it bit-perfect.
        val floatTrack = TrackFormat(AudioFormat.ENCODING_PCM_FLOAT, 96_000, 2)
        val state = BitPerfectMixerPolicy.decide(true, 34, true, dac, floatTrack)
        assertEquals(Status.NO_MATCHING_FORMAT, state.status)
        assertNull(state.requested)
        assertTrue(state.detail, state.detail.contains("PCM32 96.0 kHz"))
        // Rate and channel count have to match as well as the encoding.
        assertEquals(
            Status.NO_MATCHING_FORMAT,
            BitPerfectMixerPolicy.decide(true, 34, true, dac, cdTrack.copy(sampleRateHz = 48_000)).status,
        )
        assertEquals(
            Status.NO_MATCHING_FORMAT,
            BitPerfectMixerPolicy.decide(true, 34, true, dac, cdTrack.copy(channelCount = 1)).status,
        )
    }

    @Test
    fun confirmationNeedsABitPerfectReadBackInTheTracksExactFormat() {
        assertTrue(BitPerfectMixerPolicy.confirmed(pcm16At44, cdTrack, trackOpenedAfterRequest = true))
        assertFalse(BitPerfectMixerPolicy.confirmed(null, cdTrack, true))
        assertFalse(BitPerfectMixerPolicy.confirmed(pcm16At44, null, true))
        assertFalse(BitPerfectMixerPolicy.confirmed(pcm16At44.copy(bitPerfect = false), cdTrack, true))
        assertFalse(BitPerfectMixerPolicy.confirmed(pcm32At96, cdTrack, true))
    }

    @Test
    fun aTrackThatWasAlreadyOpenWhenThePreferenceWasSetIsNotConfirmed() {
        // The preference shapes an output only when one opens; the track playing at the time of
        // the request may still be mixed, however the read-back looks.
        assertFalse(BitPerfectMixerPolicy.confirmed(pcm16At44, cdTrack, trackOpenedAfterRequest = false))
    }

    @Test
    fun theConfirmedStateNamesTheFormat() {
        val confirmed = BitPerfectMixerPolicy.State(Status.CONFIRMED, listOf(pcm16At44), pcm16At44, deviceId = 7)
        assertTrue(confirmed.confirmed)
        assertEquals("Confirmed by Android · PCM16 / 44.1 kHz / 2 ch", confirmed.detail)
    }
}
