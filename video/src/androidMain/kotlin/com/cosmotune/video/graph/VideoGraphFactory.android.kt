package com.cosmotune.video.graph

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.net.ConnectivityManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.downloads.android.AndroidDownloadController
import com.cosmotune.video.feed.RefreshLimitedFeed
import com.cosmotune.video.feed.SubscriptionFeedEngine
import com.cosmotune.video.feed.rss.OkHttpFeedFetcher
import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.VideoLibraryStore
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.player.android.AndroidVideoEngine
import com.cosmotune.video.player.android.VideoPlaybackService
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.segments.DeArrowClient
import com.cosmotune.video.segments.RydClient
import com.cosmotune.video.segments.SegmentPolicy
import com.cosmotune.video.segments.SponsorBlockClient
import com.cosmotune.video.segments.WebServicesHttp
import com.cosmotune.video.select.StreamSelector
import com.cosmotune.video.settings.VideoSettingsStore
import com.cosmotune.video.shorts.ShortsQueueEngine
import com.cosmotune.video.source.newpipe.NewPipeVideoSource
import com.cosmotune.video.ui.VideoHostLinks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Locale

@Composable
actual fun rememberVideoGraph(links: VideoHostLinks): VideoGraph {
    val application = LocalContext.current.applicationContext
    AttachHostLinks(AndroidVideoProcess.relay, links)
    val graph = remember(application) { AndroidVideoProcess.graph(application) }
    val modes = LocalCosmoShell.current?.settings?.mode
    SideEffect { AndroidVideoProcess.audio?.watch(modes) }
    PauseMusicWhileVideoPlays(graph.playback, links)
    return graph
}

/** The phone's graph: built once, on first use, from the application context. */
private object AndroidVideoProcess {
    val relay = HostLinksRelay()

    /** Every long-lived job of Video mode (the stores' writes, the feed, the player's ticks, the downloads): one per process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    private var built: VideoGraph? = null

    /** The two modes' audio, once the graph is built. */
    @Volatile
    var audio: ModeAudio? = null
        private set

    @Synchronized
    fun graph(application: Context): VideoGraph = built ?: build(application).also { built = it }

    // One slot per line, a blank line between slots: the wiring task replaces its lines, the Shorts task only its own.
    private fun build(application: Context): VideoGraph {
        val files = FileLibraryFiles(videoLibraryDir(LibraryPlatform.Android(application.filesDir)))
        val agent = WebServicesHttp.userAgent(appVersion(application))
        val connectivity = application.getSystemService(ConnectivityManager::class.java)

        val source = NewPipeVideoSource(clock)

        val library = VideoLibraryStore(files, scope, clock)

        val settings = VideoSettingsStore(files, scope)

        val segments = SponsorBlockClient(clock = clock, userAgent = agent)

        val branding = DeArrowClient(clock = clock, userAgent = agent)

        val votes = RydClient(clock = clock, userAgent = agent)

        val feed = RefreshLimitedFeed(SubscriptionFeedEngine(library, source, OkHttpFeedFetcher(), files, settings, scope, clock), library, clock)

        val downloads = AndroidDownloadController(application, source, settings, scope, segments)

        val metered = { connectivity?.isActiveNetworkMetered ?: false }

        val engine = AndroidVideoEngine(application)
        val playback = VideoPlayerController(source, engine, library, settings, segments, StreamSelector(), SegmentPolicy, metered, scope, clock)

        val surface = engine

        val music = HostMusicBridge(relay.links)   // Y-16 replaces this line (the host's bridge: it stays)

        val shorts = ShortsQueueEngine(source, feed, library, settings)

        wire(application, library, settings, source, playback)
        return VideoGraph(
            source = source, library = library, settings = settings, segments = segments, branding = branding, votes = votes,
            feed = feed, downloads = downloads, playback = playback, surface = surface, music = music, metered = metered,
            shorts = shorts,
        )
    }

    /**
     * What the graph needs besides its slots: the source follows the region settings (the device's by default); the stores are
     * written out when the app's screen goes to the background (a change younger than 500 ms would be lost with the process); the
     * notification's previous and next drive the controller's queue; the two modes' audio is exclusive.
     */
    private fun wire(application: Context, library: VideoLibraryStore, settings: VideoSettingsStore, source: NewPipeVideoSource, playback: VideoPlayerController) {
        val device = Locale.getDefault()
        scope.followRegion(settings, source, device.country, device.language)
        application.registerComponentCallbacks(object : ComponentCallbacks2 {
            override fun onTrimMemory(level: Int) {
                if (level >= ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN) {
                    scope.launch(Dispatchers.IO) {
                        library.flush()
                        settings.flush()
                    }
                }
            }

            override fun onConfigurationChanged(newConfig: Configuration) = Unit

            @Deprecated("Deprecated in Java")
            override fun onLowMemory() = Unit
        })
        VideoPlaybackService.queueButtons = object : VideoPlaybackService.QueueButtons {
            override fun previous() = playback.playPrevious()

            override fun next() = playback.playNext()
        }
        audio = ModeAudio(playback, scope)
    }

    /** The app's version name (the User-Agent of the three web services says it), or null when the system does not tell. */
    private fun appVersion(application: Context): String? = try {
        application.packageManager.getPackageInfo(application.packageName, 0).versionName
    } catch (e: Exception) {
        null
    }
}
