package com.cosmotune.music.desktop

import com.cosmotune.music.data.listentogether.PartyMember
import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyQueue
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.DesktopPartySync.Companion.decideSeek
import com.cosmotune.music.desktop.DesktopRoomPlayback.LauncherSummary
import com.cosmotune.music.desktop.DesktopRoomPlayback.Launch
import com.cosmotune.music.desktop.DesktopRoomPlayback.LocalPlayer
import com.cosmotune.music.desktop.DesktopRoomPlayback.Player
import com.cosmotune.music.desktop.DesktopRoomPlayback.Step
import com.cosmotune.room.RoomChatMessage
import com.cosmotune.room.RoomConnection
import com.cosmotune.room.RoomMember
import com.cosmotune.room.RoomMeta
import com.cosmotune.room.RoomState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A room's items of every kind, as the desktop follows them: which player opens each, what a station does differently (no playhead),
 * what a person's own speed changes, how the two players hand over, and what "Play in room" does from where the person stands. Pure
 * decisions only: nothing here touches [DesktopListenTogether], [DesktopRooms], Video's player or [DesktopPersistence] (on Windows the
 * last is the real registry).
 */
class DesktopRoomKindsTest {

    private val song = PartyTrack("song-a", "A", "Artist", durationMs = 200_000)
    private val video = PartyTrack("dQw4w9WgXcQ", "A video", "Channel", durationMs = 213_000, kind = PartyTrack.KIND_VIDEO)
    private val station = PartyTrack(
        "podio:radio:9617a958", "Radio City", "Chennai · India", kind = PartyTrack.KIND_RADIO, url = "https://stream.example.com/live.mp3",
    )
    private val episode = PartyTrack(
        "podio:episode:4f2a", "Episode 12", "A show", durationMs = 3_600_000, kind = PartyTrack.KIND_PODCAST,
        url = "https://cdn.example.com/ep12.mp3",
    )

    private fun playing(track: PartyTrack, seq: Long = 4) = PartyPlayback(seq = seq, track = track, isPlaying = true, positionMs = 0, anchorMs = 1_000)

    private fun paused(track: PartyTrack, position: Long = 30_000) = PartyPlayback(seq = 4, track = track, isPlaying = false, positionMs = position)

    private fun local(
        videoId: String?,
        isPlaying: Boolean = true,
        positionMs: Long = 30_000,
        durationMs: Long = 200_000,
        isLoading: Boolean = false,
        failed: Boolean = false,
        speed: Float = 1f,
    ) = LocalPlayer(videoId, isPlaying, isLoading, failed, positionMs, durationMs, speed)

    private fun step(
        target: PartyPlayback,
        player: LocalPlayer,
        want: Long? = 30_000,
        synced: Boolean = true,
        locallyPaused: Boolean = false,
        loading: String? = null,
        leftPlayer: Boolean = false,
    ) = DesktopRoomPlayback.step(target, player, want, 0, synced, locallyPaused, loading, leftPlayer)

    // ---- which player ----

    @Test
    fun `a video opens in Video mode and everything else in the music player`() {
        assertEquals(Player.VIDEO, DesktopRoomPlayback.playerFor(video))
        assertEquals(Player.MUSIC, DesktopRoomPlayback.playerFor(song))
        assertEquals(Player.MUSIC, DesktopRoomPlayback.playerFor(station))
        assertEquals(Player.MUSIC, DesktopRoomPlayback.playerFor(episode))
        // A Watch item is a picture too: Watch's player, through the same steps as a room video.
        assertEquals(Player.VIDEO, DesktopRoomPlayback.playerFor(PartyTrack("m:tmdb:603", "The Matrix", kind = PartyTrack.KIND_WATCH)))
    }

    @Test
    fun `a Watch item is tried by its key and a kind from a newer app never plays as a song`() {
        assertTrue(DesktopRoomPlayback.playableHere(PartyTrack("e:tmdb:1399:1:2", "Game of Thrones", kind = PartyTrack.KIND_WATCH)))
        assertFalse(DesktopRoomPlayback.playableHere(PartyTrack("dQw4w9WgXcQ", "Not a key", kind = PartyTrack.KIND_WATCH)))
        val newer = song.copy(kind = "hologram")
        assertTrue(newer.needsUpdate)
        assertEquals(Player.MUSIC, DesktopRoomPlayback.playerFor(newer))
        assertFalse(DesktopRoomPlayback.playableHere(newer))
    }

