package com.cosmotune.music

import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.datasource.okhttp.OkHttpDataSource
import androidx.media3.exoplayer.analytics.AnalyticsListener
import androidx.media3.exoplayer.source.LoadEventInfo
import androidx.media3.exoplayer.source.MediaLoadData
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.music.playback.toMediaItem
import com.cosmotune.podio.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PodioAndroidTest {
    @Test fun publisherRssUsesTheAndroidXmlParserWithoutExternalEntities() {
        val parsed = PodioCatalogue.parseFeed("""<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>Podcast</title><item><guid>one</guid><title>Episode</title><enclosure url="https://example.com/audio.mp3" type="audio/mpeg"/><itunes:duration>12:34</itunes:duration></item></channel></rss>""", "https://example.com/feed")
        assertEquals(754, parsed.episodes.single().durationSeconds)
        assertTrue(parsed.episodes.single().item().song().toMediaItem().localConfiguration!!.uri.toString().startsWith("https://"))
        assertTrue(runCatching { PodioCatalogue.parseFeed("<!DOCTYPE rss><rss/>", "https://example.com/feed") }.isFailure)
    }
    @Test fun publisherRssAndSpotifyLinksMatchedToRssPlayWithoutAnAccount() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("podioLive") == "true")
        val catalogue = PodioCatalogue()
        val feed = catalogue.feed("https://lexfridman.com/feed/podcast/")
        decode("publisher RSS", feed.episodes.first().url)
        val spotify = catalogue.feed("https://open.spotify.com/show/2MAi0BvDc6GTFvKFPXnkCL")
        assertTrue(spotify.episodes.isNotEmpty())
        val item = spotify.episodes.first().item()
        assertFalse(item.id.startsWith("podio:episode:spotify:"))
        decode("publisher RSS matched from Spotify link", item.url, seek = true)
    }
    private suspend fun decode(label: String, url: String, seek: Boolean = false) {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val client = okhttp3.OkHttpClient.Builder().connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
        var bytes = 0L
        var loadError = "none"
        val player = withContext(Dispatchers.Main) { ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(context).setDataSourceFactory(OkHttpDataSource.Factory(client)))
            .build().apply {
            volume = 0f
            addAnalyticsListener(object : AnalyticsListener {
                override fun onLoadCompleted(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData) {
                    bytes += loadEventInfo.bytesLoaded
                }
                override fun onLoadError(eventTime: AnalyticsListener.EventTime, loadEventInfo: LoadEventInfo, mediaLoadData: MediaLoadData,
                    error: java.io.IOException, wasCanceled: Boolean) { loadError = error.javaClass.simpleName }
            })
            setMediaItem(MediaItem.fromUri(url)); prepare(); play()
        } }
        try {
            withTimeout(45_000) {
                while (!withContext(Dispatchers.Main) {
                    player.playerError?.let { throw it }
                    player.currentPosition > 300 && (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > 0
                }) delay(100)
            }
            println("Podio Android $label rendered PCM successfully")
            if (seek) {
                val before = withContext(Dispatchers.Main) { (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0).also { player.seekTo(60_000) } }
                withTimeout(20_000) {
                    while (!withContext(Dispatchers.Main) {
                        player.playerError?.let { throw it }
                        player.currentPosition > 60_300 && (player.audioDecoderCounters?.renderedOutputBufferCount ?: 0) > before
                    }) delay(100)
                }
                println("Podio Android publisher audio rendered after seeking")
            }
        } catch (e: TimeoutCancellationException) {
            val state = withContext(Dispatchers.Main) {
                val tracks = player.currentTracks.groups.joinToString { group ->
                    (0 until group.length).joinToString { index ->
                        val format = group.getTrackFormat(index)
                        "${format.sampleMimeType}/${format.codecs}/support=${group.getTrackSupport(index)}/selected=${group.isTrackSelected(index)}"
                    }
                }
                "state=${player.playbackState}, position=${player.currentPosition}, buffered=${player.bufferedPosition}, bytes=$bytes, loadError=$loadError, tracks=$tracks"
            }
            throw AssertionError("Podio $label did not render: $state", e)
        } finally { withContext(Dispatchers.Main) { player.release() } }
    }
}
