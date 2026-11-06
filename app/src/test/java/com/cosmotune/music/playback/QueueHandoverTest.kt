package com.cosmotune.music.playback

import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import java.io.File
import java.lang.reflect.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No queue edit is lost when another player takes over from the session's. A crossfade's standby and a version swap's
 * standby are each given a copy of the queue some seconds before they become the session; every edit made in between went to
 * the outgoing player and used to vanish at the swap (only a crossfade's appends were carried). At the hand-over the incoming
 * player now takes the live queue around the song it is already playing, which is never replaced.
 */
class QueueHandoverTest {

    private fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).build()

    /** A player holding [items], playing [current]; it records whether an edit ever replaced its playing item. */
    private class FakePlayer(items: List<MediaItem>, var current: Int) {
        val items = items.toMutableList()
        var touchedPlaying = false

        val player: Player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getCurrentMediaItemIndex" -> current
                "getMediaItemCount" -> this.items.size
                "getMediaItemAt" -> this.items[args[0] as Int]
                "getCurrentMediaItem" -> this.items.getOrNull(current)
                "replaceMediaItems" -> {
                    val from = args[0] as Int
                    val to = args[1] as Int
                    @Suppress("UNCHECKED_CAST")
                    val new = (args[2] as List<MediaItem>).toList()
                    if (current in from until to) touchedPlaying = true
                    for (i in (to - 1) downTo from) this.items.removeAt(i)
                    this.items.addAll(from, new)
                    if (to <= current) current += new.size - (to - from)
                    null
                }
                else -> null
            }
        } as Player

        fun ids() = items.map { it.mediaId }
    }

    @Test fun aCrossfadeCarriesEveryEditMadeDuringTheBlend() {
        val a = item("a"); val b = item("b"); val c = item("c"); val d = item("d")
        // Armed: the standby got a copy of the queue and plays b, the song fading in.
        val out = FakePlayer(listOf(a, b, c, d), current = 0)
        val into = FakePlayer(listOf(a, b, c, d), current = 1)
        // During the blend, on the outgoing player: c removed, e queued next after b, d dragged up, f appended by AutoPlay.
        val e = item("e"); val f = item("f")
        out.items.remove(c)
        out.items.add(2, e)
        out.items.remove(d); out.items.add(2, d)
        out.items.add(f)

        assertTrue(QueueHandover.crossfade(out.player, into.player))
        assertEquals(listOf("a", "b", "d", "e", "f"), into.ids())
        assertEquals(1, into.current)
        assertSame(b, into.items[into.current])
        assertFalse("the song fading in is never replaced", into.touchedPlaying)
    }

    @Test fun aCrossfadeCarriesEditsBehindThePlayingSong() {
        val x = item("x"); val a = item("a"); val b = item("b"); val c = item("c")
        val out = FakePlayer(listOf(x, a, b, c), current = 1)
        val into = FakePlayer(listOf(x, a, b, c), current = 2)
        out.items.remove(x)
        out.current = 0

        assertTrue(QueueHandover.crossfade(out.player, into.player))
        assertEquals(listOf("a", "b", "c"), into.ids())
        assertEquals(1, into.current)
        assertFalse(into.touchedPlaying)
    }

    @Test fun aRepeatAllWrapFindsTheSongFadingInAtTheStart() {
        val a = item("a"); val b = item("b"); val c = item("c")
        val out = FakePlayer(listOf(a, b, c), current = 2)
        val into = FakePlayer(listOf(a, b, c), current = 0)
        out.items.remove(b)
        out.current = 1

        assertTrue(QueueHandover.crossfade(out.player, into.player))
        assertEquals(listOf("a", "c"), into.ids())
        assertEquals(0, into.current)
    }

    @Test fun aSongFadingInThatWasTakenOutIsNotHandedTo() {
        val a = item("a"); val b = item("b"); val c = item("c")
        val out = FakePlayer(listOf(a, b, c), current = 0)
        val into = FakePlayer(listOf(a, b, c), current = 1)
        out.items.remove(b)

        assertEquals(-1, QueueHandover.incomingIndex(out.items, out.current, b))
        assertFalse(QueueHandover.crossfade(out.player, into.player))
        assertEquals("nothing changed on the standby", listOf("a", "b", "c"), into.ids())
    }

    @Test fun aVersionSwapCarriesEveryEditMadeWhileTheOtherCutLoaded() {
        val a = item("a"); val song = item("song"); val b = item("b"); val c = item("c")
        val otherCut = item("song-video")
        // The standby got a copy with the other cut in the song's place, then took up to seven seconds to be ready.
        val active = FakePlayer(listOf(a, song, b, c), current = 1)
        val standby = FakePlayer(listOf(a, otherCut, b, c), current = 1)
        // Meanwhile: a removed (ahead of the song), d queued next, c dragged above b.
        val d = item("d")
        active.items.remove(a)
        active.current = 0
        active.items.add(1, d)
        active.items.remove(c); active.items.add(2, c)

        assertTrue(QueueHandover.versionSwap(active.player, standby.player))
        assertEquals(listOf("song-video", "d", "c", "b"), standby.ids())
        assertEquals(0, standby.current)
        assertSame(otherCut, standby.items[standby.current])
        assertFalse("the other cut, already playing, is never replaced", standby.touchedPlaying)
    }

    @Test fun anUneditedQueueIsLeftAlone() {
        val a = item("a"); val b = item("b")
        val out = FakePlayer(listOf(a, b), current = 0)
        val into = FakePlayer(listOf(a, b), current = 1)
        assertTrue(QueueHandover.crossfade(out.player, into.player))
        assertEquals(listOf("a", "b"), into.ids())
    }

    @Test fun theSwapFollowsTheSongNotItsIndex() {
        val song = item("song")
        assertTrue(QueueHandover.sameItem(song, song))
        assertTrue("the same entry read back again", QueueHandover.sameItem(item("song"), song))
        assertFalse(QueueHandover.sameItem(item("other"), song))
        assertFalse(QueueHandover.sameItem(null, song))
    }

    @Test fun bothHandOversCarryTheLiveQueue() {
        val dir = listOf(File("src/main/java/com/cosmotune/music/playback"), File("app/src/main/java/com/cosmotune/music/playback"))
            .first { it.isDirectory }
        val crossfade = File(dir, "CrossfadeController.kt").readText()
        val handOff = crossfade.substringAfter("private fun handOff(").substringBefore("onHandoff(out, into)")
        assertTrue("the crossfade carries the queue before the swap", "reconcileQueue(out, into)" in handOff)
        assertTrue("QueueHandover.crossfade(out, into)" in crossfade)
        val service = File(dir, "PlaybackService.kt").readText()
        val adopt = service.indexOf("adoptPlayerForVersionSwap(outgoing = activePlayer, incoming = standbyPlayer)")
        val carry = service.lastIndexOf("QueueHandover.versionSwap(activePlayer, standbyPlayer)", adopt)
        assertTrue("the version swap carries the queue right before it adopts the standby", carry in 0 until adopt)
    }
}
