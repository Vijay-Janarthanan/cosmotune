package com.cosmotune.watch

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalPreviewsTest {
    @Test fun plansAtMostAHundredFramesOnWholeSeconds() {
        val film = LocalPreviews.plan(2 * 3_600_000L + 7_000)!!
        assertEquals(73_000, film.intervalMs)
        assertEquals(98, film.frames)
        assertEquals(10, film.columns)
        assertEquals(10, film.rows)
        val episode = LocalPreviews.plan(4 * 60_000L)!!
        assertEquals(5_000, episode.intervalMs, "never closer than 5 s")
        assertEquals(48, episode.frames)
        assertEquals(5, episode.rows)
        assertNull(LocalPreviews.plan(20_000), "a clip too short to need them")
        assertEquals(listOf(2_500L, 7_500L), LocalPreviews.times(episode).take(2))
        assertEquals(com.cosmotune.watch.model.SeekPreviews.Frame(0, 3, 1), episode.frameAt(LocalPreviews.times(episode)[13]))
    }

    @Test fun findsThePathOfAFileOnThisDevice() {
        assertEquals("/storage/emulated/0/Movies/Watch/A Film.mkv", LocalPreviews.pathOf("file:///storage/emulated/0/Movies/Watch/A%20Film.mkv"))
        assertEquals("C:/Users/me/Videos/Café.mp4", LocalPreviews.pathOf("file:/C:/Users/me/Videos/Caf%C3%A9.mp4"))
        assertEquals("D:\\Films\\x.mkv", LocalPreviews.pathOf("D:\\Films\\x.mkv"))
        assertNull(LocalPreviews.pathOf("https://example.com/x.mkv"))
    }
}