    @Test
    fun `an item this device cannot fetch is not tried`() {
        assertTrue(DesktopRoomPlayback.playableHere(song))
        assertTrue(DesktopRoomPlayback.playableHere(video))
        assertTrue(DesktopRoomPlayback.playableHere(station))
        assertTrue(DesktopRoomPlayback.playableHere(episode))
        assertFalse(DesktopRoomPlayback.playableHere(station.copy(url = "http://stream.example.com/live.mp3")), "plain http is refused")
        assertFalse(DesktopRoomPlayback.playableHere(station.copy(url = null)))
        assertFalse(DesktopRoomPlayback.playableHere(station.copy(url = "https://user:pw@stream.example.com/")), "no credentials in a link")
        assertFalse(DesktopRoomPlayback.playableHere(station.copy(videoId = "9617a958")), "without Podio's id the resolver would not know it")
        assertFalse(DesktopRoomPlayback.playableHere(video.copy(videoId = " ")))
        assertFalse(DesktopRoomPlayback.playableHere(song.copy(kind = "hologram")), "a kind from a newer app")
        // An episode from YouTube Music is found by its video id and needs no link.
        assertTrue(DesktopRoomPlayback.playableHere(episode.copy(videoId = "podio:episode:youtube:dQw4w9WgXcQ", url = null)))
    }

    // ---- a station has no playhead ----

    @Test
    fun `a station is tuned to without waiting for the clock`() {
        assertEquals(Step.Load(station.videoId), step(playing(station), local(videoId = song.videoId), want = null, synced = false))
    }

    @Test
    fun `a live station is never seeked`() {
        // Far from the room's "position" and with an unsynced clock: a station plays on as it is.
        assertEquals(Step.None, step(playing(station), local(videoId = station.videoId, positionMs = 900_000), want = 5_000))
        assertEquals(Step.None, step(playing(station), local(videoId = station.videoId, positionMs = 900_000), want = 5_000, synced = false))
    }

    @Test
    fun `pausing the room pauses the station and play resumes it, from now`() {
        assertEquals(Step.Pause(null), step(paused(station, position = 120_000), local(videoId = station.videoId, positionMs = 0)))
        assertEquals(Step.Start(null), step(playing(station), local(videoId = station.videoId, isPlaying = false, positionMs = 400_000), want = 9_000))
        assertEquals(Step.None, step(playing(station), local(videoId = station.videoId, isPlaying = false), locallyPaused = true))
    }

    @Test
    fun `a station that will not play here is held`() {
        assertEquals(Step.Hold, step(playing(station), local(videoId = station.videoId, isPlaying = false, failed = true)))
    }

    @Test
    fun `a station never moves the room on`() {
        val members = listOf(PartyMember("me", isHost = true, connected = true))
        assertFalse(DesktopRoomPlayback.shouldAdvance("me", members, false, playing(station), 10_000_000, 1_000, -1))
    }

    // ---- an episode and a video follow the clock like a song ----

    @Test
    fun `an episode and a video align and correct drift like a song`() {
        assertEquals(Step.Follow(30_000), step(playing(episode), local(videoId = episode.videoId, durationMs = 3_600_000)))
        assertEquals(Step.Follow(30_000), step(playing(video), local(videoId = video.videoId, durationMs = 213_000)))
        assertEquals(Step.Start(30_000), step(playing(video), local(videoId = video.videoId, isPlaying = false, durationMs = 213_000)))
        assertEquals(Step.Pause(30_000), step(paused(video), local(videoId = video.videoId, positionMs = 90_000, durationMs = 213_000)))
    }

