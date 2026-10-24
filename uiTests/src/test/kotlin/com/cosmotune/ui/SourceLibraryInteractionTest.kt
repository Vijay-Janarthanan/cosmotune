package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.*
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.providers.*
import com.cosmotune.ui.library.CosmoLibrary
import com.cosmotune.ui.providers.SourceLibraryButton
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalMaterial3Api::class, ExperimentalTestApi::class)
class SourceLibraryInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private fun library(): SourceLibraryStore = runBlocking {
        ProviderHub.configure(temporary.newFolder(), force = true, cache = temporary.newFolder())
        withTimeout(10_000) { while (ProviderHub.state.value.loading) delay(20) }
        requireNotNull(ProviderHub.state.value.library)
    }
    private val song = Song("src:sflx-fixture::recording", "A source song", "An artist", null)
    private fun page(kind: String, id: String, title: String) = SourceCataloguePage(ProviderCollection(ProviderBrowseKey("fixture", kind, id), title), listOf(song))

    @Test fun savingAndFollowingUsePersistentLocalStateAndCanBeUndone() {
        val library = library()
        val album = page("album", "album", "A source album")
        val artist = page("artist", "artist", "An artist")
        compose.setContent { MaterialTheme { Column { SourceLibraryButton(album); SourceLibraryButton(artist) } } }
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10_000) { library.state.value.collections.size == 1 }
        compose.onNodeWithText("Saved", substring = false).assertIsDisplayed()
        compose.onNodeWithText("Follow", substring = false).performClick()
        compose.waitUntil(10_000) { library.state.value.followed.size == 1 }
        compose.onNodeWithText("Following", substring = false).performClick()
        compose.waitUntil(10_000) { library.state.value.followed.isEmpty() }
        compose.onNodeWithText("Saved", substring = false).performClick()
        compose.waitUntil(10_000) { library.state.value.collections.isEmpty() }
    }

    @Test fun signedOutLibraryKeepsSourceFavouritesAlbumsAndArtistsReachable() {
        val library = library()
        runBlocking {
            library.rate(song, LikeStatus.LIKE)
            library.save(page("album", "album", "A source album"), true)
            library.follow(page("artist", "artist", "A source artist"), true)
        }
        val installed = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installed.get(null)
        AppUi.install(object : AppUiHost {
            override val swipeToPlayNext = MutableStateFlow(false)
            override val homeRecentsViewType = MutableStateFlow(LibraryViewType.entries.first())
            override val pinnedPlaylists = MutableStateFlow(emptyList<String>())
            override val librarySort = MutableStateFlow(LibrarySort.entries.first())
            override fun setHomeRecentsViewType(value: LibraryViewType) {}
            @Composable override fun downloadedIds() = emptySet<String>()
        })
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        val typography = CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
            .newInstance(900f, FontFamily.Default, FontFamily.Default)
        var opened: String? = null
        try {
            compose.setContent { MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides typography) {
                    Box(Modifier.width(900.dp).fillMaxHeight()) {
                        CosmoLibrary(signedIn = false, state = UiState.Success(LibraryPage(emptyList(), emptyList(), emptyList())),
                            listState = rememberLazyListState(), onShelfItemClick = { opened = it.browseId }, onShelfItemLongPress = {},
                            onNewPlaylist = {}, onShowAll = {}, replay = {}, onSignIn = {}, onRetry = {}, refreshing = false,
                            onRefresh = {}, pullState = rememberPullToRefreshState(), contentPadding = PaddingValues(0.dp), links = emptyList(), deviceItems = emptyList())
                    }
                }
            } }
            compose.onNodeWithText("Liked source songs", substring = false).performClick()
            assertEquals(SourceLibraryStore.LIKED_BROWSE_ID, opened)
            compose.onNodeWithText("Albums", substring = false).performClick()
            compose.onNodeWithText("A source album", substring = false).performClick()
            assertEquals(ProviderBrowseKey("fixture", "album", "album").encode(), opened)
            compose.onNodeWithText("Artists", substring = false).performClick()
            compose.onNodeWithText("A source artist", substring = false).performClick()
            assertEquals(ProviderBrowseKey("fixture", "artist", "artist").encode(), opened)
        } finally { installed.set(null, previous) }
    }
}
