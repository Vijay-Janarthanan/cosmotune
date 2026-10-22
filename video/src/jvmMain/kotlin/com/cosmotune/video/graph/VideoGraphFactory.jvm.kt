package com.cosmotune.video.graph

import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.downloads.desktop.DesktopDownloadController
import com.cosmotune.video.feed.RefreshLimitedFeed
import com.cosmotune.video.feed.SubscriptionFeedEngine
import com.cosmotune.video.feed.rss.OkHttpFeedFetcher
import com.cosmotune.video.library.FileLibraryFiles
import com.cosmotune.video.library.LibraryPlatform
import com.cosmotune.video.library.VideoLibraryStore
import com.cosmotune.video.library.videoLibraryDir
import com.cosmotune.video.player.controller.VideoPlayerController
import com.cosmotune.video.player.desktop.DesktopVideoEngine
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
import java.util.Locale

@Composable
actual fun rememberVideoGraph(links: VideoHostLinks): VideoGraph {
    AttachHostLinks(DesktopVideoProcess.relay, links)
    val graph = DesktopVideoProcess.graph
    val modes = LocalCosmoShell.current?.settings?.mode
    SideEffect { DesktopVideoProcess.audio?.watch(modes) }
    PauseMusicWhileVideoPlays(graph.playback, links)
    return graph
}

/**
 * Audio is exclusive across the two modes (the controller's ruling): the desktop host calls this when Music starts playing while
 * Video is drawn (the system's media controls can start it), and a video that plays stops. Nothing happens before the graph is
 * built (no video can be playing then).
 */
fun stopVideoForMusic() {
    DesktopVideoProcess.audio?.musicStarted()
}

/** The desktop's graph: built on first use, then shared by every visit to Video. */
private object DesktopVideoProcess {
    val relay = HostLinksRelay()

    /** Every long-lived job of Video mode (the stores' writes, the feed, the player's ticks, the downloads): one per process. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val clock: () -> Long = System::currentTimeMillis

    /** The two modes' audio, once the graph is built. */
    @Volatile
    var audio: ModeAudio? = null
        private set

    val graph: VideoGraph by lazy { build() }

    // One slot per line, a blank line between slots: the wiring task replaces its lines, the Shorts task only its own.
    private fun build(): VideoGraph {
        val files = FileLibraryFiles(videoLibraryDir(LibraryPlatform.Desktop))
        val agent = WebServicesHttp.userAgent(System.getProperty("cosmotune.version") ?: FALLBACK_VERSION)

        val source = NewPipeVideoSource(clock)

        val library = VideoLibraryStore(files, scope, clock)

        val settings = VideoSettingsStore(files, scope)

        val segments = SponsorBlockClient(clock = clock, userAgent = agent)

        val branding = DeArrowClient(clock = clock, userAgent = agent)

        val votes = RydClient(clock = clock, userAgent = agent)

        val feed = RefreshLimitedFeed(SubscriptionFeedEngine(library, source, OkHttpFeedFetcher(), files, settings, scope, clock), library, clock)

        val downloads = DesktopDownloadController(source, settings, scope, segments)

        val metered = { false }   // Y-16 replaces this line (a computer is never on a metered connection here: it stays)

        val engine = DesktopVideoEngine.shared
        val playback = VideoPlayerController(source, engine, library, settings, segments, StreamSelector(), SegmentPolicy, metered, scope, clock)

        val surface = engine

        val music = HostMusicBridge(relay.links)   // Y-16 replaces this line (the host's bridge: it stays)

        val shorts = ShortsQueueEngine(source, feed, library, settings)

        wire(library, settings, source, playback)
        return VideoGraph(
            source = source, library = library, settings = settings, segments = segments, branding = branding, votes = votes,
            feed = feed, downloads = downloads, playback = playback, surface = surface, music = music, metered = metered,
            shorts = shorts,
        )
    }

    /**
     * What the graph needs besides its slots: the source follows the region settings (the device's by default); the stores are
     * written out when the application ends (a change younger than 500 ms would be lost otherwise); the two modes' audio is exclusive.
     */
    private fun wire(library: VideoLibraryStore, settings: VideoSettingsStore, source: NewPipeVideoSource, playback: VideoPlayerController) {
        val device = Locale.getDefault()
        scope.followRegion(settings, source, device.country, device.language)
        Runtime.getRuntime().addShutdownHook(
            Thread({
                runCatching { library.flush() }
                runCatching { settings.flush() }
            }, "cosmotune-video-flush"),
        )
        audio = ModeAudio(playback, scope)
    }

    /** The version the desktop application reports when it was started without one (as its settings footer and update check do). */
    private const val FALLBACK_VERSION = "1.0.0"
}
