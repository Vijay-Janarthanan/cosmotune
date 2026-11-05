package com.cosmotune.music.playback.cast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

/**
 * The remote-speaker seam: Cast and the watch behind one [RemoteSpeaker], as
 * PlaybackService builds it (`Speakers(listOf(castPlayback, watchSpeaker))`)
 * and hands it to its session player.
 */
class SpeakersTest {

    private class FakeSpeaker(
        val name: String,
        override var active: Boolean = false,
        override val playWhenReady: Boolean = false,
        override val isPlaying: Boolean = false,
        override val playbackState: Int = Player.STATE_IDLE,
        override val positionMs: Long = 0L,
        override val durationMs: Long = C.TIME_UNSET,
        override val deviceVolume: Int = 0,
    ) : RemoteSpeaker {
        override var sink: CastSink? = null
        val calls = mutableListOf<String>()
        override fun play() { calls += "play" }
        override fun pause() { calls += "pause" }
        override fun seekTo(positionMs: Long) { calls += "seek:$positionMs" }
        override fun setVolume(fraction: Float) { calls += "volume:$fraction" }
        override fun onLocalTransition(item: MediaItem?, reason: Int) { calls += "transition:$reason" }
        override fun onLocalQueueChanged() { calls += "queue" }
        override fun onLocalRepeatChanged(repeatMode: Int) { calls += "repeat:$repeatMode" }
        override fun onLocalStartedPlaying(player: Player) { calls += "started" }
    }

    private val anyPlayer: Player = Proxy.newProxyInstance(
        Player::class.java.classLoader,
        arrayOf(Player::class.java),
    ) { _, _, _ -> null } as Player

    @Test
    fun `with no receiver active nothing is the speaker and the first answers inertly`() {
        val cast = FakeSpeaker("cast")
        val watch = FakeSpeaker("watch")
        val speakers = Speakers(listOf(cast, watch))
        assertFalse(speakers.active)
        assertEquals(Player.STATE_IDLE, speakers.playbackState)
        assertEquals(C.TIME_UNSET, speakers.durationMs)
    }

    @Test
    fun `the watch as speaker answers the session player's state and transport`() {
        val cast = FakeSpeaker("cast", positionMs = 1L)
        val watch = FakeSpeaker(
            "watch", active = true, playWhenReady = true, isPlaying = true,
            playbackState = Player.STATE_READY, positionMs = 42_000L, durationMs = 180_000L, deviceVolume = 60,
        )
        val speakers = Speakers(listOf(cast, watch))
        assertTrue(speakers.active)
        assertTrue(speakers.isPlaying && speakers.playWhenReady)
        assertEquals(Player.STATE_READY, speakers.playbackState)
        assertEquals(42_000L, speakers.positionMs)
        assertEquals(180_000L, speakers.durationMs)
        assertEquals(60, speakers.deviceVolume)

        speakers.pause()
        speakers.seekTo(90_000L)
        speakers.setVolume(0.25f)
        speakers.play()
        assertEquals(listOf("pause", "seek:90000", "volume:0.25", "play"), watch.calls)
        assertTrue("Cast must not be driven while the watch plays", cast.calls.isEmpty())
    }

    @Test
    fun `Cast as speaker is driven the same way`() {
        val cast = FakeSpeaker("cast", active = true, positionMs = 7L)
        val watch = FakeSpeaker("watch")
        val speakers = Speakers(listOf(cast, watch))
        speakers.seekTo(5L)
        assertEquals(7L, speakers.positionMs)
        assertEquals(listOf("seek:5"), cast.calls)
        assertTrue(watch.calls.isEmpty())
    }

    @Test
    fun `the phone's own moves reach every receiver, active or not`() {
        val cast = FakeSpeaker("cast")
        val watch = FakeSpeaker("watch", active = true)
        val speakers = Speakers(listOf(cast, watch))
        speakers.onLocalTransition(null, Player.MEDIA_ITEM_TRANSITION_REASON_SEEK)
        speakers.onLocalQueueChanged()
        speakers.onLocalRepeatChanged(Player.REPEAT_MODE_ONE)
        speakers.onLocalStartedPlaying(anyPlayer)
        val expected = listOf(
            "transition:${Player.MEDIA_ITEM_TRANSITION_REASON_SEEK}",
            "queue",
            "repeat:${Player.REPEAT_MODE_ONE}",
            "started",
        )
        assertEquals(expected, cast.calls)
        assertEquals(expected, watch.calls)
    }

    @Test
    fun `the session player's sink is handed to every receiver`() {
        val cast = FakeSpeaker("cast")
        val watch = FakeSpeaker("watch")
        val speakers = Speakers(listOf(cast, watch))
        val sink = object : CastSink {
            override fun onRemotePlaybackChanged() = Unit
            override fun onRemoteRouteChanged() = Unit
            override fun onRemoteVolumeChanged() = Unit
        }
        speakers.sink = sink
        assertSame(sink, cast.sink)
        assertSame(sink, watch.sink)
        speakers.sink = null
        assertEquals(null, watch.sink)
    }

    @Test
    fun `switching from Cast to the watch moves who answers without a rebuild`() {
        val cast = FakeSpeaker("cast", active = true, positionMs = 10L)
        val watch = FakeSpeaker("watch", positionMs = 20L)
        val speakers = Speakers(listOf(cast, watch))
        assertEquals(10L, speakers.positionMs)
        cast.active = false
        watch.active = true
        assertEquals(20L, speakers.positionMs)
        speakers.play()
        assertEquals(listOf("play"), watch.calls)
    }

    @Test
    fun `the TV build's stand-in is never the speaker and ignores everything`() {
        assertFalse(NoSpeaker.active)
        assertEquals(Player.STATE_IDLE, NoSpeaker.playbackState)
        assertEquals(C.TIME_UNSET, NoSpeaker.durationMs)
        NoSpeaker.play()
        NoSpeaker.onLocalStartedPlaying(anyPlayer)
        val speakers = Speakers(listOf(NoSpeaker))
        assertFalse(speakers.active)
    }
}
