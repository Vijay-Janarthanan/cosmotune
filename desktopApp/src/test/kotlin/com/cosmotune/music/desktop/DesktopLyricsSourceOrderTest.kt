package com.cosmotune.music.desktop

import com.cosmotune.music.data.lyrics.LyricsSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The source list, as the settings dialog stores it and the lookup reads it back. */
class DesktopLyricsSourceOrderTest {

    @Test
    fun theChainIsTheStoredOrderNarrowedToWhatIsTicked() {
        val order = listOf("lrc.red", "LRCLIB", "YouTube Music")
        assertEquals(
            listOf("lrc.red", "YouTube Music"),
            DesktopLyricsClient.enabledSources(order, setOf("YouTube Music", "lrc.red")),
        )
    }

    @Test
    fun untickingEverythingLeavesNothingToAsk() {
        assertEquals(emptyList(), DesktopLyricsClient.enabledSources(listOf("LRCLIB"), emptySet()))
    }

    @Test
    fun theDesktopOffersThePhonesProvidersInThePhonesOrder() {
        // One provider list for both apps: the lookup is the phone's LyricsRepository.
        val names = DesktopLyricsClient.sources.map { it.name }
        assertEquals(LyricsSource.offered.map { it.label }, names)
        assertTrue(names.all { lyricsSourceNamed(it) != null })
        // Word-timed providers first; the name-matched line-synced library, which still finds uploads and local
        // files, last.
        assertEquals("lrc.red", names.first())
        assertEquals("LRCLIB", names.last())
    }

    @Test
    fun everySourceOfferedIsOneTheLookupCanActuallyAsk() {
        // The dialog is built from [sources]; a name in it with no provider behind it is a row that
        // silently does nothing when ticked.
        val order = DesktopLyricsClient.sources.map { it.name }
        assertEquals(order, DesktopLyricsClient.enabledSources(order, order.toSet()))
    }
}
