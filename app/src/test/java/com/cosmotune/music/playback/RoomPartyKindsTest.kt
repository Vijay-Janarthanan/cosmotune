package com.cosmotune.music.playback

import com.cosmotune.music.data.listentogether.PartyPlayback
import com.cosmotune.music.data.listentogether.PartyTrack
import com.cosmotune.music.playback.PartyFollow.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A room plays songs, videos, live stations and podcast episodes, each on every device in that device's own player. What each player
 * does, given the room's item of each kind and the player's own state: the [PartyFollow] decisions [PartySync] (the music player,
 * which Podio plays through) and the room's video follower act on.
 */
class RoomPartyKindsTest {

    private val song = PartyTrack("song-a", "A", "Artist", durationMs = 200_000)
    private val video = PartyTrack("dQw4w9WgXcQ", "A video", "A channel", durationMs = 213_000, kind = PartyTrack.KIND_VIDEO)
    private val station = PartyTrack(
        "podio:radio:9617a958", "Radio One", "London", kind = PartyTrack.KIND_RADIO, url = "https://stream.example.org/live.mp3",
    )
    private val episode = PartyTrack(
        "podio:episode:abc123", "Episode 4", "A show", durationMs = 3_600_000, kind = PartyTrack.KIND_PODCAST,
        url = "https://cdn.example.org/ep4.mp3",
    )

    private fun playing(track: PartyTrack, seq: Long = 4) =
        PartyPlayback(seq = seq, track = track, isPlaying = true, positionMs = 10_000, anchorMs = 1_000)

    private fun shared(playback: PartyPlayback, want: Long? = 30_000, synced: Boolean = true) =
        PartyFollow.Shared(playback, clockSynced = synced, wantMs = want)

    private fun local(id: String?, playing: Boolean = true, at: Long = 30_000, ended: Boolean = false, ownPace: Boolean = false) =
        PartyFollow.Local(mediaId = id, playWhenReady = playing, ready = true, positionMs = at, ended = ended, ownPace = ownPace)

    private fun music(shared: PartyFollow.Shared, local: PartyFollow.Local, alignedSeq: Long = 4) =
        PartyFollow.decide(shared, local, alignedSeq = alignedSeq)

    private fun videoPlayer(shared: PartyFollow.Shared, local: PartyFollow.Local, alignedSeq: Long = 4, unplayable: Boolean = false) =
        PartyFollow.decide(shared, local, alignedSeq = alignedSeq, unplayableHere = unplayable, player = RoomKinds.Player.VIDEO)

    // ---- music: unchanged ----

    @Test
    fun `a song is still followed exactly as before`() {
        assertEquals(Step.Load, music(shared(playing(song)), local("other")))
        assertEquals(Step.Drift(30_000, 1_500), music(shared(playing(song)), local("song-a", at = 31_500)))
        assertEquals(Step.InStep, music(shared(playing(song)), local("song-a")))
    }

    // ---- switching kinds stops the other player ----

    @Test
    fun `a video in the room stops the music player and a song stops the video player`() {
        assertEquals(Step.Elsewhere, music(shared(playing(video)), local("song-a")))
        assertEquals(Step.Elsewhere, videoPlayer(shared(playing(song)), local(video.videoId)))
        // a station and an episode are the music player's, as Podio plays them: the video stops for them too
        assertEquals(Step.Elsewhere, videoPlayer(shared(playing(station)), local(video.videoId)))
        assertEquals(Step.Elsewhere, videoPlayer(shared(playing(episode)), local(null)))
    }

    @Test
    fun `stations and episodes are the music player's and videos are Video mode's`() {
        assertEquals(RoomKinds.Player.MUSIC, RoomKinds.playerOf(song))
        assertEquals(RoomKinds.Player.MUSIC, RoomKinds.playerOf(station))
        assertEquals(RoomKinds.Player.MUSIC, RoomKinds.playerOf(episode))
        assertEquals(RoomKinds.Player.VIDEO, RoomKinds.playerOf(video))
    }

    // ---- video: followed like music, on the video player ----

