package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The desktop recap share card, drawn from a summary built in memory (no stored listening is read). */
class DesktopReplayCardTest {
    private val summary = DesktopReplaySummary(
        period = DesktopReplayPeriod.THIS_YEAR, label = "2026", totalMs = 125 * 60_000L, totalPlays = 42,
        songs = (1..7).map { DesktopReplayEntry(Song("v$it", "Song $it", "Artist $it", null), listenedMs = 60_000L * (8 - it), plays = 8 - it) },
        artists = listOf(DesktopRankedEntry("A. R. Rahman", null, null, null, 90_000, 9), DesktopRankedEntry("Ilaiyaraaja", null, null, null, 30_000, 3)),
        distinctSongs = 7, distinctArtists = 2,
    )

    @Test fun theCardSaysThePeriodMinutesAndTopFive() {
        val text = summary.cardText("Vijay")
        assertEquals("Vijay's Replay", text.title)
        assertEquals("2026", text.period)
        assertEquals("125 minutes", text.headline)
        assertEquals(listOf("42 plays", "7 songs", "2 artists"), text.stats)
        assertEquals(5, text.songs.size)
        assertEquals("1. Song 1 · Artist 1", text.songs.first())
        assertEquals(listOf("1. A. R. Rahman", "2. Ilaiyaraaja"), text.artists)
        assertEquals("Replay", DesktopReplaySummary(label = "").cardText("").title)
    }

    @Test fun theCardIsStorySizedAndDrawsTextOnTheBackground() {
        val image = renderReplayCard(summary.cardText("Vijay").copy(songs = listOf("1. " + "Very long title ".repeat(20))))
        assertEquals(1080, image.width); assertEquals(1920, image.height)
        // Bright text pixels exist in the headline band; the background there is dark.
        val bright = (80 until 1000).count { x -> (500 until 700).any { y -> (image.getRGB(x, y) and 0xFF) > 200 } }
        assertTrue(bright > 100, "expected drawn headline text, found $bright bright columns")
        // A long line is shortened to the card instead of running past its right edge.
        assertTrue((1040 until 1080).none { x -> (0 until 1920).any { y -> (image.getRGB(x, y) and 0xFF) > 230 } })
    }
}
