package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.SearchFilter
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.screens.SearchScreen
import com.cosmotune.ui.search.CosmoSearch
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoType
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import kotlin.test.assertEquals

/**
 * BC-31, rendered on both looks: the Add-ons source shows the add-ons' rows with their offered tier and no YouTube filters, its
 * own recent words while nothing is submitted, and a tap on a row plays from the add-on list (not YouTube's).
 */
class AddonSearchInteractionTest {
    @get:Rule val compose = createComposeRule()

    private val atmos = Song("src:a::tidal:2", "Atmos mix", "Singer", null, sourceQuality = "DOLBY")
    private val stereo = Song("src:a::tidal:1", "Stereo mix", "Singer", null, sourceQuality = "LOSSLESS")

    private fun withRowHosts(block: () -> Unit) {
        val installedHost = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installedHost.get(null)
        val installedUi = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousUi = installedUi.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) { homeRecentsViewType.value = value }
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        val host = java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, args ->
            when (method.name) {
                "rememberRemoteArtworkUrl" -> (args?.getOrNull(0) as? Song)?.thumbnailUrl
                else -> error("A search row unexpectedly requested ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { installedHost.set(null, previous); installedUi.set(null, previousUi) }
    }

    @Composable
    private fun NewLook(content: @Composable () -> Unit) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(400f, FontFamily.Default, FontFamily.Default)
        MaterialTheme {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                Box(Modifier.width(400.dp).fillMaxHeight()) { content() }
            }
        }
    }

    private class Taps { var youtube = 0; var addon: Song? = null; var history: String? = null }

    @Composable
    private fun Page(newLook: Boolean, query: String, results: UiState<List<Song>>?, history: List<String>, taps: Taps) {
        val listState = rememberLazyListState()
        if (newLook) CosmoSearch(
            query = query, onQueryChange = {}, filter = SearchFilter.ALL, currentSong = null, isPlaying = false, onFilterChange = {},
            results = null, loadingMore = false, onLoadMore = {}, listState = listState, scrollResetTrigger = 0, focusRequested = false,
            onFocusHandled = {}, onSongClick = { _, _ -> taps.youtube++ }, onSongLongPress = {}, onSongSwipe = {}, onTopResultPlay = {},
            onTopResultPlaylist = {}, onBrowseClick = {}, history = emptyList(), suggestions = emptyList(), typeaheadResults = emptyList(),
            onSubmit = {}, onSuggestionClick = {}, onHistoryClick = {}, onHistoryRemove = {}, onHistoryClear = {},
            contentPadding = PaddingValues(0.dp), topPadding = 0.dp, showField = false,
            searchingAddons = true, addonResults = results, addonHistory = history, onAddonHistoryClick = { taps.history = it },
            onAddonSongClick = { songs, index -> taps.addon = songs[index] },
        ) else SearchScreen(
            query = query, onQueryChange = {}, filter = SearchFilter.ALL, currentSong = null, isPlaying = false, onFilterChange = {},
            results = null, loadingMore = false, onLoadMore = {}, listState = listState, scrollResetTrigger = 0, focusRequested = false,
            onFocusHandled = {}, onSongClick = { _, _ -> taps.youtube++ }, onSongLongPress = {}, onSongSwipe = {}, onTopResultPlay = {},
            onTopResultPlaylist = {}, onBrowseClick = {}, history = emptyList(), suggestions = emptyList(), typeaheadResults = emptyList(),
            onSubmit = {}, onSuggestionClick = {}, onHistoryClick = {}, onHistoryRemove = {}, onHistoryClear = {},
            contentPadding = PaddingValues(0.dp), topPadding = 0.dp, showField = false,
            searchingAddons = true, addonResults = results, addonHistory = history, onAddonHistoryClick = { taps.history = it },
            onAddonSongClick = { songs, index -> taps.addon = songs[index] },
        )
    }

    private fun resultsShowTheTierAndPlayFromTheAddonList(newLook: Boolean) = withRowHosts {
        val taps = Taps()
        compose.setContent { NewLook { Page(newLook, "mix", UiState.Success(listOf(stereo, atmos)), emptyList(), taps) } }
        compose.onAllNodesWithText("Dolby Atmos", substring = true, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Lossless", substring = false, useUnmergedTree = true).assertCountEquals(1)
        compose.onAllNodesWithText("Songs", substring = false, useUnmergedTree = true).assertCountEquals(0)
        compose.onNodeWithText("Atmos mix", substring = true).performClick()
        assertEquals(atmos, taps.addon)
        assertEquals(0, taps.youtube, "an add-on row is not played as a YouTube search hit")
    }

    private fun recentWordsShowUntilSomethingIsSubmitted(newLook: Boolean) = withRowHosts {
        val taps = Taps()
        compose.setContent { NewLook { Page(newLook, "", null, listOf("aurora", "hans zimmer"), taps) } }
        compose.onNodeWithText("Recent searches").assertExists()
        compose.onNodeWithText("hans zimmer").performClick()
        assertEquals("hans zimmer", taps.history)
    }

    private fun anEmptyAnswerSaysNoAddonHasIt(newLook: Boolean) = withRowHosts {
        compose.setContent { NewLook { Page(newLook, "zzz", UiState.Success(emptyList()), listOf("aurora"), Taps()) } }
        compose.onNodeWithText("No addon has “zzz”.", substring = true).assertExists()
        compose.onAllNodesWithText("aurora").assertCountEquals(0)
    }

    @Test fun newLookResults() = resultsShowTheTierAndPlayFromTheAddonList(newLook = true)
    @Test fun classicResults() = resultsShowTheTierAndPlayFromTheAddonList(newLook = false)
    @Test fun newLookRecents() = recentWordsShowUntilSomethingIsSubmitted(newLook = true)
    @Test fun classicRecents() = recentWordsShowUntilSomethingIsSubmitted(newLook = false)
    @Test fun newLookNoMatch() = anEmptyAnswerSaysNoAddonHasIt(newLook = true)
    @Test fun classicNoMatch() = anEmptyAnswerSaysNoAddonHasIt(newLook = false)
}
