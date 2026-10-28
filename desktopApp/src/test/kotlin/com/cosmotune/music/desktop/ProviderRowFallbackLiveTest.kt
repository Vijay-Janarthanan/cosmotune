package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assume.assumeTrue
import org.junit.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A row from a provider package whose service cannot serve it (the YouTube Music package answered
 * `api_error` for every recording) used to leave the song silent. Here the package is not even
 * installed, so its own request fails, and the song has to come from another source. Needs the
 * network, so it runs only when asked: `COSMOTUNE_PROVIDER_FALLBACK_LIVE=1`.
 */
internal class ProviderRowFallbackLiveTest {
    @Test(timeout = 200_000) fun aRowItsPackageCannotServeIsPlayedFromAnotherSource() {
        assumeTrue(System.getenv("COSMOTUNE_PROVIDER_FALLBACK_LIVE") == "1")
        runBlocking {
            val song = Song(
                videoId = "src:sflx-ytmusic-spotiflac:notarealid",
                title = "Veezha Veera (From \"OM Chapter 1: Udhiram  - The Blood Wood\") [Tamil]",
                artist = "Sai Abhyankkar, Tippu, Arun Srinivasan",
                thumbnailUrl = null, durationText = "3:57",
            )
            val resolved = assertNotNull(withTimeout(120_000) { DesktopSourceRegistry.resolveLive(song, null) }.getOrNull())
            assertTrue(resolved.stream.url.startsWith("http"), "a real stream came back")
            assertTrue(resolved.stream.sourceId != "sflx-ytmusic-spotiflac", "not from the package that failed")
        }
    }
}
