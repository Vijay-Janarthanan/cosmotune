package com.cosmotune.music.desktop

import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartySnapshot
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.desktop.DesktopPartySync.Companion.decideSeek
import com.cosmotune.music.desktop.DesktopRoomPlayback.LocalPlayer
import com.cosmotune.music.desktop.DesktopRoomPlayback.Step
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomControl
import com.cosmotune.room.RoomException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The room-following decisions, without a player or a network. Nothing here touches [DesktopListenTogether], [DesktopRooms] or
 * [DesktopPersistence]: on Windows the last is the real registry.
 */
class DesktopRoomPlaybackTest {

    private val song = PartyTrack("song-a", "A", "Artist", durationMs = 200_000)
    private val other = PartyTrack("song-b", "B", "Artist", durationMs = 180_000)

    private fun playing(seq: Long = 4, position: Long = 0) =
        PartyPlayback(seq = seq, track = song, isPlaying = true, positionMs = position, anchorMs = 1_000)

    private fun paused(position: Long = 30_000) = PartyPlayback(seq = 4, track = song, isPlaying = false, positionMs = position)

    private fun local(
        videoId: String? = song.videoId,
        isPlaying: Boolean = true,
        positionMs: Long = 30_000,
        failed: Boolean = false,
        isLoading: Boolean = false,
        durationMs: Long = 200_000,
    ) = LocalPlayer(videoId, isPlaying, isLoading, failed, positionMs, durationMs)

    private fun step(
        target: PartyPlayback,
        player: LocalPlayer,
        want: Long? = 30_000,
        wait: Long = 0,
        synced: Boolean = true,
        locallyPaused: Boolean = false,
        loading: String? = null,
    ) = DesktopRoomPlayback.step(target, player, want, wait, synced, locallyPaused, loading)

    // ---- which step follows ----

    @Test
    fun `a different track starts the room's track`() {
        assertEquals(Step.Load(song.videoId), step(playing(), local(videoId = other.videoId)))
        assertEquals(Step.Load(song.videoId), step(playing(), local(videoId = null, isPlaying = false)))
    }

    @Test
    fun `a track already being loaded is not asked for again`() {
        assertEquals(Step.None, step(playing(), local(videoId = other.videoId), loading = song.videoId))
    }

    @Test
    fun `a playing room is left alone until the clock has a sample`() {
        assertEquals(Step.None, step(playing(), local(videoId = other.videoId), synced = false))
        // a paused room needs no clock: the position it holds is exact
        assertEquals(Step.Pause(null), step(paused(), local(isPlaying = true), synced = false))
    }

    @Test
    fun `a paused room pauses this player and seeks only when meaningfully elsewhere`() {
        assertEquals(Step.Pause(null), step(paused(position = 30_000), local(positionMs = 30_200)))
        assertEquals(Step.Pause(42_000), step(paused(position = 42_000), local(positionMs = 30_000)))
    }

    @Test
    fun `a playing room starts a paused player at the shared position`() {
        assertEquals(Step.Start(61_000), step(playing(), local(isPlaying = false), want = 61_000))
    }

    @Test
    fun `a resume scheduled ahead waits for its instant`() {
        assertEquals(Step.WaitForStart(250), step(playing(), local(isPlaying = false), wait = 250))
    }

    @Test
    fun `a player that finished a moment before the room's clock does not start the song again`() {
        assertEquals(Step.None, step(playing(), local(isPlaying = false, positionMs = 200_000), want = 199_900))
    }

    @Test
    fun `a listener who paused on their own is not restarted`() {
        assertEquals(Step.None, step(playing(), local(isPlaying = false), locallyPaused = true))
    }

    @Test
    fun `both playing the same track compares the drift`() {
        assertEquals(Step.Follow(30_000), step(playing(), local(isPlaying = true)))
    }

    @Test
    fun `a track this device cannot play is held quietly`() {
        assertEquals(Step.Hold, step(playing(), local(isPlaying = false, failed = true)))
        assertEquals(Step.Hold, step(paused(), local(isPlaying = false, failed = true)))
        // while a retry is still loading it is not given up on yet
        assertEquals(Step.Start(30_000), step(playing(), local(isPlaying = false, failed = true, isLoading = true)))
    }

    @Test
    fun `after a held track the room's next one is loaded`() {
        val next = PartyPlayback(seq = 5, track = other, isPlaying = true, anchorMs = 2_000)
        assertEquals(Step.Load(other.videoId), step(next, local(isPlaying = false, failed = true)))
    }

    @Test
    fun `an empty room asks nothing of the player`() {
        assertEquals(Step.None, step(PartyPlayback(), local()))
    }

