package com.cosmotune.music.data.room.voice

import com.cosmotune.room.voice.PeerState
import com.cosmotune.room.voice.PeerVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceLevelsTest {
    @Test
    fun `silence and nonsense read as nothing`() {
        assertEquals(0f, VoiceLevels.normalise(null))
        assertEquals(0f, VoiceLevels.normalise(0.0))
        assertEquals(0f, VoiceLevels.normalise(-1.0))
        assertEquals(0f, VoiceLevels.normalise(Double.NaN))
        // noise-suppressed room tone, about -60 dB
        assertEquals(0f, VoiceLevels.normalise(0.001))
    }

    @Test
    fun `full scale and beyond read as one`() {
        assertEquals(1f, VoiceLevels.normalise(1.0))
        assertEquals(1f, VoiceLevels.normalise(3.0))
        assertEquals(1f, VoiceLevels.normalise(0.6))
    }

    @Test
    fun `quiet speech already lights the speaking ring and louder speech reads louder`() {
        val quiet = VoiceLevels.normalise(0.02) // about -34 dB
        val normal = VoiceLevels.normalise(0.1) // -20 dB
        assertTrue(quiet > PeerVoice.SPEAKING_LEVEL)
        assertTrue(normal > quiet)
        assertEquals(0.625f, normal, 0.001f)
    }

    @Test
    fun `the floor of the scale stays below the speaking threshold`() {
        assertTrue(VoiceLevels.normalise(0.005) < PeerVoice.SPEAKING_LEVEL) // -46 dB
    }

    @Test
    fun `levels rise at once and fade over a few polls`() {
        assertEquals(0.8f, VoiceLevels.smooth(0.1f, 0.8f))
        assertEquals(0.4f, VoiceLevels.smooth(0.8f, 0f))
        assertEquals(0.5f, VoiceLevels.smooth(0.8f, 0.5f))
        var level = 0.8f
        repeat(10) { level = VoiceLevels.smooth(level, 0f) }
        assertEquals(0f, level)
    }

    @Test
    fun `stats give the remote level from inbound audio and the local one from the microphone source`() {
        val (remote, local) = VoiceLevels.levelsOf(
            listOf(
                "inbound-rtp" to mapOf("kind" to "audio", "audioLevel" to 0.1),
                "media-source" to mapOf("kind" to "audio", "audioLevel" to 1.0),
                "inbound-rtp" to mapOf("kind" to "video", "audioLevel" to 1.0),
                "candidate-pair" to mapOf("state" to "succeeded"),
            ),
        )
        assertEquals(0.625f, remote!!, 0.001f)
        assertEquals(1f, local!!)
    }

    @Test
    fun `absent reports are told apart from silence`() {
        val (remote, local) = VoiceLevels.levelsOf(listOf("inbound-rtp" to mapOf("kind" to "audio")))
        assertEquals(0f, remote)
        assertNull(local)
        assertEquals(null to null, VoiceLevels.levelsOf(emptyList()))
    }

    @Test
    fun `connection states map onto the room's`() {
        assertEquals(PeerState.NEW, VoiceLevels.peerState("NEW"))
        assertEquals(PeerState.CONNECTING, VoiceLevels.peerState("CONNECTING"))
        assertEquals(PeerState.CONNECTED, VoiceLevels.peerState("CONNECTED"))
        // a dropped connection may come back by itself; only FAILED makes the mesh start over
        assertEquals(PeerState.CONNECTING, VoiceLevels.peerState("DISCONNECTED"))
        assertEquals(PeerState.FAILED, VoiceLevels.peerState("FAILED"))
        assertEquals(PeerState.CLOSED, VoiceLevels.peerState("CLOSED"))
        assertEquals(PeerState.CONNECTING, VoiceLevels.peerState("SOMETHING_NEW"))
    }

    @Test
    fun `volume is clamped into a gain`() {
        assertEquals(1.0, VoiceLevels.gain(1f), 0.0)
        assertEquals(0.5, VoiceLevels.gain(0.5f), 0.0)
        assertEquals(0.0, VoiceLevels.gain(-2f), 0.0)
        assertEquals(1.0, VoiceLevels.gain(4f), 0.0)
        assertEquals(1.0, VoiceLevels.gain(Float.NaN), 0.0)
    }

    @Test
    fun `stun only, the two servers the room expects`() {
        assertEquals(listOf("stun:stun.cloudflare.com:3478", "stun:stun.l.google.com:19302"), AndroidVoiceEngine.STUN_SERVERS)
    }
}
