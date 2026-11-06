package com.cosmotune.video.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.theme.CosmoShellState
import com.cosmotune.ui.theme.LocalCosmoShell
import com.cosmotune.video.account.FakeVideoAccount
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.music.VideoMusicBridge
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.nav.VideoTab
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.player.PlaybackUiState
import com.cosmotune.video.player.PlayerStatus
import com.cosmotune.video.segments.FakeBrandingProvider
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.segments.FakeVotesProvider
import com.cosmotune.video.segments.SegmentAction
import com.cosmotune.video.segments.SegmentCategory
import com.cosmotune.video.segments.SkipSegment
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.shorts.ShortsOrigin
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavHost
import com.cosmotune.video.ui.shell.VideoNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The Orbit screens of Video mode (Canvas repo, design/cosmotune/batch-be/board.html section B) drawn to PNGs over the fakes, through
 * the real nav host: at 390 dp (phone) and 1060 dp (the desktop page beside its sidebar), light and dark, into
 * `build/ui-preview/orbit-<page>-<phone|wide>-<theme>.png`. The watch page draws with a video loaded (playing, a quarter in, a
 * sponsor segment on the line). Each picture is checked not to be blank; the looking is done by opening them.
 */
class VideoOrbitRenderTest {
    @BeforeTest fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private val sponsor = SkipSegment("seg-1", SegmentCategory.SPONSOR, SegmentAction.SKIP, 312_000, 348_000, 600_000, 12, false, null)

    private fun playing() = FakeVideoPlayback(
        initial = PlaybackUiState.Idle.copy(
            item = FakeVideoSource.videos[0], status = PlayerStatus.READY, playing = true, durationMs = 600_000, bufferedMs = 260_000,
            segments = listOf(sponsor), queue = FakeVideoSource.videos.drop(1).take(3),
        ),
    ).apply { position = 150_000 }

    private fun graph(playback: FakeVideoPlayback) = VideoGraph(
        source = FakeVideoSource(), library = FakeVideoLibrary.withSamples(), settings = FakeVideoSettingsRepository(), segments = FakeSegmentProvider(),
        branding = FakeBrandingProvider(), votes = FakeVotesProvider(), feed = FakeSubscriptionFeed(), downloads = FakeDownloadController(),
        playback = playback, surface = null, music = object : VideoMusicBridge {
            override fun playAsSong(item: VideoItem) = Unit
        },
        metered = { false }, shorts = FakeShortsFeed(), account = FakeVideoAccount(signedIn = true),
    )

    private fun shellState(dark: Boolean): CosmoShellState {
        val settings = CosmoLookSettings(object : KeyValueStore {
            override fun getBoolean(key: String, default: Boolean) = default
            override fun putBoolean(key: String, value: Boolean) = Unit
        }).apply { setMode(AppMode.VIDEO) }
        val constructor = CosmoShellState::class.java.declaredConstructors.single { it.parameterCount == 2 }
        constructor.isAccessible = true
        return constructor.newInstance(settings, dark) as CosmoShellState
    }

    /** [route] through the nav host, phone and wide, in both palettes. */
    private fun page(name: String, route: VideoRoute, phoneHeight: Int = 1500, wideHeight: Int = 900, padTop: Boolean = true) {
        for (dark in listOf(true, false)) {
            val theme = if (dark) "dark" else "light"
            for (wide in listOf(false, true)) {
                val navigator = VideoNavigator()
                when (route) {
                    VideoRoute.Home -> Unit
                    VideoRoute.Subscriptions -> navigator.selectTab(VideoTab.SUBSCRIPTIONS)
                    VideoRoute.Library -> navigator.selectTab(VideoTab.LIBRARY)
                    else -> navigator.push(route)
                }
                val playback = playing()
                PreviewKit.render(
                    "orbit-$name-${if (wide) "wide" else "phone"}-$theme", if (wide) 1060 else 390, if (wide) wideHeight else phoneHeight, dark, frames = 70,
                    providers = arrayOf(
                        LocalVideoGraph provides graph(playback),
                        LocalVideoNavigator provides navigator,
                        LocalVideoNowMs provides FakeVideoSource.NOW_MS,
                        LocalCosmoShell provides shellState(dark),
                    ),
                ) {
                    Box(Modifier.fillMaxSize()) {
                        VideoNavHost(navigator, PaddingValues(top = if (padTop) (if (wide) 8.dp else 52.dp) else 0.dp, bottom = 24.dp), wide = wide)
                    }
                }
            }
        }
    }

    @Test fun home() = page("home", VideoRoute.Home, phoneHeight = 2300, wideHeight = 1500)

    @Test fun watch() = page("watch", VideoRoute.Watch(FakeVideoSource.videos[0].id), phoneHeight = 1500, wideHeight = 820, padTop = false)

    @Test fun shorts() = page("shorts", VideoRoute.Shorts(ShortsOrigin.Home), phoneHeight = 800, wideHeight = 800, padTop = false)

    @Test fun channel() = page("channel", VideoRoute.Channel(FakeVideoSource.CHANNEL_1), phoneHeight = 1500, wideHeight = 1000)

    @Test fun subscriptions() = page("subscriptions", VideoRoute.Subscriptions, phoneHeight = 1400, wideHeight = 1000)

    @Test fun library() = page("library", VideoRoute.Library, phoneHeight = 1600, wideHeight = 1100)

    @Test fun history() = page("history", VideoRoute.History, phoneHeight = 1000, wideHeight = 900)

    @Test fun downloads() = page("downloads", VideoRoute.Downloads, phoneHeight = 900, wideHeight = 800)

    @Test fun playlist() = page("playlist", VideoRoute.Playlist(FakeVideoSource.PLAYLIST_ID), phoneHeight = 1300, wideHeight = 900)

    @Test fun search() = page("search", VideoRoute.Search("synth"), phoneHeight = 1500, wideHeight = 1000)

    @Test fun settings() = page("settings", VideoRoute.Settings, phoneHeight = 2000, wideHeight = 1200)
}