    // ---- the drift rule in a room ----

    private fun drift(ms: Long, strikes: Int = 0, seq: Long = 4, aligned: Long = 4) = decideSeek(
        drift = ms, controlSeq = seq, alignedSeq = aligned, strikes = strikes, nowMs = 10_000, cooldownUntilMs = 0,
        limitMs = DesktopRoomPlayback.DRIFT_LIMIT_MS,
    )

    @Test
    fun `drift within the room limit is left alone`() {
        assertFalse(drift(700).seek)
        assertFalse(drift(-790, strikes = 1).seek)
    }

    @Test
    fun `drift above the room limit is corrected on the second reading`() {
        assertFalse(drift(900).seek)
        assertTrue(drift(900, strikes = 1).seek)
        assertTrue(drift(-1_000, strikes = 1).seek)
    }

    @Test
    fun `the party server's looser limit still applies outside a room`() {
        val outside = decideSeek(drift = 900, controlSeq = 4, alignedSeq = 4, strikes = 1, nowMs = 10_000, cooldownUntilMs = 0)
        assertFalse(outside.seek)
    }

    @Test
    fun `a new control aligns at once`() {
        assertTrue(drift(300, seq = 5, aligned = 4).seek)
    }

    // ---- who moves the room on at the end of a song ----

    private val host = PartyMember("host", "host", "Host", isHost = true, connected = true)
    private val anu = PartyMember("anu", "anu", "Anu", connected = true)
    private val ravi = PartyMember("ravi", "ravi", "Ravi", connected = true)

    private fun advance(
        self: String,
        members: List<PartyMember> = listOf(host, anu, ravi),
        position: Long = 200_000,
        duration: Long? = 200_000,
        lastSeq: Long = -1,
        hostOnly: Boolean = false,
        playback: PartyPlayback = playing(),
    ) = DesktopRoomPlayback.shouldAdvance(self, members, hostOnly, playback, position, duration, lastSeq)

    @Test
    fun `the host moves the room on when the song is over and the others wait`() {
        assertTrue(advance("host"))
        assertFalse(advance("anu"))
        assertFalse(advance("ravi"))
    }

    @Test
    fun `nobody moves on before the end`() {
        assertFalse(advance("host", position = 150_000))
        // within the slack counts as over
        assertTrue(advance("host", position = 200_000 - DesktopRoomPlayback.END_SLACK_MS))
    }

    @Test
    fun `without the host the first connected member moves on`() {
        val away = host.copy(connected = false)
        assertEquals("anu", DesktopRoomPlayback.advancerId(listOf(away, anu, ravi)))
        assertTrue(advance("anu", members = listOf(away, anu, ravi)))
        assertFalse(advance("ravi", members = listOf(away, anu, ravi)))
    }

    @Test
    fun `the others step in one after another when the one whose turn it is does not`() {
        val late = 200_000 + DesktopRoomPlayback.BACKUP_ADVANCE_MS
        assertTrue(advance("anu", position = late))
        assertFalse(advance("ravi", position = late))
        assertTrue(advance("ravi", position = 200_000 + 2 * DesktopRoomPlayback.BACKUP_ADVANCE_MS))
    }

    @Test
    fun `a device moves the room on once per song`() {
        assertFalse(advance("host", lastSeq = 4))
    }

    @Test
    fun `a paused room, an unknown length or a stranger never moves on`() {
        assertFalse(advance("host", playback = paused(position = 200_000)))
        assertFalse(advance("host", duration = null))
        assertFalse(advance("someone-else"))
    }

    @Test
    fun `with host-only control only the host moves on`() {
        val late = 200_000 + 3 * DesktopRoomPlayback.BACKUP_ADVANCE_MS
        assertTrue(advance("host", hostOnly = true))
        assertFalse(advance("anu", hostOnly = true, position = late))
    }

    // ---- the party's calls as room controls ----

    @Test
    fun `a running order with a new song becomes SetQueue`() {
        val controls = DesktopRoomPlayback.queueControls(song.videoId, PartyQueue(), listOf(other, song), 0)
        assertEquals(listOf<RoomControl>(RoomControl.SetQueue(listOf(other, song), 0)), controls)
    }

    @Test
    fun `an edit around the playing song keeps the playhead`() {
        val room = PartyQueue(seq = 2, index = 0, items = listOf(song, other))
        val extra = PartyTrack("song-c")
        val controls = DesktopRoomPlayback.queueControls(song.videoId, room, listOf(song, other, extra), 0)
        assertEquals(listOf(RoomControl.QueueClear, RoomControl.QueueAdd(listOf(other, extra))), controls)
        assertTrue(controls.none { it is RoomControl.SetQueue })
    }