    @Test
    fun `a video is loaded, started, held and kept in step on the video player like a song`() {
        assertEquals(Step.Load, videoPlayer(shared(playing(video)), local(null)))
        assertEquals(Step.Start(30_000), videoPlayer(shared(playing(video)), local(video.videoId, playing = false, at = 0)))
        val paused = PartyPlayback(seq = 5, track = video, isPlaying = false, positionMs = 42_000)
        assertEquals(Step.Hold(42_000), videoPlayer(shared(paused, want = null), local(video.videoId, at = 50_000)))
        // the same drift rule as music
        assertEquals(Step.Drift(30_000, -2_000), videoPlayer(shared(playing(video)), local(video.videoId, at = 28_000)))
        assertEquals(Step.InStep, videoPlayer(shared(playing(video)), local(video.videoId, at = 30_000 + PartyFollow.DRIFT_LIMIT_MS)))
        assertEquals(Step.Align(30_000, 300), videoPlayer(shared(playing(video)), local(video.videoId, at = 30_300), alignedSeq = 3))
    }

    @Test
    fun `a video this device cannot play is sat out, and a paused video of its own is left paused`() {
        assertEquals(Step.SitOut, videoPlayer(shared(playing(video)), local(null), unplayable = true))
        val step = PartyFollow.decide(shared(playing(video)), local(video.videoId, playing = false), locallyPaused = true, player = RoomKinds.Player.VIDEO)
        assertEquals(Step.Muted, step)
    }

    @Test
    fun `a pause on the watch page is told apart from the room's own start`() {
        // the room had it playing, it is settled and stopped, and the room's last command is old: the person's own pause
        assertTrue(PartyFollow.pausedHere(commandedPlaying = true, roomPlaying = true, playing = false, ready = true, msSinceCommand = 5_000))
        // just started by the room: still starting
        assertFalse(PartyFollow.pausedHere(commandedPlaying = true, roomPlaying = true, playing = false, ready = true, msSinceCommand = 400))
        // still loading, the room paused it, or the room never asked it to play
        assertFalse(PartyFollow.pausedHere(commandedPlaying = true, roomPlaying = true, playing = false, ready = false, msSinceCommand = 5_000))
        assertFalse(PartyFollow.pausedHere(commandedPlaying = true, roomPlaying = false, playing = false, ready = true, msSinceCommand = 5_000))
        assertFalse(PartyFollow.pausedHere(commandedPlaying = false, roomPlaying = true, playing = false, ready = true, msSinceCommand = 5_000))
    }

    @Test
    fun `the end of an item whose next is another player's moves the room on`() {
        assertEquals(Step.Ended, music(shared(playing(song)), local("song-a", at = 200_000, ended = true)))
        // a room that is paused at the end is held, not moved on
        val paused = PartyPlayback(seq = 5, track = song, isPlaying = false, positionMs = 200_000)
        assertEquals(Step.Hold(null), music(shared(paused, want = null), local("song-a", playing = false, at = 200_000, ended = true)))
    }

    // ---- radio: live, never seeked ----

    @Test
    fun `a live station is tuned in and played, and never seeked whatever the playheads say`() {
        assertEquals(Step.Load, music(shared(playing(station)), local("song-a")))
        assertEquals(Step.Start(null), music(shared(playing(station)), local(station.videoId, playing = false, at = 0)))
        // far apart, a new control, no clock: all the same to a live stream
        assertEquals(Step.InStep, music(shared(playing(station)), local(station.videoId, at = 900_000), alignedSeq = 3))
        assertEquals(Step.InStep, music(shared(playing(station), synced = false, want = null), local(station.videoId, at = 5)))
        // a room that joined late tunes in without waiting for the clock
        assertEquals(Step.Start(null), music(shared(playing(station), synced = false, want = null), local(station.videoId, playing = false)))
    }

    @Test
    fun `pausing the room pauses a station everywhere without moving it, and play resumes it`() {
        val paused = PartyPlayback(seq = 6, track = station, isPlaying = false, positionMs = 77_000)
        assertEquals(Step.Hold(null), music(shared(paused, want = null), local(station.videoId, at = 5_000)))
        assertEquals(Step.Start(null), music(shared(playing(station, seq = 7)), local(station.videoId, playing = false, at = 5_000)))
    }

