package com.cosmotune.music.desktop

import com.cosmotune.room.RoomConnection
import com.cosmotune.room.voice.MicAccessResult
import com.cosmotune.room.voice.RoomVoiceUi
import com.cosmotune.ui.room.RoomUiState
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's room voice decisions: what the screen reads from the voice, where the microphone settings are on each system, and the
 * one-time Windows privacy hint. Nothing here builds an engine, opens a link or touches [DesktopPersistence] (the real registry).
 */
class DesktopRoomVoiceTest {
    private val base = RoomUiState(room = null, connection = RoomConnection.LIVE, selfUid = "me", positionMs = 0)

    @Test
    fun `the screen reads every voice field from the voice`() {
        val voice = RoomVoiceUi(
            available = true,
            speakingUids = setOf("me", "ana"),
            mutedHere = setOf("bo"),
            volumes = mapOf("ana" to 0.4f),
            connecting = setOf("cy"),
            micLive = true,
            deafened = true,
            micBlocked = true,
            micLevel = 0.3f,
            message = "The microphone is not available",
        )
        val state = DesktopRoomVoice.withVoice(base.copy(message = "kept"), voice)
        assertTrue(state.voiceAvailable)
        assertTrue(state.micLive)
        assertTrue(state.deafened)
        assertTrue(state.micBlocked)
        assertEquals(setOf("me", "ana"), state.speakingUids)
        assertEquals(setOf("bo"), state.mutedHere)
        assertEquals(mapOf("ana" to 0.4f), state.volumes)
        assertEquals(setOf("cy"), state.voiceConnecting)
        assertEquals(0.3f, state.micLevel)
        assertEquals("The microphone is not available", state.voiceMessage)
        assertEquals("kept", state.message) // the room's own message is not the voice's
    }

    @Test
    fun `this device is never muted, turned down or connecting to itself`() {
        val voice = RoomVoiceUi(available = true, mutedHere = setOf("me", "bo"), volumes = mapOf("me" to 0.2f), connecting = setOf("me"))
        val state = DesktopRoomVoice.withVoice(base, voice)
        assertEquals(setOf("bo"), state.mutedHere)
        assertTrue(state.volumes.isEmpty())
        assertTrue(state.voiceConnecting.isEmpty())
    }

    @Test
    fun `no voice means nothing live and no meter`() {
        val state = DesktopRoomVoice.withVoice(base, RoomVoiceUi())
        assertFalse(state.voiceAvailable)
        assertFalse(state.micLive)
        assertFalse(state.deafened)
        assertEquals(0f, state.micLevel)
        assertNull(state.voiceMessage)
        // a level left over from a mic just muted does not move the meter
        assertEquals(0f, DesktopRoomVoice.withVoice(base, RoomVoiceUi(available = true, micLevel = 0.5f)).micLevel)
    }

    @Test
    fun `the microphone settings page for each system`() {
        assertEquals("ms-settings:privacy-microphone", DesktopRoomVoice.micSettingsUri(windows = true, mac = false))
        assertEquals(
            "x-apple.systempreferences:com.apple.preference.security?Privacy_Microphone",
            DesktopRoomVoice.micSettingsUri(windows = false, mac = true),
        )
        assertNull(DesktopRoomVoice.micSettingsUri(windows = false, mac = false))
    }

    @Test
    fun `mic access is granted and opens the page, or quietly nothing on Linux`() = runBlocking {
        val opened = mutableListOf<String>()
        val windows = DesktopMicAccess(settingsUri = "ms-settings:privacy-microphone", open = { opened += it; Result.success(Unit) })
        assertEquals(MicAccessResult.GRANTED, windows.ensure())
        windows.openSettings()
        assertEquals(listOf("ms-settings:privacy-microphone"), opened)

        val linux = DesktopMicAccess(settingsUri = null, open = { opened += it; Result.success(Unit) })
        linux.openSettings()
        assertEquals(1, opened.size)
    }

    @Test
    fun `the privacy hint shows once, after the first unmute that went live, on Windows only`() {
        var shown = false
        val hint = DesktopMicHint(windows = true, shown = { shown }, markShown = { shown = true })
        assertNull(hint.afterUnmute(liveNow = false)) // an unmute that did not go live (voice off, mic refused) does not use it up
        assertFalse(shown)
        assertEquals(DesktopRoomVoice.MIC_PRIVACY_HINT, hint.afterUnmute(liveNow = true))
        assertTrue(shown)
        assertNull(hint.afterUnmute(liveNow = true))

        // the flag is per install: a later run that reads it as set says nothing
        assertNull(DesktopMicHint(windows = true, shown = { true }, markShown = { error("not again") }).afterUnmute(liveNow = true))

        var macShown = false
        assertNull(DesktopMicHint(windows = false, shown = { macShown }, markShown = { macShown = true }).afterUnmute(liveNow = true))
        assertFalse(macShown)
    }
}
