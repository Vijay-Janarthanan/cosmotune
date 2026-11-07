package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.printToString
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.HomeFilter
import com.cosmotune.watch.WatchHome
import com.cosmotune.watch.WatchRepository
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test

/**
 * Every mode's main pages keep what they loaded: leaving a mode (its page leaves the composition, as a mode switch does) and
 * coming back draws the content on the first frame, without the skeleton and without asking the network again inside the
 * freshness window.
 */
class CachedPagesInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val evening = 1_762_461_600_000L

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                "getSettings" -> java.lang.reflect.Proxy.newProxyInstance(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java.classLoader, arrayOf(com.cosmotune.music.ui.player.PlayerSettingsSource::class.java)) { _, _, _ -> MutableStateFlow(false) }
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi); WatchNav.go(WatchTab.HOME) }
    }

    /** Leaves the page (another mode on screen) and comes back, as the mode switcher does. */
    private fun leaveAndReturn(shown: androidx.compose.runtime.MutableState<Boolean>) {
        shown.value = false
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Another mode").fetchSemanticsNodes().isNotEmpty())
        shown.value = true
        compose.waitForIdle()
    }

    private fun waitForText(text: String) = try {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        compose.onRoot(useUnmergedTree = true).printToLog("CachedPages")
        println(compose.onRoot(useUnmergedTree = true).printToString())
        throw e
    }

    private fun assertShows(text: String) =
        assertTrue(compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty(), "\"$text\" is on the page")

    private fun assertNoTag(tag: String) =
        assertTrue(compose.onAllNodesWithTag(tag, useUnmergedTree = true).fetchSemanticsNodes().isEmpty(), "no $tag on the page")

    /** The fake's home, counting its calls. */
    private class CountingWatch(private val fake: WatchRepository) : WatchRepository by fake {
        val homeCalls = AtomicInteger()
        override suspend fun home(filter: HomeFilter): WatchHome = fake.home(filter).also { homeCalls.incrementAndGet() }
    }

    @Test fun watchHomeShowsItsRowsAtOnceOnReturn() = hosts {
        WatchNav.go(WatchTab.HOME)
        val repository = CountingWatch(FakeWatchRepository(now = { evening }))
        val shown = mutableStateOf(true)
        compose.setContent {
            PodioTestTheme {
                Box(Modifier.size(1004.dp, 2400.dp)) {
                    if (shown.value) ProvideWatch(repository = repository, clock = { evening }) { WatchApp(showNavigation = false) }
                    else Text("Another mode")
                }
            }
        }
        waitForText("The Long Night Shift")
        assertEquals(1, repository.homeCalls.get())

        leaveAndReturn(shown)
        assertShows("The Long Night Shift")
        assertNoTag("watch:home:loading")
        assertEquals(1, repository.homeCalls.get(), "no second fetch inside the freshness window")
    }
}
