package com.cosmotune.music.data.room.voice

import com.cosmotune.music.R
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.voice.MicAccessResult
import com.cosmotune.room.voice.RoomVoiceUi
import com.cosmotune.ui.room.RoomUiState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The phone's room voice glue without Android: the permission's answers, and the voice state in the room screen's fields. */
class PhoneRoomVoiceTest {
    private class FakePermission(var granted: Boolean, val answer: Boolean, val blocked: Boolean) {
        var asked = 0
        var settingsOpened = 0
        val access = AndroidMicAccess(
            granted = { granted },
            request = { asked++; answer.also { if (it) granted = true } },
            blocked = { blocked },
            settings = { settingsOpened++ },
        )
    }

    @Test
    fun `already granted shows no dialog`() = runBlocking {
        val permission = FakePermission(granted = true, answer = false, blocked = true)
        assertEquals(MicAccessResult.GRANTED, permission.access.ensure())
        assertEquals(0, permission.asked)
    }

    @Test
    fun `a yes in the dialog grants`() = runBlocking {
        val permission = FakePermission(granted = false, answer = true, blocked = false)
        assertEquals(MicAccessResult.GRANTED, permission.access.ensure())
        assertEquals(1, permission.asked)
        // asked once: the next unmute finds it granted
        assertEquals(MicAccessResult.GRANTED, permission.access.ensure())
        assertEquals(1, permission.asked)
    }

    @Test
    fun `a first no is denied and a final one is blocked`() = runBlocking {
        assertEquals(MicAccessResult.DENIED, FakePermission(granted = false, answer = false, blocked = false).access.ensure())
        assertEquals(MicAccessResult.BLOCKED, FakePermission(granted = false, answer = false, blocked = true).access.ensure())
    }

    @Test
    fun `open settings goes to the permission`() {
        val permission = FakePermission(granted = false, answer = false, blocked = true)
        permission.access.openSettings()
        assertEquals(1, permission.settingsOpened)
    }

    private val base = RoomUiState(room = null, connection = RoomConnection.LIVE, selfUid = "me", positionMs = 0L, message = "room message")

    private fun text(id: Int): String = "res:$id"

    @Test
    fun `every voice field comes from the voice state`() {
        val voice = RoomVoiceUi(
            available = true,
            speakingUids = setOf("me", "a"),
            mutedHere = setOf("b"),
            volumes = mapOf("a" to 0.4f),
            connecting = setOf("c"),
            micLive = true,
            deafened = true,
            micBlocked = false,
            micLevel = 0.6f,
        )
        val state = PhoneRoomVoice.applyTo(base, voice, ::text)
        assertTrue(state.voiceAvailable)
        assertTrue(state.micLive)
        assertTrue(state.deafened)
        assertEquals(setOf("me", "a"), state.speakingUids)
        assertEquals(setOf("b"), state.mutedHere)
        assertEquals(mapOf("a" to 0.4f), state.volumes)
        assertEquals(setOf("c"), state.voiceConnecting)
        assertFalse(state.micBlocked)
        assertEquals(0.6f, state.micLevel)
        assertNull(state.voiceMessage)
        // the room's own fields are left as they were
        assertEquals("room message", state.message)
        assertEquals("me", state.selfUid)
    }

    @Test
    fun `no room means no voice`() {
        val state = PhoneRoomVoice.applyTo(base.copy(micLive = true, voiceAvailable = true), RoomVoiceUi(), ::text)
        assertFalse(state.voiceAvailable)
        assertFalse(state.micLive)
        assertEquals(0f, state.micLevel)
    }

    @Test
    fun `the shared messages are said in the phone's strings`() {
        fun shown(message: String) = PhoneRoomVoice.applyTo(base, RoomVoiceUi(message = message), ::text).voiceMessage
        assertEquals(text(R.string.room_voice_mic_denied), shown("The microphone permission was not given"))
        assertEquals(text(R.string.room_voice_mic_blocked), shown("The microphone is blocked in the system settings"))
        assertEquals(text(R.string.room_voice_mic_unavailable), shown("The microphone is not available"))
        assertEquals(text(R.string.room_voice_unavailable), shown("Voice is not available on this device"))
        // one the phone has no string for is still shown
        assertEquals("Something new", shown("Something new"))
    }

    @Test
    fun `a blocked microphone says so with its message`() {
        val state = PhoneRoomVoice.applyTo(
            base,
            RoomVoiceUi(available = true, micBlocked = true, message = "The microphone is blocked in the system settings"),
            ::text,
        )
        assertTrue(state.micBlocked)
        assertEquals(text(R.string.room_voice_mic_blocked), state.voiceMessage)
    }
}
