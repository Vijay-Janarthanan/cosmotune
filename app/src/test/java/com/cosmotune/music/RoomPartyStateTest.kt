package com.cosmotune.music

import com.cosmotune.music.data.listentogether.ListenTogether
import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.room.PhoneRooms
import com.cosmotune.music.data.room.RoomPartyState
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomException
import com.cosmotune.room.RoomPreview
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** The room in the party shape the phone's player integration and screens read, and the messages for what went wrong. */
class RoomPartyStateTest {

    private val track = PartyTrack("v1", "Song", "Artist", durationMs = 180_000)

    private fun snapshot(hostOnly: Boolean = false) = PartySnapshot(
        code = "ABC234",
        maxMembers = 5,
        hostOnlyControl = hostOnly,
        members = listOf(
            PartyMember("host-uid", "host-uid", "Host", isHost = true, connected = true),
            PartyMember("me-uid", "me-uid", "Me", connected = true),
        ),
        playback = PartyPlayback(seq = 7, track = track, isPlaying = true, positionMs = 10_000, anchorMs = 100_000),
        queue = PartyQueue(seq = 3, index = 0, items = listOf(track)),
    )

    @Test
    fun `a joined room reads as a party this device is in`() {
        val state = RoomPartyState.of(snapshot(), "me-uid", RoomConnection.LIVE, clockSynced = true, roundTripMs = 80, serverNowMs = 105_000)
        assertTrue(state.inParty)
        assertEquals("ABC234", state.code)
        assertEquals("me-uid", state.you?.memberId)
        assertEquals(ListenTogether.Connection.LIVE, state.connection)
        assertEquals(7, state.playback.seq)
        assertEquals(3, state.queue.seq)
        // the rough playhead before the clock is trusted: position plus the time since the anchor
        assertEquals(15_000, state.playback.effectivePositionMs)
    }

    @Test
    fun `host-only control locks a listener and never the host`() {
        val listener = RoomPartyState.of(snapshot(hostOnly = true), "me-uid", RoomConnection.LIVE, true, 0, 0)
        assertTrue(listener.controlsLocked)
        val host = RoomPartyState.of(snapshot(hostOnly = true), "host-uid", RoomConnection.LIVE, true, 0, 0)
        assertFalse(host.controlsLocked)
    }

    @Test
    fun `a reconnecting room is still a party, just not live`() {
        val state = RoomPartyState.of(snapshot(), "me-uid", RoomConnection.RECONNECTING, false, 0, 0)
        assertTrue(state.inParty)
        assertEquals(ListenTogether.Connection.CONNECTING, state.connection)
    }

    @Test
    fun `each refusal has its own short message`() {
        fun reason(r: RoomException.Reason) = RoomPartyState.messageFor(RoomException(r, "x"))
        assertEquals(R.string.room_err_not_found, reason(RoomException.Reason.NOT_FOUND))
        assertEquals(R.string.room_err_full, reason(RoomException.Reason.FULL))
        assertEquals(R.string.room_err_offline, reason(RoomException.Reason.OFFLINE))
        assertEquals(R.string.room_err_denied, reason(RoomException.Reason.DENIED))
        assertEquals(R.string.room_err_expired, reason(RoomException.Reason.EXPIRED))
        assertEquals(R.string.listen_together_host_only_notice, reason(RoomException.Reason.HOST_ONLY))
        assertEquals(R.string.room_err_other, reason(RoomException.Reason.OTHER))
        assertEquals(R.string.room_err_offline, RoomPartyState.messageFor(IOException("down")))
        assertEquals(R.string.room_err_sign_in, RoomPartyState.messageFor(PhoneRooms.SignInFailed(IllegalStateException("no"))))
        assertEquals(R.string.room_err_offline, RoomPartyState.messageFor(PhoneRooms.SignInFailed(IOException("down"))))
    }

    @Test
    fun `the join sheet sees who is inside and whether it is full`() {
        val preview = RoomPartyState.preview(RoomPreview("ABC234", "Host", 5, 5, listOf("Host", "Ann", "Ben", "Cy", "Di")))
        assertTrue(preview.isFull)
        assertEquals("Host", preview.hostName)
        assertEquals(5, preview.members.size)
        assertTrue(preview.members.first().isHost)
    }

    @Test
    fun `a guest is told apart by a short stable suffix`() {
        assertEquals("CDEF", RoomPartyState.guestSuffix("1234-abcdef"))
        assertEquals(RoomPartyState.guestSuffix("same-id"), RoomPartyState.guestSuffix("same-id"))
        assertEquals("0000", RoomPartyState.guestSuffix("--"))
    }

    @Test
    fun `only newer lines from other people are unread`() {
        val chat = listOf(
            RoomChatMessage("1", "ann", "Ann", "text", "hi", null, 100),
            RoomChatMessage("2", "me", "Me", "text", "hello", null, 200),
            RoomChatMessage("3", "ben", "Ben", "system", "Ben skipped", null, 300),
            RoomChatMessage("4", "ben", "Ben", "request", "", track, 400),
        )
        assertEquals(1, RoomPartyState.unread(chat, "me", seenAtMs = 100))
        assertEquals(2, RoomPartyState.unread(chat, "me", seenAtMs = 0))
        assertEquals(0, RoomPartyState.unread(chat, "me", seenAtMs = Long.MAX_VALUE))
    }
}