    @Test
    fun `the same running order sends nothing`() {
        val room = PartyQueue(seq = 2, index = 0, items = listOf(song, other))
        assertEquals(emptyList(), DesktopRoomPlayback.queueControls(song.videoId, room, listOf(song, other), 0))
    }

    @Test
    fun `controls move the sequences the way the room counts them`() {
        assertEquals(1 to 0, DesktopRoomPlayback.seqBumps(RoomControl.Play()))
        assertEquals(1 to 0, DesktopRoomPlayback.seqBumps(RoomControl.Seek(5)))
        assertEquals(1 to 1, DesktopRoomPlayback.seqBumps(RoomControl.SetTrack(song)))
        assertEquals(1 to 1, DesktopRoomPlayback.seqBumps(RoomControl.QueueClear))
    }

    // ---- the room as the party code reads it ----

    @Test
    fun `a live room reads as a party with this device in it`() {
        val snapshot = PartySnapshot(code = "ABC234", maxMembers = 5, members = listOf(host, anu), playback = playing())
        val mirror = DesktopRoomPlayback.mirrorOf(snapshot, "ABC234", RoomConnection.LIVE, "anu", clockSynced = true, roundTripMs = 80)
        assertTrue(mirror.inParty)
        assertEquals("anu", mirror.you?.memberId)
        assertEquals(DesktopListenTogether.Connection.LIVE, mirror.connection)
        assertEquals(song, mirror.playback.track)
        assertTrue(mirror.clockSynced)
    }

    @Test
    fun `host-only control locks everyone but the host`() {
        val snapshot = PartySnapshot(code = "ABC234", hostOnlyControl = true, members = listOf(host, anu), playback = playing())
        assertTrue(DesktopRoomPlayback.mirrorOf(snapshot, "ABC234", RoomConnection.LIVE, "anu", true, 0).controlsLocked)
        assertFalse(DesktopRoomPlayback.mirrorOf(snapshot, "ABC234", RoomConnection.LIVE, "host", true, 0).controlsLocked)
    }

    @Test
    fun `an ended room reads as no party, and a connecting one as a party still arriving`() {
        assertFalse(DesktopRoomPlayback.mirrorOf(null, "ABC234", RoomConnection.ENDED, "anu", true, 0).inParty)
        assertFalse(DesktopRoomPlayback.mirrorOf(null, "ABC234", RoomConnection.REMOVED, "anu", true, 0).inParty)
        val arriving = DesktopRoomPlayback.mirrorOf(null, "ABC234", RoomConnection.CONNECTING, "anu", false, 0)
        assertTrue(arriving.inParty)
        assertEquals(DesktopListenTogether.Connection.CONNECTING, arriving.connection)
        assertNull(arriving.playback.track)
    }

    // ---- small pieces ----

    @Test
    fun `failures read as short messages`() {
        assertEquals("That room is full.", DesktopRoomPlayback.errorMessage(RoomException(RoomException.Reason.FULL, "x")))
        assertEquals(
            "Only the host can control the music here.",
            DesktopRoomPlayback.errorMessage(RoomException(RoomException.Reason.HOST_ONLY, "x")),
        )
        assertEquals("No connection. Check your internet and try again.", DesktopRoomPlayback.errorMessage(java.io.IOException("down")))
    }

    @Test
    fun `guests get a short suffix and durations parse`() {
        assertEquals("Guest 9F3A", DesktopRoomPlayback.guestName("1234-abcd-9f3a"))
        assertEquals(225_000L, DesktopRoomPlayback.durationMsOf("3:45"))
        assertEquals(3_725_000L, DesktopRoomPlayback.durationMsOf("1:02:05"))
        assertNull(DesktopRoomPlayback.durationMsOf("live"))
    }

    @Test
    fun `unread chat counts only others since the chat was last open`() {
        val chat = listOf(
            com.cosmotune.room.RoomChatMessage("1", "anu", "Anu", "text", "hi", null, 100),
            com.cosmotune.room.RoomChatMessage("2", "host", "Host", "text", "hey", null, 200),
            com.cosmotune.room.RoomChatMessage("3", "anu", "Anu", "text", "play this", null, 300),
        )
        assertEquals(1, DesktopRoomPlayback.unreadCount(chat, selfUid = "host", seenAtMs = 150))
        assertEquals(0, DesktopRoomPlayback.unreadCount(chat, selfUid = "host", seenAtMs = 300))
    }
}
