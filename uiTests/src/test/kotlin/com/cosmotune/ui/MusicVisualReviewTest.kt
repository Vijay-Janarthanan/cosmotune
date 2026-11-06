package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.music.data.model.*
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.home.OrbitHome
import com.cosmotune.ui.home.HomeQuickLink
import com.cosmotune.ui.library.CosmoLibrary
import com.cosmotune.ui.listening.CosmoReleasesPage
import com.cosmotune.ui.pages.CosmoArtistPage
import com.cosmotune.ui.pages.CosmoReleasePage
import com.cosmotune.ui.theme.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** The Music screens with original fixtures, drawn at phone width in the dark, AMOLED and light palettes, written under build/music-review. */
@OptIn(ExperimentalMaterial3Api::class)
class MusicVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {
            it.color = java.awt.Color(r, g, b); it.fillRect(0, 0, 320, 320)
            it.color = java.awt.Color(minOf(r + 60, 255), minOf(g + 60, 255), minOf(b + 60, 255)); it.fillOval(60, 60, 200, 200)
            it.dispose()
        }
        return temporary.newFile("$name.png").also { ImageIO.write(image, "png", it) }.path
    }

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
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi) }
    }

    private fun page(palette: CosmoPaletteState, dark: Boolean, content: @Composable () -> Unit) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(390.dp, 844.dp).testTag("music:review").background(Color(colors.palette.backgroundBottom))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File("../build/music-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("music:review").captureToImage().toAwtImage(), "png", file)
    }

    private fun items(prefix: String, count: Int, art: List<String>, songs: Boolean = false) = List(count) { i ->
        ShelfItem("$prefix ${i + 1} with a title long enough to wrap", "Fixture artist", art[i % art.size], if (songs) "v$prefix$i" else null, if (songs) null else "MPRE$prefix$i")
    }

    private fun tracks(art: String) = List(8) { i -> Song("v$i", "Track number ${i + 1}", "Fixture artist", art, durationText = "3:0$i") }

    @Test fun musicScreensAtPhoneWidth() = hosts {
        val art = listOf(cover("a", 142, 68, 58), cover("b", 44, 96, 120), cover("c", 90, 120, 60), cover("d", 110, 70, 130))
        for ((name, palette, dark) in listOf(
            Triple("dark", OneTestPalette.dark(), true),
        )) {
            page(palette, dark) {
                OrbitHome(
                    state = UiState.Success(listOf(
                        HomeShelf("Recents", items("Recent", 6, art)),
                        HomeShelf("Your mixes", items("Mix", 6, art), subtitle = "Made for you"),
                        HomeShelf("Quick picks", items("Pick", 12, art, songs = true)),
                    )),
                    listState = rememberLazyListState(),
                    onItemClick = { _, _ -> }, onRetry = {}, refreshing = false, onRefresh = {}, pullState = rememberPullToRefreshState(),
                    contentPadding = PaddingValues(top = 56.dp),
                    quickLinks = listOf(HomeQuickLink("Liked songs", CosmoIcons.Heart) {}, HomeQuickLink("Downloads", CosmoIcons.Download) {}),
                )
            }
            capture("home-$name")
        }
    }

    @Test fun libraryAtPhoneWidth() = hosts {
        val art = listOf(cover("a", 142, 68, 58), cover("b", 44, 96, 120))
        page(OneTestPalette.dark(), true) {
            CosmoLibrary(
                signedIn = true,
                state = UiState.Success(LibraryPage(emptyList(), emptyList(), listOf(HomeShelf("Playlists", items("Playlist", 8, art))))),
                listState = rememberLazyListState(), onShelfItemClick = {}, onShelfItemLongPress = {}, onNewPlaylist = {}, onShowAll = {},
                replay = {}, onSignIn = {}, onRetry = {}, refreshing = false, onRefresh = {}, pullState = rememberPullToRefreshState(),
                contentPadding = PaddingValues(top = 56.dp), links = emptyList(), deviceItems = emptyList(), sourceShelves = emptyList(),
            )
        }
        capture("library-dark")
    }

    @Test fun albumAndArtistAtPhoneWidth() = hosts {
        val art = cover("a", 142, 68, 58)
        val rows = tracks(art)
        val visible = mutableStateOf(true)
        page(OneTestPalette.dark(), true) {
            if (visible.value) CosmoReleasePage(
                page = DetailPage("MPREx", "A fixture album", "Album • Fixture artist • 2026", art, songs = UiState.Success(rows), type = BrowseType.ALBUM),
                songs = rows, trackNumbers = emptyMap(), currentSong = null, isPlaying = false, listState = rememberLazyListState(),
                contentPadding = PaddingValues(top = 56.dp), onSongClick = { _, _ -> }, onSongLongPress = {}, onSongSwipe = {}, onShuffle = {},
                onMore = {}, onArtistClick = { _, _ -> }, onAddSuggested = {}, onToggleLibrary = null,
            )
        }
        capture("album-dark")
        visible.value = false
    }

    @Test fun artistAtPhoneWidth() = hosts {
        val art = cover("a", 44, 96, 120)
        val rows = tracks(art)
        page(OneTestPalette.dark(), true) {
            CosmoArtistPage(
                page = DetailPage(
                    "UCfixture", "A fixture artist", "Artist", art, songs = UiState.Success(rows), type = BrowseType.ARTIST,
                    sections = listOf(HomeShelf("Albums", items("Album", 6, listOf(art)))), description = "An original fixture, written for this review, long enough to be cut after three lines of text on a phone so that the read more button shows under it. ".repeat(3),
                ),
                heroUrl = art, currentSong = null, isPlaying = false, listState = rememberLazyListState(), contentPadding = PaddingValues(top = 56.dp),
                activeShelf = null, onActiveShelfChange = {}, onSongClick = { _, _ -> }, onSongLongPress = {}, onSongSwipe = {}, onShuffle = {},
                onSectionItemClick = {}, onSectionItemLongPress = null, onToggleSubscription = null, releaseLibrary = emptyMap(), onLoadReleaseLibrary = null, onToggleReleaseLibrary = null,
            )
        }
        capture("artist-dark")
    }

    @Test fun releasesAtPhoneWidth() = hosts {
        val art = cover("a", 44, 96, 120)
        val file = temporary.newFolder().resolve("listening.json")
        val store = ListeningStore(file)
        store.watchArtist("UCfixture000000", "Fixture artist")
        val now = System.currentTimeMillis()
        store.update {
            it.copy(inbox = listOf(
                ReleaseNotice("MPREa", "UCfixture000000", "Fixture artist", "A new album", art, now, single = false),
                ReleaseNotice("MPREb", "UCfixture000000", "Fixture artist", "A new single", art, now - 8 * 24 * 3_600_000L, read = true, single = true),
                ReleaseNotice("MPREc", "UCfixture000000", "Fixture artist", "An older album", art, now - 40 * 24 * 3_600_000L, read = true, single = false),
            ))
        }
        page(OneTestPalette.dark(), true) {
            CosmoReleasesPage(file = file, listState = rememberLazyListState(), contentPadding = PaddingValues(top = 56.dp), onOpenRelease = { _, _ -> }, onManage = {}, lookup = { emptyList() })
        }
        compose.waitUntil(5_000) { compose.onAllNodesWithText("A new album").fetchSemanticsNodes().isNotEmpty() }
        capture("releases-dark")
    }
}

/** The chrome's One palette, as the theme installs it. */
private object OneTestPalette {
    fun dark() = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true)
}