    @Test
    fun `a person's own speed keeps the item in step on controls but not on drift`() {
        val fast = step(playing(episode), local(videoId = episode.videoId, durationMs = 3_600_000, speed = 1.5f))
        assertEquals(Step.Follow(30_000, correctDrift = false), fast)
        // The room's own control still lands everyone in the same place...
        assertTrue(decideSeek(drift = 9_000, controlSeq = 5, alignedSeq = 4, strikes = 0, nowMs = 0, cooldownUntilMs = 0, correctDrift = false).seek)
        // ...and the drift the person's speed builds up afterwards is theirs to keep.
        var strikes = 0
        repeat(4) {
            val decision = decideSeek(drift = 9_000, controlSeq = 5, alignedSeq = 5, strikes = strikes, nowMs = 0, cooldownUntilMs = 0, correctDrift = false)
            assertFalse(decision.seek)
            strikes = decision.strikes
        }
    }

    @Test
    fun `an item already over on the room's clock is not opened only to end`() {
        assertEquals(Step.None, step(playing(video), local(videoId = null, isPlaying = false), want = 213_000))
        assertEquals(Step.Load(video.videoId), step(playing(video), local(videoId = null, isPlaying = false), want = 100_000))
    }

    @Test
    fun `a video the person closed is not reopened until the next item`() {
        assertEquals(Step.None, step(playing(video), local(videoId = null, isPlaying = false), leftPlayer = true))
        assertEquals(Step.Load(video.videoId), step(playing(video), local(videoId = null, isPlaying = false), leftPlayer = false))
    }

    // ---- one player at a time ----

    @Test
    fun `switching kinds stops the other player`() {
        assertEquals(Player.MUSIC, DesktopRoomPlayback.standDown(video, musicPlaying = true, videoHoldsRoomItem = false))
        assertNull(DesktopRoomPlayback.standDown(video, musicPlaying = false, videoHoldsRoomItem = true), "the video is this item's player")
        assertEquals(Player.VIDEO, DesktopRoomPlayback.standDown(song, musicPlaying = true, videoHoldsRoomItem = true))
        assertEquals(Player.VIDEO, DesktopRoomPlayback.standDown(station, musicPlaying = false, videoHoldsRoomItem = true))
        assertEquals(Player.VIDEO, DesktopRoomPlayback.standDown(episode, musicPlaying = false, videoHoldsRoomItem = true))
        assertNull(DesktopRoomPlayback.standDown(song, musicPlaying = true, videoHoldsRoomItem = false), "a video of the person's own is left alone")
    }

    // ---- the Video player's own controls ----

    @Test
    fun `a pause pressed in the Video player is heard once the player has settled`() {
        val pausedHere = local(videoId = video.videoId, isPlaying = false, durationMs = 213_000)
        assertEquals(false, DesktopRoomPlayback.videoGesture(true, settled = true, local = pausedHere, trackId = video.videoId))
        assertEquals(true, DesktopRoomPlayback.videoGesture(false, settled = true, local = local(videoId = video.videoId, durationMs = 213_000), trackId = video.videoId))
    }

    @Test
    fun `starting, buffering, ending and other videos are not gestures`() {
        val pausedHere = local(videoId = video.videoId, isPlaying = false, durationMs = 213_000)
        assertNull(DesktopRoomPlayback.videoGesture(true, settled = false, local = pausedHere, trackId = video.videoId), "still starting")
        assertNull(DesktopRoomPlayback.videoGesture(null, settled = true, local = pausedHere, trackId = video.videoId), "nothing told yet")
        assertNull(DesktopRoomPlayback.videoGesture(true, settled = true, local = pausedHere.copy(isLoading = true), trackId = video.videoId))
        assertNull(DesktopRoomPlayback.videoGesture(true, settled = true, local = pausedHere.copy(positionMs = 212_500), trackId = video.videoId), "at its end")
        assertNull(DesktopRoomPlayback.videoGesture(true, settled = true, local = pausedHere.copy(videoId = "another"), trackId = video.videoId))
        assertNull(DesktopRoomPlayback.videoGesture(true, settled = true, local = pausedHere.copy(failed = true), trackId = video.videoId))
    }

    // ---- the rows the music player plays, and what it shares ----

    @Test
    fun `a station and an episode become the rows Podio plays`() {
        val radio = station.toDesktopSong()
        assertEquals(station.videoId, radio.videoId)
        assertEquals(station.url, radio.localUri, "the resolver fetches the stream from here")
        assertEquals("Podio · Live radio", radio.albumName)
        assertNull(radio.durationText)
        val show = episode.toDesktopSong()
        assertEquals(episode.url, show.localUri)
        assertEquals("60:00", show.durationText)
        assertNull(station.copy(url = "http://plain.example.com/").toDesktopSong().localUri, "never a link that is not https")
        assertTrue(video.toDesktopSong().isVideo)
        assertNull(song.toDesktopSong().localUri)
    }

