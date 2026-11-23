package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Watch search on a 360 dp phone: before typing it offers recent searches, Browse chips and Popular now; a Browse chip browses
 * with the genre set and shows the filter row; nothing found offers Clear filters; the Search button pressed again clears the box.
 */
class WatchSearchInteractionTest {
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


    private fun waitFor(text: String) = try {
        compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
    } catch (e: Throwable) {
        println(compose.onRoot(useUnmergedTree = true).printToString())
        throw e
    }

    private fun shows(text: String) = compose.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty()

    @Test fun browseFilterClearAndSearchAgain() = hosts {
        WatchNav.go(WatchTab.SEARCH)
        val repository = FakeWatchRepository(now = { evening })
        compose.setContent {
            PodioTestTheme {
                Box(Modifier.size(360.dp, 780.dp)) { ProvideWatch(repository = repository, clock = { evening }) { WatchApp(showNavigation = true) } }
            }
        }
        waitFor("sources on")
        assertTrue(shows("Recent") && shows("harbour town"), "the viewer's recent searches")
        assertTrue(shows("Browse"), "the Browse chips")
        waitFor("Popular now")
        for (kind in listOf("All", "Films", "Series", "Anime", "Live TV")) assertTrue(shows(kind), "the $kind chip")

        compose.onAllNodesWithText("Drama")[0].performClick()
        waitFor("1 source")
        assertTrue(compose.onAllNodesWithTag("search:filter:genre", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty(), "the filter row shows")
        assertTrue(shows("Ember Road") && shows("Harbour Town"), "an empty box with a genre browses it")
        assertTrue(!shows("The Cartographer"), "an adventure is not drama")

        compose.onNode(hasSetTextAction()).performTextInput("zzz")
        waitFor("Clear filters")
        compose.onAllNodesWithText("Clear filters")[0].performClick()
        compose.waitUntil(20_000) { !shows("Clear filters") && compose.onAllNodesWithTag("search:empty", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

        WatchNav.tapSearch()
        waitFor("Recent")
        assertEquals(WatchTab.SEARCH, WatchNav.tab.value)
        compose.onNodeWithTag("search:idle", useUnmergedTree = true)
    }
}
