package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.theme.*
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
import com.cosmotune.video.settings.FakeVideoSettingsRepository
import com.cosmotune.video.shorts.FakeShortsFeed
import com.cosmotune.video.source.FakeVideoSource
import com.cosmotune.video.ui.shell.LocalVideoNavigator
import com.cosmotune.video.ui.shell.VideoNavHost
import com.cosmotune.video.ui.shell.VideoNavigator
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/**
 * The owner's steps on the real watch page, phone and wide: open video A from a page, tap video B in its Up next list, then
 * press the page's Minimise button. B takes A's place (A is no page to go back to), and Minimise comes back to the page A was
 * opened from, not to A.
 */
@OptIn(ExperimentalTestApi::class)
class VideoWatchNavigationInteractionTest {
    @get:Rule val compose = createComposeRule()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
        val type = remember { CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(600f, FontFamily.Default, FontFamily.Default) }
        val shell = remember {
            val settings = CosmoLookSettings(object : KeyValueStore {
                override fun getBoolean(key: String, default: Boolean) = default
                override fun putBoolean(key: String, value: Boolean) = Unit
            }).apply { setMode(AppMode.VIDEO) }
            CosmoShellState::class.java.getConstructor(CosmoLookSettings::class.java, Boolean::class.javaPrimitiveType)
                .newInstance(settings, true)
        }
        MaterialTheme { CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalCosmoShell provides shell, LocalReducedMotion provides true) { content() } }
    }

    private fun graph(playback: FakeVideoPlayback) = VideoGraph(
        source = FakeVideoSource(), library = FakeVideoLibrary.withSamples(), settings = FakeVideoSettingsRepository(), segments = FakeSegmentProvider(),
        branding = FakeBrandingProvider(), votes = FakeVotesProvider(), feed = FakeSubscriptionFeed(), downloads = FakeDownloadController(),
        playback = playback, surface = null, music = object : VideoMusicBridge {
            override fun playAsSong(item: VideoItem) = Unit
        },
        metered = { false }, shorts = FakeShortsFeed(), account = FakeVideoAccount(signedIn = true),
    )

    private fun ownersSteps(wide: Boolean) {
        val a = FakeVideoSource.videos[0]
        val b = FakeVideoSource.videos[1]
        val origin = VideoRoute.History
        // A is on, with its details (and so its related videos) loaded, as the real playback has them.
        val details = kotlinx.coroutines.runBlocking { FakeVideoSource().details(a.id) }
        val playback = FakeVideoPlayback(initial = PlaybackUiState.Idle.copy(item = a, details = details, status = PlayerStatus.READY, durationMs = 600_000))
        val navigator = VideoNavigator().apply { push(origin); push(VideoRoute.Watch(a.id)) }
        compose.setContent { Theme {
            CompositionLocalProvider(LocalVideoGraph provides graph(playback), LocalVideoNavigator provides navigator) {
                Box(Modifier.size(if (wide) 1060.dp else 390.dp, 1600.dp)) {
                    VideoNavHost(navigator, PaddingValues(), wide = wide)
                }
            }
        } }

        // Up next lists B, below the card in the page's list: a tap opens it in the page's place.
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Up next").fetchSemanticsNodes().isNotEmpty() }
        // The phone's one list, or the wide page's side column: whichever list holds B's row, once the related list has loaded.
        compose.waitUntil(10_000) {
            val lists = compose.onAllNodes(hasScrollToKeyAction())
            (0 until lists.fetchSemanticsNodes().size).any { runCatching { lists[it].performScrollToKey("related:${b.id}") }.isSuccess }
        }
        compose.waitUntil(10_000) { compose.onAllNodesWithText(b.title).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText(b.title).onFirst().performClick()
        compose.runOnIdle {
            assertEquals(VideoRoute.Watch(b.id), navigator.top)
            assertEquals(listOf(VideoRoute.Home, origin, VideoRoute.Watch(b.id)), navigatorRoutes(navigator), "B replaced A")
            assertEquals(listOf("play(${b.id})"), playback.calls.filter { it.startsWith("play(") }, "the tap plays B")
        }

        // The page's chevron leaves the watch page for the page A was opened from.
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Minimise the player").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithContentDescription("Minimise the player").onFirst().performClick()
        compose.runOnIdle {
            assertEquals(origin, navigator.top, "minimise comes back to the origin, not to A")
            assertFalse(navigator.encode().contains("watch:"), "no watch page is left to go back to")
        }
    }

    /** The routes of the current tab from the root up, read back from the navigator's text. */
    private fun navigatorRoutes(navigator: VideoNavigator): List<VideoRoute> {
        val stack = com.cosmotune.video.nav.VideoBackStack.decode(navigator.encode())!!
        return stack.routesOf(VideoTab.HOME)
    }

    @Test fun aRelatedVideoReplacesTheWatchPageAndMinimiseLeavesForTheOriginOnThePhone() = ownersSteps(wide = false)

    @Test fun aRelatedVideoReplacesTheWatchPageAndMinimiseLeavesForTheOriginOnTheDesktop() = ownersSteps(wide = true)
}
