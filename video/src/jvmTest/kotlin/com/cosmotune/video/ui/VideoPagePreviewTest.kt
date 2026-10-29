package com.cosmotune.video.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cosmotune.video.model.Description
import com.cosmotune.video.nav.VideoRoute
import com.cosmotune.video.ui.channel.ChannelActions
import com.cosmotune.video.ui.channel.ChannelBanner
import com.cosmotune.video.ui.channel.ChannelHead
import com.cosmotune.video.ui.channel.ChannelSection
import com.cosmotune.video.ui.channel.ChannelTabChips
import com.cosmotune.video.ui.comments.CommentsPreviewTile
import com.cosmotune.video.ui.desktop.DesktopVideoState
import com.cosmotune.video.ui.desktop.VideoDesktopHeader
import com.cosmotune.video.ui.desktop.VideoDesktopSidebarContent
import com.cosmotune.video.ui.search.VideoSearch
import com.cosmotune.video.ui.watch.DescriptionInline
import com.cosmotune.video.ui.watch.DescriptionTile
import com.cosmotune.video.ui.watch.UpNextHeader
import com.cosmotune.video.ui.watch.UpNextRow
import com.cosmotune.video.ui.watch.WatchInfo
import com.cosmotune.video.ui.watch.WatchMeta
import com.cosmotune.video.account.FakeVideoAccount
import com.cosmotune.video.downloads.FakeDownloadController
import com.cosmotune.video.feed.FakeSubscriptionFeed
import com.cosmotune.video.graph.LocalVideoGraph
import com.cosmotune.video.graph.VideoGraph
import com.cosmotune.video.library.FakeVideoLibrary
import com.cosmotune.video.model.VideoItem
import com.cosmotune.video.music.VideoMusicBridge
import com.cosmotune.video.player.FakeVideoPlayback
import com.cosmotune.video.segments.FakeBrandingProvider
import com.cosmotune.video.segments.FakeSegmentProvider
import com.cosmotune.video.segments.FakeVotesProvider
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.blocks.LocalVideoNowMs
import com.cosmotune.video.ui.home.VideoHome
import com.cosmotune.video.ui.library.LibraryPage
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavigator
import com.cosmotune.video.ui.subscriptions.SubscriptionsPage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The real pages (Home, You, Subscriptions) drawn to PNGs over the fakes of the graph: no network, no player. The pictures of the
 * videos are gradients with the video's title ([PreviewKit.standIn]), so a feed reads as a feed. At 390 dp (phone) and 1280 dp
 * (desktop; the page beside a 220 dp sidebar is 1060 dp), light and dark, into `build/ui-preview/`. The test asserts each picture is
 * not blank; the looking is done by opening them.
 */
class VideoPagePreviewTest {
    @BeforeTest fun mainDispatcher() = Dispatchers.setMain(Dispatchers.Unconfined)

    @AfterTest fun resetMainDispatcher() = Dispatchers.resetMain()

    private fun graph(signedIn: Boolean) = VideoGraph(
        source = FakeVideoSource(), library = FakeVideoLibrary.withSamples(), settings = FakeVideoSettingsRepository(), segments = FakeSegmentProvider(),
        branding = FakeBrandingProvider(), votes = FakeVotesProvider(), feed = FakeSubscriptionFeed(), downloads = FakeDownloadController(),
        playback = FakeVideoPlayback(), surface = null, music = object : VideoMusicBridge {
            override fun playAsSong(item: VideoItem) = Unit
        },
        metered = { false }, shorts = FakeShortsFeed(), account = FakeVideoAccount(signedIn = signedIn),
    )

    private fun render(
        name: String,
        widthDp: Int,
        heightDp: Int,
        dark: Boolean,
        signedIn: Boolean = false,
        navigator: VideoNavigator = VideoNavigator(),
        content: @Composable () -> Unit,
    ) {
        val graph = graph(signedIn)
        PreviewKit.render(
            name, widthDp, heightDp, dark,
            providers = arrayOf(
                LocalVideoGraph provides graph,
                LocalVideoNavigator provides navigator,
                LocalVideoNowMs provides FakeVideoSource.NOW_MS,
            ),
            content = content,
        )
    }

