package com.cosmotune.music.playback

import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.playback.PartyFollow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What a device in a room does to its own player, given the shared playback and the player's state, and who moves the room on at
 * the end of a song. The player side of this is [PartySync]; these are the decisions it acts on.
 */
class RoomFollowTest {

    private val song = PartyTrack("song-a", "A", "Artist", durationMs = 200_000)

    private fun playing(seq: Long = 4, positionMs: Long = 10_000) =
        PartyPlayback(seq = seq, track = song, isPlaying = true, positionMs = positionMs, anchorMs = 1_000)

    private fun shared(playback: PartyPlayback = playing(), want: Long? = 30_000, synced: Boolean = true, untilStart: Long = 0) =
        PartyFollow.Shared(playback, clockSynced = synced, wantMs = want, msUntilStart = untilStart)

    private fun local(id: String? = "song-a", playing: Boolean = true, ready: Boolean = true, at: Long = 30_000) =
        PartyFollow.Local(mediaId = id, playWhenReady = playing, ready = ready, positionMs = at)

    @Test
    fun `a song the player does not have yet is loaded`() {
        assertEquals(Step.Load, PartyFollow.decide(shared(), local(id = "something-else")))
        assertEquals(Step.Load, PartyFollow.decide(shared(), local(id = null)))
    }

    @Test
    fun `a song this device could not play is sat out rather than loaded again`() {
        assertEquals(Step.SitOut, PartyFollow.decide(shared(), local(id = "the-next-one"), unplayableHere = true))
    }

    @Test
    fun `a stopped player starts at the shared position`() {
        assertEquals(Step.Start(30_000), PartyFollow.decide(shared(), local(playing = false, at = 0)))
    }

    @Test
    fun `a resume scheduled ahead is waited for, not started early`() {
        assertEquals(Step.WaitForStart(350), PartyFollow.decide(shared(untilStart = 350), local(playing = false)))
    }

    @Test
    fun `a paused room pauses this device and holds the playhead where the room stopped`() {
        val paused = PartyPlayback(seq = 5, track = song, isPlaying = false, positionMs = 42_000)
        assertEquals(Step.Hold(42_000), PartyFollow.decide(shared(paused, want = null), local(at = 50_000)))
        // already within the tolerance: paused, but not seeked
        assertEquals(Step.Hold(null), PartyFollow.decide(shared(paused, want = null), local(at = 42_200)))
    }

    @Test
    fun `drift beyond the limit is corrected by seeking to the shared position`() {
        val step = PartyFollow.decide(shared(), local(at = 31_500), alignedSeq = 4)
        assertEquals(Step.Drift(seekTo = 30_000, driftMs = 1_500), step)
        val behind = PartyFollow.decide(shared(), local(at = 28_000), alignedSeq = 4)
        assertEquals(Step.Drift(seekTo = 30_000, driftMs = -2_000), behind)
    }

    @Test
    fun `nothing happens while the device is in step`() {
        assertEquals(Step.InStep, PartyFollow.decide(shared(), local(at = 30_000), alignedSeq = 4))
        // two decoders a little apart are not a desync
        assertEquals(Step.InStep, PartyFollow.decide(shared(), local(at = 30_000 + PartyFollow.DRIFT_LIMIT_MS), alignedSeq = 4))
    }

    @Test
    fun `a new control is landed on exactly, below the drift limit`() {
        assertEquals(Step.Align(seekTo = 30_000, driftMs = 300), PartyFollow.decide(shared(), local(at = 30_300), alignedSeq = 3))
    }

    @Test
    fun `a control that did not move the playhead is no reason to seek`() {
        val step = PartyFollow.decide(shared(), local(at = 30_300), alignedSeq = 3, anchorChanged = false)
        assertEquals(Step.InStep, step)
    }

    @Test
    fun `a buffering player is not measured`() {
        assertEquals(Step.Settling, PartyFollow.decide(shared(), local(ready = false, at = 0), alignedSeq = 4))
    }

    @Test
    fun `before the clock is measured the song is followed but the playhead is left alone`() {
        assertEquals(Step.AwaitClock, PartyFollow.decide(shared(synced = false, want = null), local(playing = false, at = 0)))
        assertEquals(Step.Load, PartyFollow.decide(shared(synced = false, want = null), local(id = "other")))
    }

    @Test
    fun `a listener who paused their own device in a locked room is left quiet`() {
        assertEquals(Step.Muted, PartyFollow.decide(shared(), local(playing = false), locallyPaused = true))
    }

    @Test
    fun `a file on this device or a suppressed player is left alone`() {
        assertEquals(Step.Leave, PartyFollow.decide(shared(), local().copy(isDeviceFile = true)))
        assertEquals(Step.Leave, PartyFollow.decide(shared(), local().copy(suppressed = true)))
        assertEquals(Step.Leave, PartyFollow.decide(shared(PartyPlayback()), local()))
    }

    private fun member(id: String, host: Boolean = false, connected: Boolean = true) =
        PartyMember(memberId = id, isHost = host, connected = connected)

    @Test
    fun `the host moves the room on at the end of a song`() {
        val members = listOf(member("ann"), member("host", host = true), member("ben"))
        assertEquals("host", PartyFollow.advancer(members, hostOnly = false))
    }

    @Test
    fun `with the host away the first connected member in seat order does it`() {
        val members = listOf(member("host", host = true, connected = false), member("ann", connected = false), member("ben"), member("cy"))
        assertEquals("ben", PartyFollow.advancer(members, hostOnly = false))
    }

    @Test
    fun `with host-only control only the host may, connected or not`() {
        val members = listOf(member("host", host = true, connected = false), member("ann"))
        assertEquals("host", PartyFollow.advancer(members, hostOnly = true))
        assertNull(PartyFollow.advancer(listOf(member("ann")), hostOnly = true))
    }

    @Test
    fun `nobody connected means nobody advances`() {
        assertNull(PartyFollow.advancer(listOf(member("ann", connected = false)), hostOnly = false))
    }
}
