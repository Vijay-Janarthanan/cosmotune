package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.HomeShelf
import com.cosmotune.music.data.model.ShelfItem
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.account.AccountExpiredBanner
import com.cosmotune.ui.blocks.CosmoListRow
import com.cosmotune.ui.blocks.CosmoOverline
import com.cosmotune.ui.blocks.CosmoPageHeader
import com.cosmotune.ui.blocks.CosmoRowLeading
import com.cosmotune.ui.blocks.CosmoSectionHeader
import com.cosmotune.ui.blocks.CosmoShelf
import com.cosmotune.ui.components.CosmoButton
import com.cosmotune.ui.components.CosmoButtonKind
import com.cosmotune.ui.components.CosmoChip
import com.cosmotune.ui.components.CosmoIconButton
import com.cosmotune.ui.components.CosmoIconButtonStyle
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.home.HomeDiscoveryControls
import com.cosmotune.ui.home.PersonalPlaylistDialog
import com.cosmotune.ui.kit.KitArt
import com.cosmotune.ui.kit.KitButton
import com.cosmotune.ui.kit.KitButtonKind
import com.cosmotune.ui.kit.KitChip
import com.cosmotune.ui.kit.KitMediaRow
import com.cosmotune.ui.kit.KitNavRail
import com.cosmotune.ui.kit.KitSectionHeader
import com.cosmotune.ui.kit.KitSettingsRow
import com.cosmotune.ui.kit.KitSheet
import com.cosmotune.ui.kit.KitSkeletonRows
import com.cosmotune.ui.kit.KitTextButton
import com.cosmotune.ui.kit.KitTileTint
import com.cosmotune.ui.library.CosmoLibraryGrid
import com.cosmotune.ui.pages.CosmoShelfGridPage
import com.cosmotune.ui.player.MiniPlayerPanel
import com.cosmotune.ui.search.CosmoMoodPage
import com.cosmotune.ui.search.SearchInChips
import com.cosmotune.ui.search.SearchInChoice
import com.cosmotune.ui.search.SearchInState
import com.cosmotune.ui.search.SearchInStatus
import com.cosmotune.ui.settings.CosmoLookPreviewDialog
import com.cosmotune.ui.shell.CosmoTab
import com.cosmotune.ui.states.CosmoEmptyState
import com.cosmotune.ui.states.CosmoSignInBanner
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Not an assertion: the pre-Orbit base pieces (buttons, icon buttons, chips, switches, rows, headings, cards, shelves, the rail, the
 * skeleton and the sheet) as they draw now, in Orbit, and the pages converted with them (a mood page, the library and shelf grids,
 * Search in, Home's discovery row, Make my playlist, the look preview, the desktop's mini player, the empty state and the banners),
 * at phone width in the dark and the light palettes, written under build/old-screens/kitbase for a person to look at.
 */
