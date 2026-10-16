package com.cosmotune.video.graph

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.segments.FakeBrandingProvider
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.segments.FakeVotesProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.VideoHostLinks

@Composable
actual fun rememberVideoGraph(links: VideoHostLinks): VideoGraph {
    val application = LocalContext.current.applicationContext
    AttachHostLinks(AndroidVideoProcess.relay, links)
    return remember(application) { AndroidVideoProcess.graph(application) }
}

/** The phone's graph: built once, on first use, from the application context. */
private object AndroidVideoProcess {
    val relay = HostLinksRelay()

    private var built: VideoGraph? = null

    @Synchronized
    fun graph(application: Context): VideoGraph = built ?: build(application).also { built = it }

    // One slot per line, a blank line between slots: the wiring task replaces its lines, the Shorts task only its own.
    @Suppress("UNUSED_PARAMETER")
    private fun build(application: Context): VideoGraph {
        val source = FakeVideoSource()   // Y-16 replaces this line

        val library = FakeVideoLibrary()   // Y-16 replaces this line

        val settings = FakeVideoSettingsRepository()   // Y-16 replaces this line

        val segments = FakeSegmentProvider()   // Y-16 replaces this line

        val branding = FakeBrandingProvider()   // Y-16 replaces this line

        val votes = FakeVotesProvider()   // Y-16 replaces this line

        val feed = FakeSubscriptionFeed()   // Y-16 replaces this line

        val downloads = FakeDownloadController()   // Y-16 replaces this line

        val playback = FakeVideoPlayback()   // Y-16 replaces this line

        val surface = null   // Y-16 replaces this line

        val music = HostMusicBridge(relay.links)   // Y-16 replaces this line (the host's bridge: it stays)

        val metered = { false }   // Y-16 replaces this line

        val shorts = FakeShortsFeed()   // Y-31 replaces this line

        return VideoGraph(
            source = source, library = library, settings = settings, segments = segments, branding = branding, votes = votes,
            feed = feed, downloads = downloads, playback = playback, surface = surface, music = music, metered = metered,
            shorts = shorts,
        )
    }
}
