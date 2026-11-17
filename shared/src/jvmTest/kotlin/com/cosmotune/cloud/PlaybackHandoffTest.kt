package com.cosmotune.cloud

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PlaybackHandoffTest {
    private fun song(n: Int) = Song("v$n", "Song $n", "Artist $n", "https://img/$n.jpg", albumName = "Album $n")
    private val queue = (0 until 30).map(::song)

    private fun handoff(
        index: Int = 3, position: Long = 60_000, playing: Boolean = true, at: Long = 1_000_000, device: String = "phone", songs: List<Song> = queue,
    ) = PlaybackHandoff.of(device, "your phone", songs, index, position, 240_000, playing, at)!!

    @Test fun theQueueIsCutAroundTheCurrentSongAndKeepsItsPlace() {
        val long = (0 until 300).map(::song)
        val h = PlaybackHandoff.of("d", "your phone", long, 150, 0, 0, false, 0)!!
        assertEquals(PlaybackHandoff.KEEP_BEFORE + 1 + PlaybackHandoff.KEEP_AFTER, h.songs.size)
        assertEquals("v150", h.song?.videoId)
        assertEquals(PlaybackHandoff.KEEP_AFTER, h.upNext)
        assertNull(PlaybackHandoff.of("d", "x", long, 300, 0, 0, false, 0), "an index outside the queue is nothing to hand over")
    }

    @Test fun itSurvivesTheStoredFormWholeAndAnUnreadableOneIsNothing() {
        val h = handoff()
        assertEquals(h, PlaybackHandoff.fromFields(PlaybackHandoff.toFields(h)))
        assertNull(PlaybackHandoff.fromFields(kotlinx.serialization.json.JsonObject(mapOf("data" to kotlinx.serialization.json.JsonPrimitive("{not json")))))
    }

    @Test fun aPlayingSongMovesOnByTheClockAndStopsAtItsEnd() {
        val h = handoff(position = 60_000, at = 1_000_000)
        assertEquals(90_000, h.positionAt(1_030_000))
        assertEquals(240_000, h.positionAt(9_000_000))
        assertEquals(60_000, handoff(playing = false).positionAt(9_000_000), "a paused one stays where it was")
    }

    @Test fun aChangeIsWrittenAtOnceAndPlayingAloneOnlyEveryFewMinutes() {
        val last = handoff(at = 1_000_000)
        assertTrue(HandoffPolicy.shouldWrite(null, last))
        assertTrue(HandoffPolicy.shouldWrite(last, handoff(index = 4, at = 1_001_000)), "a new song")
        assertTrue(HandoffPolicy.shouldWrite(last, handoff(playing = false, position = 61_000, at = 1_001_000)), "paused")
        assertTrue(HandoffPolicy.shouldWrite(last, handoff(position = 200_000, at = 1_001_000)), "a seek")
        assertTrue(HandoffPolicy.shouldWrite(last, handoff(songs = queue + song(99), at = 1_001_000)), "the queue changed")
        assertFalse(HandoffPolicy.shouldWrite(last, handoff(position = 90_000, at = 1_030_000)), "just playing on")
        val later = 1_000_000 + HandoffPolicy.PLAYING_EVERY_MS
        assertTrue(HandoffPolicy.shouldWrite(last, handoff(position = 60_000 + HandoffPolicy.PLAYING_EVERY_MS, at = later)), "a playing song is written now and then")
        val paused = handoff(playing = false, at = 1_000_000)
        assertFalse(HandoffPolicy.shouldWrite(paused, handoff(playing = false, at = later + 600_000)), "a paused one is not written again")
    }

    @Test fun anotherDevicesRecentSongIsOfferedOnlyWhenThisOneIsNotPlayingAndHasNotPlayedSince() {
        val now = 2_000_000L
        val remote = handoff(at = 1_900_000, device = "phone")
        assertNotNull(HandoffPolicy.offer(remote, "pc", localPlaying = false, localLastActivityMs = 1_000_000, dismissedAtMs = 0, nowMs = now))
        assertNull(HandoffPolicy.offer(remote, "phone", false, 0, 0, now), "this device's own")
        assertNull(HandoffPolicy.offer(remote, "pc", true, 0, 0, now), "this device is playing")
        assertNull(HandoffPolicy.offer(remote, "pc", false, 1_950_000, 0, now), "this device played since")
        assertNull(HandoffPolicy.offer(remote, "pc", false, 0, 1_900_000, now), "put aside here")
        assertNull(HandoffPolicy.offer(remote, "pc", false, 0, 0, 1_900_000 + HandoffPolicy.OFFER_FOR_MS + 1), "too old")
        assertNull(HandoffPolicy.offer(null, "pc", false, 0, 0, now))
    }
}

class HandoffHostTest {
    private fun song(n: Int) = Song("v$n", "Song $n", "Artist $n", null)

    private class Device(name: String, val cloud: MutableList<PlaybackHandoff>, var clock: Long = 1_000_000L, var on: Boolean = true) {
        val prefs = mutableMapOf<String, String>()
        val host = HandoffHost(
            name, { on }, { cloud += it; true }, { cloud.lastOrNull() }, { prefs[it].orEmpty() }, { k, v -> prefs[k] = v },
            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), { clock },
        )
    }

    @Test fun thePhonesSongIsOfferedOnTheComputerUntilTheComputerPlaysOrPutsItAside() = kotlinx.coroutines.runBlocking {
        val cloud = mutableListOf<PlaybackHandoff>()
        val phone = Device("your phone", cloud)
        val pc = Device("your Windows PC", cloud)
        val queue = (0 until 5).map(::song)

        phone.host.playing(queue, 1, 30_000, 200_000, isPlaying = true)
        phone.host.playing(queue, 1, 31_000, 200_000, isPlaying = true)
        assertEquals(1, cloud.size, "playing on is not written every second")

        pc.clock = phone.clock + 60_000
        pc.host.look(isPlaying = false)
        val offered = assertNotNull(pc.host.elsewhere.value)
        assertEquals("your phone", offered.deviceName)
        assertEquals("v1", offered.song?.videoId)
        assertEquals(3, offered.upNext)

        phone.host.look(isPlaying = false)
        assertNull(phone.host.elsewhere.value, "a device is not offered its own song")

        pc.host.putAside(offered)
        pc.host.look(isPlaying = false)
        assertNull(pc.host.elsewhere.value, "put aside")

        // The phone moves on: a new offer; the computer then plays and the offer goes.
        phone.clock += 120_000
        phone.host.playing(queue, 2, 0, 200_000, isPlaying = true)
        pc.clock = phone.clock + 1_000
        pc.host.look(isPlaying = false)
        assertNotNull(pc.host.elsewhere.value)
        pc.host.playing(queue, 0, 0, 200_000, isPlaying = true)
        assertNull(pc.host.elsewhere.value)
        pc.host.look(isPlaying = false)
        assertNull(pc.host.elsewhere.value, "the computer played after the phone did")
    }

    @Test fun withCloudSyncOffNothingIsSentOrOffered() = kotlinx.coroutines.runBlocking {
        val cloud = mutableListOf<PlaybackHandoff>()
        val phone = Device("your phone", cloud, on = false)
        phone.host.playing(listOf(song(1)), 0, 0, 1000, isPlaying = true)
        assertTrue(cloud.isEmpty())
        cloud += PlaybackHandoff.of("other", "your Mac", listOf(song(2)), 0, 0, 1000, true, phone.clock + 5)!!
        phone.host.look(isPlaying = false)
        assertNull(phone.host.elsewhere.value)
    }
}