class KitbaseVisualReviewTest {
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
                "PlayerBackHandler", "BackHandler" -> Unit
                else -> error("Unexpected ${method.name}")
            }
        } as PlayerHost)
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi) }
    }

    private fun page(dark: Boolean, width: Int = 390, height: Int = 844, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(width.dp, height.dp).testTag("kitbase:review").background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    private fun capture(name: String, popup: Boolean = false) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/old-screens/kitbase/$name.png").apply { parentFile.mkdirs() }
        val node = if (popup) compose.onAllNodes(isRoot())[1] else compose.onNodeWithTag("kitbase:review")
        ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
    }

    private fun each(block: (dark: Boolean, tag: String) -> Unit) {
        for (dark in listOf(true, false)) block(dark, if (dark) "dark" else "light")
    }

    private val names = listOf("Golden Hour" to "Mira and the Coast", "Hukum Reloaded" to "Anirudh Ravichander", "Low Tide Letters" to "Harbour Lights",
        "Lantern Season" to "Mira and the Coast", "Night Signals" to "Nila Vale", "Blue Hour" to "Coastline")

    private fun art() = listOf(cover("a", 168, 88, 52), cover("b", 40, 110, 118), cover("c", 150, 52, 86), cover("d", 120, 96, 30), cover("e", 90, 70, 150))

    private fun items(art: List<String>, artists: Boolean = false) = names.mapIndexed { i, (title, artist) ->
        if (artists) ShelfItem(artist, "Artist", art[i % art.size], null, "UCartist$i")
        else ShelfItem(title, "Album · $artist", art[i % art.size], null, "MPREb$i")
    }

    @Test fun basePieces() = hosts {
        val art = art()
        each { dark, tag ->
            page(dark) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    CosmoPageHeader("Base pieces", overline = "Kitbase", subtitle = "The old pieces, drawn by the Orbit kit.") {
                        CosmoIconButton(CosmoIcons.Search, "Search", {}, style = CosmoIconButtonStyle.TONAL, size = 40.dp)
                    }
                    CosmoOverline("Buttons")
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoButton("Play all", {}, icon = CosmoIcons.Play)
                        CosmoButton("Shuffle", {}, kind = CosmoButtonKind.TONAL)
                        CosmoButton("Not now", {}, kind = CosmoButtonKind.TEXT)
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        KitButton("Retry", {}, kind = KitButtonKind.PRIMARY, dense = true)
                        KitButton("Tonal", {}, kind = KitButtonKind.TONAL, dense = true)
                        KitButton("Quiet", {}, kind = KitButtonKind.QUIET, dense = true, enabled = false)
                        KitTextButton("See all", {})
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoIconButton(CosmoIcons.Shuffle, "Shuffle", {}, on = true)
                        CosmoIconButton(CosmoIcons.HeartFill, "Liked", {}, on = true, accentWhenOn = true, size = 40.dp)
                        CosmoIconButton(CosmoIcons.More, "More", {}, style = CosmoIconButtonStyle.GLASS, size = 40.dp)
                        CosmoIconButton(CosmoIcons.Queue, "Queue", {}, enabled = false)
                        CosmoSwitch(true, {})
                        CosmoSwitch(false, {})
                    }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        CosmoChip("Chill", true, {})
                        CosmoChip("Focus", false, {})
                        KitChip("Today", true, {})
                        KitChip("Videos", false, {}, icon = CosmoIcons.Video)
                    }
                    CosmoSectionHeader("Rows", subtitle = "The list row and the media row", onShowAll = {})
                    CosmoListRow("Golden Hour", {}, subtitle = "Mira and the Coast", leading = CosmoRowLeading.Cover(art[0]), isCurrent = true, isPlaying = true)
                    CosmoListRow("Night Signals", {}, subtitle = "Nila Vale", why = "Because you played Blue Hour", leading = CosmoRowLeading.Cover(art[1], circle = true))
                    CosmoListRow("Track three", {}, subtitle = "3:42", leading = CosmoRowLeading.Number(3))
                    CosmoListRow("Local files", {}, leading = CosmoRowLeading.Icon(CosmoIcons.Video), trailing = { CosmoIconButton(CosmoIcons.Close, "Remove", {}, size = 36.dp) })
                    KitMediaRow("Low Tide Letters", "Harbour Lights", {}, artUrl = art[2], trailingText = "4:07", onMore = {})
                    KitMediaRow("Episode 12: Monsoon", "Harbour Radio", {}, artUrl = art[3], art = KitArt.WIDE, trailingText = "52 min")
                    KitSettingsRow("Appearance", icon = CosmoIcons.Settings, summary = "Dark · follows the cover", tile = KitTileTint.PURPLE, onClick = {}, selected = true)
                    KitSettingsRow("Notifications", icon = CosmoIcons.Bell, summary = "On", trailing = { CosmoSwitch(true, {}) })
                }
            }
            capture("base-pieces-$tag")
        }
        each { dark, tag ->
            page(dark) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
                    CosmoShelf("Albums for you", items(art), {}, subtitle = "Made from what you play", onShowAll = {})
                    CosmoShelf("Artists", items(art, artists = true), {}, circles = true)
                    KitSectionHeader("Loading", overline = "Search in · Bandcamp", onSeeAll = {}, seeAllLabel = "Verify to search")
                    KitSkeletonRows(count = 3)
                    Row(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                        KitNavRail(
                            listOf(CosmoTab("Home", CosmoIcons.Home, CosmoIcons.HomeFill), CosmoTab("Search", CosmoIcons.Search, CosmoIcons.SearchFill), CosmoTab("Library", CosmoIcons.Library, CosmoIcons.LibraryFill)),
                            0, {}, Modifier.fillMaxHeight(),
                        )
                    }
                }
            }
            capture("shelves-rail-skeleton-$tag")
        }
    }

    @Test fun sheet() = hosts {
        each { dark, tag ->
            page(dark) {
                KitSheet(onDismiss = {}, title = "Catalogue language") {
                    KitSettingsRow("English", icon = CosmoIcons.Check, onClick = {})
                    KitSettingsRow("Tamil", onClick = {})
                    KitSettingsRow("Hindi", onClick = {})
                }
            }
            capture("kit-sheet-$tag", popup = true)
        }
    }

    @Test fun pages() = hosts {
        val art = art()
        val shelves = listOf(HomeShelf("Chill classics", items(art), subtitle = "Slow evenings"), HomeShelf("Artists to know", items(art, artists = true)), HomeShelf("Late night", items(art.reversed())))
        each { dark, tag ->
            page(dark) {
                CosmoMoodPage("Chill", UiState.Success(shelves), rememberLazyListState(), {}, {}, PaddingValues(top = 12.dp))
            }
            capture("mood-page-$tag")
            page(dark) {
                CosmoLibraryGrid(HomeShelf("Playlists", items(art)), rememberLazyGridState(), {}, {}, PaddingValues(top = 24.dp), onNewPlaylist = {})
            }
            capture("library-grid-$tag")
            page(dark) {
                CosmoShelfGridPage(HomeShelf("Fans also like", items(art, artists = true)), {}, null, PaddingValues(top = 24.dp))
            }
            capture("shelf-grid-artists-$tag")
            DiscoveryPreferences.configure(temporary.newFile("preferences-$tag.json").apply { delete() })
            page(dark) {
                Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
                    SearchInChips(
                        SearchInState(
                            listOf(SearchInChoice("all", "All", SearchInStatus.SEARCHING), SearchInChoice("yt", "YouTube Music", SearchInStatus.READY),
                                SearchInChoice("sflx-bandcamp", "Bandcamp", SearchInStatus.NEEDS_VERIFICATION), SearchInChoice("sub", "Navidrome", SearchInStatus.ERROR)),
                            selected = "all", onSelect = {},
                        ),
                        cosmo = true,
                    )
                    KitSectionHeader("Bandcamp", overline = "Needs verification", seeAllLabel = "Verify to search", onSeeAll = {})
                    KitSectionHeader("Navidrome", overline = "Searching…")
                    KitSkeletonRows(count = 2)
                    Box(Modifier.padding(top = 24.dp)) { HomeDiscoveryControls({}, onCreatePlaylist = {}) }
                    Box(Modifier.padding(top = 16.dp)) { CosmoSignInBanner({}) }
                    AccountExpiredBanner({}, onDismiss = {})
                    CosmoEmptyState("Nothing saved here yet. Songs you like show up here.", title = "No songs", icon = CosmoIcons.Heart, actionLabel = "Browse", onAction = {})
                }
            }
            capture("search-in-home-states-$tag")
            page(dark, width = 380, height = 124) {
                MiniPlayerPanel("Golden Hour", "Mira and the Coast", true, 0.4f, artwork = { m -> Box(m.background(Color(0xFFA85834))) },
                    onPlayPause = {}, onNext = {}, onPrevious = {}, onOpen = {}, dark = dark)
            }
            capture("mini-player-$tag")
        }
    }

    @Test fun dialogs() = hosts {
        val songs = names.mapIndexed { i, (title, artist) -> Song("v$i", title, artist, null, durationText = "3:4$i") }
        each { dark, tag ->
            page(dark) { PersonalPlaylistDialog(songs, songs.first(), {}) { _, _ -> } }
            capture("make-my-playlist-$tag", popup = true)
        }
        // The preview opens in the system's theme and carries its own Dark and Light choice.
        page(true) { CosmoLookPreviewDialog({}) }
        capture("look-preview", popup = true)
    }
}