    @Test
    fun `a station sent with its bare id is the same station as Podio's own`() {
        val bare = station.copy(videoId = "9617a958")
        assertEquals("podio:radio:9617a958", RoomKinds.mediaIdOf(bare))
        assertEquals(Step.InStep, music(shared(playing(bare)), local("podio:radio:9617a958")))
        assertEquals("podio:episode:x1", RoomKinds.mediaIdOf(episode.copy(videoId = "x1")))
        assertEquals("song-a", RoomKinds.mediaIdOf(song))
    }

    // ---- podcast: position-synced like music, at the person's own speed ----

    @Test
    fun `an episode is kept on the room's playhead like a song`() {
        assertEquals(Step.Load, music(shared(playing(episode)), local("song-a")))
        assertEquals(Step.Drift(30_000, 4_000), music(shared(playing(episode)), local(episode.videoId, at = 34_000)))
        assertEquals(Step.Align(30_000, 500), music(shared(playing(episode)), local(episode.videoId, at = 30_500), alignedSeq = 3))
    }

    @Test
    fun `at a speed of its own a device keeps its pace between controls and still lands on each one`() {
        assertEquals(Step.InStep, music(shared(playing(episode)), local(episode.videoId, at = 90_000, ownPace = true)))
        assertEquals(Step.Align(30_000, 60_000), music(shared(playing(episode)), local(episode.videoId, at = 90_000, ownPace = true), alignedSeq = 3))
    }

    // ---- what each device can play ----

    @Test
    fun `an item this device cannot reach is known before it is tried`() {
        assertTrue(RoomKinds.playableHere(song, videoAvailable = false))
        assertTrue(RoomKinds.playableHere(station, videoAvailable = false))
        assertFalse(RoomKinds.playableHere(station.copy(url = "http://stream.example.org/live.mp3"), videoAvailable = true))
        assertFalse(RoomKinds.playableHere(station.copy(url = null), videoAvailable = true))
        assertTrue(RoomKinds.playableHere(episode, videoAvailable = false))
        assertTrue(RoomKinds.playableHere(episode.copy(videoId = "podio:episode:youtube:dQw4w9WgXcQ", url = null), videoAvailable = false))
        assertTrue(RoomKinds.playableHere(video, videoAvailable = true))
        assertFalse(RoomKinds.playableHere(video, videoAvailable = false))
        assertFalse(RoomKinds.playableHere(song.copy(kind = "hologram"), videoAvailable = true))
    }

    @Test
    fun `the music player holds only the run of the room's queue it can play`() {
        val queue = listOf(song, episode, video, station)
        assertEquals(listOf(song, episode) to 1, RoomKinds.musicRun(queue, 1))
        assertEquals(emptyList<PartyTrack>() to -1, RoomKinds.musicRun(queue, 2))
        assertEquals(listOf(station) to 0, RoomKinds.musicRun(queue, 3))
        assertEquals(emptyList<PartyTrack>() to -1, RoomKinds.musicRun(queue, -1))
        assertTrue(RoomKinds.mixed(queue))
        assertFalse(RoomKinds.mixed(listOf(song, station, episode)))
    }

    // ---- between the room and the music player ----

    @Test
    fun `a station on the music player is shared as a station with its https address, and comes back as Podio's item`() {
        val song = station.toSong()
        assertEquals("podio:radio:9617a958", song.videoId)
        assertEquals("https://stream.example.org/live.mp3", song.localUri)
        val back = song.toPartyTrack(0L)
        assertEquals(PartyTrack.KIND_RADIO, back.kind)
        assertEquals("https://stream.example.org/live.mp3", back.url)
        assertTrue(back.isLive)
        // an address another device may not fetch is not shared
        assertNull(song.copy(localUri = "http://stream.example.org/live.mp3").toPartyTrack(0L).url)
        assertNull(this.song.toSong().localUri)
        assertEquals(PartyTrack.KIND_MUSIC, this.song.toSong().toPartyTrack(0L).kind)
    }

    @Test
    fun `a long Podio id is shared under the room's shorter id, and the device's own item matches it`() {
        val long = "podio:episode:" + "x".repeat(120)
        val shared = RoomKinds.roomIdOf(long)
        assertTrue(shared.length <= 64 && shared.startsWith("podio:episode:"))
        assertEquals(shared, episode.copy(videoId = long, url = "https://cdn.example.org/a.mp3").toSong().toPartyTrack(0L).videoId)
        assertEquals("song-a", RoomKinds.roomIdOf("song-a"))
    }
}
