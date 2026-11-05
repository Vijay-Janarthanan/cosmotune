package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DesktopMprisTest {
    private val song = Song(
        videoId = "track-1",
        title = "Test track",
        artist = "Test artist",
        thumbnailUrl = null,
    )

    @Test
    fun loadingFirstTrackPublishesPauseCapabilityOnlyOnce() {
        val tracker = MprisCapabilityTracker()
        val empty = DesktopPlaybackState()
        assertEquals(
            mapOf(
                "CanGoNext" to false,
                "CanGoPrevious" to false,
                "CanPlay" to false,
                "CanPause" to false,
                "CanSeek" to false,
            ),
            tracker.changed(empty, force = true).mapValues { it.value.value },
        )

        val playing = DesktopPlaybackState(song = song, isPlaying = true)
        val loadedChanges = tracker.changed(playing).mapValues { it.value.value }
        assertEquals(
            mapOf(
                "CanGoNext" to true,
                "CanGoPrevious" to true,
                "CanPlay" to true,
                "CanPause" to true,
            ),
            loadedChanges,
        )
        assertEquals(MprisCapabilities.from(playing).asProperties()["CanPause"]?.value, loadedChanges["CanPause"])
        assertEquals(emptyMap(), tracker.changed(playing))

        val paused = playing.copy(isPlaying = false)
        assertTrue(MprisCapabilities.from(paused).canPause)
        assertEquals(emptyMap(), tracker.changed(paused))

        val clearedChanges = tracker.changed(empty).mapValues { it.value.value }
        assertEquals(loadedChanges.mapValues { false }, clearedChanges)
        assertEquals(emptyMap(), tracker.changed(empty))
    }

    @Test
    fun durationAndErrorOnlyPublishCapabilitiesThatChanged() {
        val tracker = MprisCapabilityTracker()
        val playing = DesktopPlaybackState(song = song, isPlaying = true)
        tracker.changed(playing, force = true)

        val withDuration = playing.copy(durationMs = 120_000L)
        assertEquals(mapOf("CanSeek" to true), tracker.changed(withDuration).mapValues { it.value.value })

        val failed = withDuration.copy(isPlaying = false, error = "Playback failed")
        assertEquals(mapOf("CanPlay" to false), tracker.changed(failed).mapValues { it.value.value })
        assertEquals(emptyMap(), tracker.changed(failed))
    }

    // ---- BC-19: the rest of the session-bus behaviour, with fakes (no Linux session needed) ----------------------------------

    private val second = 1_000_000_000L

    @Test
    fun positionIsCarriedForwardWhilePlayingAndHeldWhilePausedOrWaitingForAudio() {
        val playing = DesktopPlaybackState(song = song, isPlaying = true, positionMs = 10_000L, positionSampledAtNanos = 5 * second, durationMs = 60_000L)
        assertEquals(10_000_000L, mprisPositionUs(playing, rate = 1.0, nowNanos = 5 * second))
        assertEquals(13_000_000L, mprisPositionUs(playing, rate = 1.0, nowNanos = 8 * second))
        assertEquals(16_000_000L, mprisPositionUs(playing, rate = 2.0, nowNanos = 8 * second), "at the playback rate")
        assertEquals(60_000_000L, mprisPositionUs(playing, rate = 1.0, nowNanos = 500 * second), "never past the end")
        assertEquals(10_000_000L, mprisPositionUs(playing.copy(isPlaying = false), rate = 1.0, nowNanos = 8 * second))
        assertEquals(10_000_000L, mprisPositionUs(playing.copy(awaitingAudio = true), rate = 1.0, nowNanos = 8 * second))
        assertEquals(0L, mprisPositionUs(DesktopPlaybackState(positionMs = -5L), rate = 1.0, nowNanos = 0L))
    }

    @Test
    fun seekIsRelativeAndPastTheEndMeansNext() {
        assertEquals(MprisSeek.To(15_000L), MprisSeeks.relative(currentUs = 10_000_000L, offsetUs = 5_000_000L, durationMs = 60_000L))
        assertEquals(MprisSeek.To(0L), MprisSeeks.relative(currentUs = 3_000_000L, offsetUs = -10_000_000L, durationMs = 60_000L))
        assertEquals(MprisSeek.Next, MprisSeeks.relative(currentUs = 58_000_000L, offsetUs = 5_000_000L, durationMs = 60_000L))
        assertEquals(MprisSeek.To(70_000L), MprisSeeks.relative(currentUs = 65_000_000L, offsetUs = 5_000_000L, durationMs = 0L), "unknown length")
        assertEquals(null, MprisSeeks.relative(currentUs = 1L, offsetUs = 0L, durationMs = 60_000L))
    }

    @Test
    fun setPositionIsIgnoredForAnotherTrackOrOutsideTheTrack() {
        val path = mprisTrackPath(song)
        assertEquals(MprisSeek.To(42_000L), MprisSeeks.absolute(path, path, 42_000_000L, durationMs = 60_000L))
        assertEquals(null, MprisSeeks.absolute(path, mprisTrackPath(song.copy(videoId = "other")), 42_000_000L, 60_000L))
        assertEquals(null, MprisSeeks.absolute(null, path, 42_000_000L, 60_000L))
        assertEquals(null, MprisSeeks.absolute(path, path, -1L, 60_000L))
        assertEquals(null, MprisSeeks.absolute(path, path, 61_000_000L, 60_000L))
    }

    @Test
    fun theControllerSeeksFromTheLivePositionWithoutABus() {
        val seeks = mutableListOf<Long>()
        var nexts = 0
        var live = DesktopPlaybackState(song = song, isPlaying = true, positionMs = 20_000L, positionSampledAtNanos = 1 * second, durationMs = 60_000L)
        val controller = DesktopMprisController(
            onPlay = {}, onPause = {}, onPlayPause = {}, onNext = { nexts++ }, onPrevious = {},
            onShuffleChanged = {}, onLoopStatusChanged = {}, onRateChanged = {}, onVolumeChanged = {},
            onSeek = { seeks += it },
            livePlayback = { live },
            nanoTime = { 3 * second },
        )
        controller.seekBy(5_000_000L) // "five seconds on", from 0:22 (the engine read 0:20 two seconds ago)
        assertEquals(listOf(27_000L), seeks)
        controller.seekTo(mprisTrackPath(song), 7_000_000L)
        controller.seekTo(mprisTrackPath(song.copy(videoId = "stale")), 9_000_000L)
        assertEquals(listOf(27_000L, 7_000L), seeks)
        live = live.copy(positionMs = 59_000L, positionSampledAtNanos = 3 * second)
        controller.seekBy(5_000_000L)
        assertEquals(1, nexts)
        assertEquals(listOf(27_000L, 7_000L), seeks)
    }

    @Test
    fun theFirstPublishSendsEverythingAndLaterOnesOnlyWhatChanged() {
        val publisher = MprisStatePublisher()
        val playing = DesktopPlaybackState(song = song, isPlaying = true, seeks = 1)
        val first = publisher.changes(playing, shuffle = false, loopStatus = "None", rate = 1.0, nowNanos = 0L)
        assertEquals(
            setOf("Metadata", "PlaybackStatus", "Shuffle", "LoopStatus", "Rate", "Volume", "CanGoNext", "CanGoPrevious", "CanPlay", "CanPause", "CanSeek"),
            first.properties.keys,
        )
        assertEquals(null, first.seekedToUs)
        assertEquals(emptyMap(), publisher.changes(playing, false, "None", 1.0, 0L).properties)
        assertEquals(setOf("PlaybackStatus"), publisher.changes(playing.copy(isPlaying = false), false, "None", 1.0, 0L).properties.keys)
        assertEquals(setOf("Shuffle", "LoopStatus"), publisher.changes(playing.copy(isPlaying = false), true, "Playlist", 1.0, 0L).properties.keys)
    }

    @Test
    fun aLengthOrAlbumFoundAfterTheTrackStartedIsSentAsNewMetadata() {
        val publisher = MprisStatePublisher()
        val playing = DesktopPlaybackState(song = song, isPlaying = true)
        publisher.changes(playing, false, "None", 1.0, 0L)
        val withLength = publisher.changes(playing.copy(durationMs = 200_000L), false, "None", 1.0, 0L).properties
        assertTrue("Metadata" in withLength, "the length arrived after the track started")
        @Suppress("UNCHECKED_CAST")
        val metadata = withLength.getValue("Metadata").value as Map<String, org.freedesktop.dbus.types.Variant<*>>
        assertEquals(200_000_000L, metadata.getValue("mpris:length").value)
        val withAlbum = publisher.changes(playing.copy(durationMs = 200_000L, song = song.copy(albumName = "An album")), false, "None", 1.0, 0L)
        assertEquals(setOf("Metadata"), withAlbum.properties.keys)
        assertEquals(mprisTrackPath(song), (mprisMetadata(song, 0L).getValue("mpris:trackid").value as org.freedesktop.dbus.DBusPath).path)
    }

    @Test
    fun aSeekWithinTheSameTrackAnnouncesSeekedButANewTrackDoesNot() {
        val publisher = MprisStatePublisher()
        val playing = DesktopPlaybackState(song = song, isPlaying = true, positionMs = 1_000L, positionSampledAtNanos = second, seeks = 1, durationMs = 60_000L)
        assertEquals(null, publisher.changes(playing, false, "None", 1.0, second).seekedToUs)
        val sought = playing.copy(positionMs = 30_000L, positionSampledAtNanos = 2 * second, seeks = 2, awaitingAudio = true)
        assertEquals(30_000_000L, publisher.changes(sought, false, "None", 1.0, 2 * second).seekedToUs)
        assertEquals(null, publisher.changes(sought.copy(awaitingAudio = false), false, "None", 1.0, 3 * second).seekedToUs, "no new jump")
        val nextTrack = sought.copy(song = song.copy(videoId = "track-2"), positionMs = 0L, seeks = 3)
        assertEquals(null, publisher.changes(nextTrack, false, "None", 1.0, 3 * second).seekedToUs, "a new track is announced by its Metadata")
        val repeatOne = nextTrack.copy(seeks = 4, positionSampledAtNanos = 4 * second)
        assertEquals(0L, publisher.changes(repeatOne, false, "None", 1.0, 4 * second).seekedToUs, "repeat-one restarting the same track is a jump to 0")
    }

    @Test
    fun theSeekedSignalCarriesItsPositionAsTheSpecificationsSignature() {
        val signal = MprisPlayer.Seeked("/org/mpris/MediaPlayer2", 30_000_000L)
        assertEquals(30_000_000L, signal.position)
        assertEquals("org.mpris.MediaPlayer2.Player", signal.`interface`)
        assertEquals("Seeked", signal.name)
        assertEquals("x", signal.sig)
    }
}
