package com.cosmotune.desktop

import com.cosmotune.music.data.lyrics.LyricLine
import com.cosmotune.music.data.lyrics.LyricsSource
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.desktop.DesktopLyrics
import com.cosmotune.music.desktop.DesktopLyricsClient
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class LyricsCacheTest {
    private fun key() = DesktopLyricsClient.LookupKey(
        Song("cache-test-${java.util.UUID.randomUUID()}", "Song", "Artist", null),
        180000, listOf(LyricsSource.LRC_RED, LyricsSource.BINI_LYRICS), false, true,
    )

    @Test fun successfulLookupIsReusedUntilRejected() = runBlocking {
        val key = key()
        var calls = 0
        suspend fun lookup() = DesktopLyricsClient.cachedLookup(key) {
            calls++
            Result.success(DesktopLyrics("Provider $calls", listOf(LyricLine(0, "Words"))))
        }
        assertEquals("Provider 1", lookup().getOrThrow().source)
        assertEquals("Provider 1", lookup().getOrThrow().source)
        DesktopLyricsClient.invalidate(key.song.videoId)
        assertEquals("Provider 2", lookup().getOrThrow().source)
        assertEquals(2, calls)
    }

    @Test fun failuresCanBeRetriedImmediately() = runBlocking {
        val key = key()
        assertTrue(DesktopLyricsClient.cachedLookup(key) { Result.failure(IllegalStateException("Offline")) }.isFailure)
        assertTrue(DesktopLyricsClient.cachedLookup(key) { Result.success(DesktopLyrics("Recovered", emptyList())) }.isSuccess)
    }

    @Test fun sourceOrderPreferencesAndRecordingMetadataEachTriggerFreshLookup() = runBlocking {
        val original = key()
        val keys = listOf(original,
            original.copy(providers = original.providers.reversed()),
            original.copy(providers = original.providers.take(1)),
            original.copy(prioritizeSyllables = true),
            original.copy(allowEmbedded = false),
            original.copy(song = original.song.copy(title = "Song (Tamil)")),
            original.copy(durationMs = 220000))
        var calls = 0
        for (key in keys) DesktopLyricsClient.cachedLookup(key) {
            calls++; Result.success(DesktopLyrics("Provider", emptyList()))
        }
        assertEquals(keys.size, calls)
        for (key in keys) DesktopLyricsClient.cachedLookup(key) { fail("Unchanged lookup should be cached") }
        DesktopLyricsClient.invalidate(original.song.videoId)
    }
}
