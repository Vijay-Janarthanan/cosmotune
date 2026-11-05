package com.cosmotune.ui.room

import com.cosmotune.room.RoomConnection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RoomVoiceTest {
    private fun state(
        voice: Boolean = true,
        micLive: Boolean = false,
        blocked: Boolean = false,
        deafened: Boolean = false,
        speaking: Set<String> = emptySet(),
        mutedHere: Set<String> = emptySet(),
        volumes: Map<String, Float> = emptyMap(),
    ) = RoomUiState(
        room = null, connection = RoomConnection.LIVE, selfUid = "ravi", positionMs = 0, micLive = micLive, voiceAvailable = voice,
        deafened = deafened, speakingUids = speaking, mutedHere = mutedHere, volumes = volumes, micBlocked = blocked,
    )

    @Test fun theMicIsLiveOnlyWhenVoiceExistsThePermissionIsThereAndItWasTurnedOn() {
        assertEquals(RoomMicMode.MUTED, roomMicMode(state()))
        assertEquals(RoomMicMode.LIVE, roomMicMode(state(micLive = true)))
        assertEquals(RoomMicMode.UNAVAILABLE, roomMicMode(state(voice = false, micLive = true)), "no voice: never live")
        assertEquals(RoomMicMode.BLOCKED, roomMicMode(state(micLive = true, blocked = true)), "blocked: never live")
        assertEquals(RoomMicMode.UNAVAILABLE, roomMicMode(state(voice = false, blocked = true)), "no voice explains first")
    }

    @Test fun theRingShowsForSomeoneSpeakingThatThisDeviceHears() {
        val s = state(speaking = setOf("anu", "mira"), mutedHere = setOf("mira"))
        assertTrue(s.showsSpeaking("anu"))
        assertFalse(s.showsSpeaking("mira"), "muted here: no ring")
        assertFalse(s.showsSpeaking("ravi"), "not speaking")
        assertFalse(state(voice = false, speaking = setOf("anu")).showsSpeaking("anu"), "no voice, no ring")
    }

    @Test fun deafenHidesOthersRingsButNotYourOwn() {
        val s = state(deafened = true, speaking = setOf("anu", "ravi"))
        assertFalse(s.showsSpeaking("anu"))
        assertTrue(s.showsSpeaking("ravi"), "the room still hears you")
    }

    @Test fun aVolumeIsFullUntilSetAndHeldToTheRange() {
        val s = state(volumes = mapOf("anu" to 0.4f, "mira" to 3f, "odd" to Float.NaN))
        assertEquals(0.4f, s.volumeOf("anu"))
        assertEquals(1f, s.volumeOf("mira"))
        assertEquals(1f, s.volumeOf("odd"))
        assertEquals(1f, s.volumeOf("nobody"))
        assertEquals(0f, roomVolume(-1f))
    }

    @Test fun aVolumeReadsAsAWholePercent() {
        assertEquals(0, roomVolumePercent(0f))
        assertEquals(50, roomVolumePercent(0.5f))
        assertEquals(67, roomVolumePercent(0.666f))
        assertEquals(100, roomVolumePercent(1f))
        assertEquals(100, roomVolumePercent(1.7f))
        assertEquals(0, roomVolumePercent(-0.2f))
    }

    @Test fun theMeterLightsOneToThreeBars() {
        assertEquals(1, roomMicMeterBars(0f))
        assertEquals(1, roomMicMeterBars(Float.NaN))
        assertEquals(2, roomMicMeterBars(0.3f))
        assertEquals(3, roomMicMeterBars(0.5f))
        assertEquals(3, roomMicMeterBars(2f))
    }
}