    private fun both(name: String, signedIn: Boolean = false, phoneHeight: Int = 1500, wideHeight: Int = 1000, wideWidth: Int = 1060, content: (wide: Boolean) -> @Composable () -> Unit) {
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("page-$name-phone-$theme", 390, phoneHeight, dark, signedIn, content = content(false))
            render("page-$name-desktop-$theme", wideWidth, wideHeight, dark, signedIn, content = content(true))
        }
    }

    // The phone's top bar and the desktop page's header stand over the first row: the shell's padding.
    private val phonePadding = PaddingValues(top = 56.dp)

    @Test fun homeDraws() = both("home", phoneHeight = 1700) { wide -> { VideoHome(if (wide) PaddingValues(top = 8.dp) else phonePadding, wide) } }

    @Test fun homeSignedInDraws() = both("home-account", signedIn = true, phoneHeight = 1700) { wide -> { VideoHome(if (wide) PaddingValues(top = 8.dp) else phonePadding, wide) } }

    @Test fun homeOnABigDesktopFillsTheWidth() = render("page-home-desktop-1900-dark", 1700, 900, dark = true) { VideoHome(PaddingValues(top = 8.dp), true) }

    @Test fun youTabDraws() = both("you", signedIn = true, phoneHeight = 1700) { wide -> { LibraryPage(PaddingValues(top = 8.dp), wide) } }

    @Test fun youTabSignedOutDraws() = render("page-you-signed-out-phone-dark", 390, 900, dark = true, signedIn = false) { LibraryPage(PaddingValues(top = 8.dp), false) }

    @Test fun subscriptionsDraws() = both("subscriptions") { wide -> { SubscriptionsPage(PaddingValues(top = 8.dp), wide) } }
    @Test fun searchResultsDraw() = both("search", phoneHeight = 1500) { wide ->
        { VideoSearch(VideoRoute.Search("synth"), if (wide) PaddingValues(top = 8.dp) else phonePadding, wide) }
    }

    private val watched = FakeVideoSource.videos[0]
    private val info = WatchInfo(watched, watched.title, likes = 18_400, dislikes = null, subscriberCount = 1_400_000, subscribed = false, inWatchLater = false, downloadedLabel = null)
    private val description = Description("Today we build a tiny synthesizer from a handful of parts.\n\n0:00 Intro\n1:30 The oscillator\n6:10 The filter\n\nSupport the channel: https://example.com/support", emptyList())

    @Test fun watchBlocksUnderThePlayerDraw() {
        val related = FakeVideoSource.videos.drop(1).take(4)
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("page-watch-phone-$theme", 390, 1700, dark, signedIn = true) {
                Column {
                    Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
                    WatchMeta(info, wide = false, theatre = false, sidePadding = 16.dp, onOpenDescription = {}, onChip = {}, onOpenChannel = {}, onSubscribe = {})
                    DescriptionTile(description, {}, 16.dp)
                    CommentsPreviewTile(videoId = watched.id, onOpen = {})
                    UpNextHeader(autoplay = true, onAutoplay = {})
                    for (item in related) UpNextRow(item, null, false, desktop = false, onPlay = {}, onMore = {})
                }
            }
            render("page-watch-desktop-$theme", 1060, 760, dark, signedIn = true) {
                Row(Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f).background(Color.Black))
                        WatchMeta(info, wide = true, theatre = false, sidePadding = 0.dp, onOpenDescription = null, onChip = {}, onOpenChannel = {}, onSubscribe = {})
                        DescriptionInline(description, {}, 0.dp)
                    }
                    Column(Modifier.width(424.dp).padding(start = 24.dp)) {
                        UpNextHeader(autoplay = true, onAutoplay = {})
                        for (item in related) UpNextRow(item, null, false, desktop = true, onPlay = {}, onMore = {})
                    }
                }
            }
        }
    }

    @Test fun channelHeaderDraws() {
        val channel = FakeVideoSource.channels[0]
        val sections = listOf(ChannelSection.VIDEOS, ChannelSection.SHORTS, ChannelSection.LIVE, ChannelSection.PLAYLISTS, ChannelSection.ABOUT)
        for (dark in listOf(false, true)) {
            val theme = if (dark) "dark" else "light"
            render("page-channel-phone-$theme", 390, 520, dark) {
                Column {
                    ChannelBanner("https://media.cosmotune.invalid/banner/${channel.id}.jpg", wide = false, widthDp = 390f, margin = 16.dp)
                    ChannelHead(channel, wide = false, margin = 16.dp)
                    ChannelActions(subscribed = false, wide = false, margin = 16.dp, preparing = false, onSubscribe = {}, onShare = {}, onDownload = {})
                    ChannelTabChips(sections, ChannelSection.VIDEOS, {})
                }
            }
            render("page-channel-desktop-$theme", 1060, 520, dark) {
                Column {
                    ChannelBanner("https://media.cosmotune.invalid/banner/${channel.id}.jpg", wide = true, widthDp = 1060f, margin = 28.dp)
                    ChannelHead(channel, wide = true, margin = 28.dp)
                    ChannelActions(subscribed = true, wide = true, margin = 28.dp, preparing = false, onSubscribe = {}, onShare = {}, onDownload = {})
                    ChannelTabChips(sections, ChannelSection.VIDEOS, {}, Modifier.padding(horizontal = 12.dp))
                }
            }
        }
    }

    /** The desktop window's Video part: the guide, the header with the search field, and Home; the guide open and folded. */
    @Test fun desktopShellDraws() {
        for (collapsed in listOf(false, true)) {
            DesktopVideoState.sidebarCollapsed = collapsed
            val name = "shell-desktop-${if (collapsed) "rail" else "guide"}-dark"
            render(name, 1280, 800, dark = true, signedIn = true, navigator = DesktopVideoState.navigator) {
                val graph = LocalVideoGraph.current
                SideEffect { DesktopVideoState.graph = graph }
                Row(Modifier.fillMaxSize()) {
                    VideoDesktopSidebarContent(focusSearch = false, onSearchFocused = {}, caption = null)
                    Column(Modifier.weight(1f)) {
                        VideoDesktopHeader()
                        VideoHome(PaddingValues(top = 4.dp), wide = true)
                    }
                }
            }
        }
        DesktopVideoState.sidebarCollapsed = false
        DesktopVideoState.graph = null
    }
}