    @Test
    fun `a Podio row playing here is shared as its kind, a file never`() {
        val radio = Song(videoId = station.videoId, title = "Radio City", artist = "Chennai", thumbnailUrl = null, localUri = station.url)
        val shared = radio.toPartyTrack(durationMs = 54_000)
        assertEquals(PartyTrack.KIND_RADIO, shared.kind)
        assertEquals(station.url, shared.url)
        assertNull(shared.durationMs, "a station has no length")
        assertTrue(radio.roomShareable())
        val show = Song(videoId = episode.videoId, title = "Episode 12", artist = "A show", thumbnailUrl = null, localUri = episode.url)
        assertEquals(PartyTrack.KIND_PODCAST, show.toPartyTrack(3_600_000).kind)
        assertEquals(PartyTrack.KIND_MUSIC, Song(videoId = "song-a", title = "A", artist = "B", thumbnailUrl = null).toPartyTrack(200_000).kind)
        assertFalse(Song(videoId = "local-1", title = "A", artist = "B", thumbnailUrl = null, localUri = "file:///music/a.flac").roomShareable())
        assertFalse(show.copy(localUri = "file:///podcasts/ep12.mp3", localPath = "C:/podcasts/ep12.mp3").roomShareable(), "a download is this computer's")
        assertFalse(radio.copy(localUri = "http://plain.example.com/").roomShareable())
    }

    // ---- "Play in room" and the room icon ----

    private fun room(hostUid: String, hostOnly: Boolean, chat: List<RoomChatMessage> = emptyList()) = RoomState(
        code = "ABC234",
        meta = RoomMeta(createdAtMs = 0, hostUid = hostUid, maxMembers = 5, hostOnlyControl = hostOnly),
        members = listOf(member("host", host = true), member("me")),
        playback = PartyPlayback(),
        queue = PartyQueue(),
        chat = chat,
        serverNowMs = 0,
    )

    private fun member(uid: String, host: Boolean = false) = RoomMember(0, uid, uid, null, "d-$uid", 0, 0, false, host, true)

    @Test
    fun `the icon reads the room for this person`() {
        assertEquals(LauncherSummary(), DesktopRoomPlayback.launcherSummary(false, room("host", false), RoomConnection.LIVE, "me", 0))
        val chat = listOf(
            RoomChatMessage("1", "host", "Host", "text", "hi", null, atMs = 50),
            RoomChatMessage("2", "me", "Me", "text", "hey", null, atMs = 60),
            RoomChatMessage("3", "host", "Host", "text", "old", null, atMs = 5),
        )
        val summary = DesktopRoomPlayback.launcherSummary(true, room("host", false, chat), RoomConnection.LIVE, "me", seenAtMs = 10)
        assertEquals(LauncherSummary(inRoom = true, canControl = true, memberCount = 2, unreadChat = 1), summary)
        assertFalse(DesktopRoomPlayback.launcherSummary(true, room("host", true), RoomConnection.LIVE, "me", 0).canControl)
        assertTrue(DesktopRoomPlayback.launcherSummary(true, room("me", true), RoomConnection.LIVE, "me", 0).canControl, "the host keeps the controls")
        assertEquals(LauncherSummary(inRoom = true), DesktopRoomPlayback.launcherSummary(true, room("host", false), RoomConnection.ENDED, "me", 0))
    }

    @Test
    fun `play in room joins first, is refused under host-only, and otherwise plays`() {
        assertEquals(Launch.JOIN_FIRST, DesktopRoomPlayback.launch(LauncherSummary()))
        assertEquals(Launch.REFUSED, DesktopRoomPlayback.launch(LauncherSummary(inRoom = true, canControl = false, memberCount = 3)))
        assertEquals(Launch.PLAY, DesktopRoomPlayback.launch(LauncherSummary(inRoom = true, canControl = true, memberCount = 3)))
    }
}
