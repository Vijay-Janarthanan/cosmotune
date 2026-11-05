package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.playlistMoves
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * BC-35 on the desktop, with no window, network or registry: the reorder dialog's order (each move reads the list as it is now,
 * the bug `94cba2fa` fixed upstream) and what saving sends, and which Last.fm credential signs a request.
 */
class DesktopPlaylistEditTest {
    private fun entry(id: String, slot: String) = Song(videoId = id, title = id, artist = "Artist", thumbnailUrl = null, setVideoId = slot)
    private val playlist = listOf(entry("a", "s1"), entry("b", "s2"), entry("c", "s3"), entry("a", "s4"))

    @Test fun movesReadTheCurrentOrderOneAfterAnother() {
        var order = playlist
        order = DesktopReorder.move(order, 3, 2)   // the second copy of "a" up
        order = DesktopReorder.move(order, 2, 1)   // and up again: it is at 2 now, not at 3
        assertEquals(listOf("s1", "s4", "s2", "s3"), order.map { it.setVideoId })
        assertEquals(order, DesktopReorder.move(order, 0, -1), "the first row cannot go further up")
        assertEquals(order, DesktopReorder.move(order, 3, 4), "nor the last further down")
    }

    @Test fun savingIsOfferedOnlyForAChangedOrderAndSendsTheFewestMoves() {
        assertFalse(DesktopReorder.changed(playlist, playlist))
        val reordered = DesktopReorder.move(playlist, 0, 3)
        assertTrue(DesktopReorder.changed(playlist, reordered))
        // What reorderOpenPlaylist hands DesktopSearchClient.reorderPlaylist: the slots, as they were and as they are left.
        val moves = playlistMoves(playlist.mapNotNull { it.setVideoId }, reordered.mapNotNull { it.setVideoId })
        assertEquals(1, moves.size, "one row moved, one move: $moves")
        assertEquals("s1", moves.single().setVideoId)
        assertNull(moves.single().before, "to the end")
    }

    @Test fun twoCopiesOfOneSongKeepTheirOwnKeys() {
        assertEquals(listOf("s1", "s2", "s3", "s4"), playlist.mapIndexed { i, s -> DesktopReorder.keyOf(s, i) })
        assertEquals("x-0", DesktopReorder.keyOf(Song(videoId = "x", title = "x", artist = "", thumbnailUrl = null), 0))
    }

    @Test fun theListenersOwnLastFmKeyWinsThenTheBuildsThenTheEnvironments() {
        assertEquals("mine", DesktopLastFmCredentials.pick(" mine ", "built", "env"))
        assertEquals("built", DesktopLastFmCredentials.pick("  ", "built", "env"))
        assertEquals("env", DesktopLastFmCredentials.pick(null, null, "env"))
        assertNull(DesktopLastFmCredentials.pick("", " ", null))
    }

    @Test fun theSignInAsksForTheAppKeyOnlyOnABuildWithoutOne() {
        assertTrue(DesktopLastFmCredentials.signInReady(needsAppKey = false, apiKey = "", apiSecret = "", username = "u", password = "p"))
        assertFalse(DesktopLastFmCredentials.signInReady(needsAppKey = true, apiKey = "k", apiSecret = "", username = "u", password = "p"))
        assertTrue(DesktopLastFmCredentials.signInReady(needsAppKey = true, apiKey = "k", apiSecret = "s", username = "u", password = "p"))
        assertFalse(DesktopLastFmCredentials.signInReady(needsAppKey = false, apiKey = "k", apiSecret = "s", username = "u", password = ""))
    }
}
