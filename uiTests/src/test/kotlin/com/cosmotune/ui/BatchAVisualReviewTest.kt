package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.music.data.YtMusicRepository
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.model.UiState
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.ui.account.AccountChoiceRow
import com.cosmotune.ui.account.AccountSheetContent
import com.cosmotune.ui.account.AccountSheetModel
import com.cosmotune.ui.account.AccountStatus
import com.cosmotune.ui.components.CosmoSwitch
import com.cosmotune.ui.library.CosmoDeviceMusic
import com.cosmotune.ui.library.CosmoHistory
import com.cosmotune.ui.listening.CosmoReleasesPage
import com.cosmotune.ui.settings.CosmoSettingsGroup
import com.cosmotune.ui.settings.CosmoSettingsRow
import com.cosmotune.ui.settings.CosmoSettingsSegmented
import com.cosmotune.ui.settings.LocalSettingsTileColor
import com.cosmotune.ui.settings.SettingsAccountCard
import com.cosmotune.ui.settings.SettingsCategory
import com.cosmotune.ui.settings.SettingsCategoryEntry
import com.cosmotune.ui.settings.SettingsCategoryRail
import com.cosmotune.ui.settings.SettingsHub
import com.cosmotune.ui.settings.SettingsHubTitle
import com.cosmotune.ui.settings.SettingsPageHeader
import com.cosmotune.ui.settings.SettingsSearchPill
import com.cosmotune.ui.settings.TmdbAttribution
import com.cosmotune.ui.settings.icon
import com.cosmotune.ui.settings.settingsCategoryTint
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
 * Batch A's screens in Orbit (Canvas repo, design/cosmotune/batch-a/board.html, sections 1 and 2): History, Downloads, Local Music,
 * Releases, the settings home with its account card, a settings page, the desktop's home beside a page, and the account sheet, drawn
 * at phone width and at the board's page width in the dark and the light palettes, written under build/batch-a-review for a look.
 */
class BatchAVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private class Size(val name: String, val width: Int, val height: Int)
    private val phone = Size("phone", 390, 844)
    private val wide = Size("wide", 1004, 700)

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

    private fun page(dark: Boolean, size: Size, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(size.width.dp, size.height.dp).testTag("batch-a:review").background(Color(colors.palette.backgroundBottom))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/batch-a-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("batch-a:review").captureToImage().toAwtImage(), "png", file)
    }

    private fun each(block: (dark: Boolean, size: Size, tag: String) -> Unit) {
        for (dark in listOf(true, false)) for (size in listOf(phone, wide)) block(dark, size, "${size.name}-${if (dark) "dark" else "light"}")
    }

    private val names = listOf("Golden Hour" to "Mira and the Coast", "Hukum Reloaded" to "Anirudh Ravichander", "Low Tide Letters" to "Harbour Lights",
        "Lantern Season" to "Mira and the Coast", "Night Signals" to "Nila Vale", "Blue Hour" to "Coastline", "Paper Moons" to "Harbour Lights",
        "Slow Orbit" to "Nila Vale", "Kites Over Adyar" to "Anirudh Ravichander", "Monsoon Static" to "Coastline")

    private fun songs(art: List<String>, files: Boolean = false) = names.mapIndexed { i, (title, artist) ->
        val path = if (files) temporary.newFile("song$i.m4a").also { it.writeBytes(ByteArray(4_000_000 + i * 350_000)) }.path else null
        Song("v$i", title, artist, art[i % art.size], durationText = "${3 + i % 2}:${(i * 7 % 60).toString().padStart(2, '0')}",
            albumName = if (i % 3 == 0) "Night Signals" else "Coastal Hours", localPath = path)
    }

    @Test fun historyDownloadsLocalAndReleases() = hosts {
        val art = listOf(cover("a", 168, 88, 52), cover("b", 40, 110, 118), cover("c", 150, 52, 86), cover("d", 120, 96, 30), cover("e", 90, 70, 150))
        val history = songs(art)
        // The day labels the history read files each song under.
        YtMusicRepository::class.java.getDeclaredField("historyDaysState").apply { isAccessible = true }.let { field ->
            @Suppress("UNCHECKED_CAST")
            (field.get(YtMusicRepository) as MutableStateFlow<Map<String, String>>).value =
                history.mapIndexed { i, s -> s.videoId to when { i < 3 -> "Today"; i < 6 -> "Yesterday"; else -> "This week" } }.toMap()
        }
        each { dark, size, tag ->
            page(dark, size) {
                CosmoHistory(
                    state = UiState.Success(history), listState = rememberLazyListState(), onSongClick = { _, _ -> }, onSongLongPress = {},
                    onSongSwipe = {}, onRetry = {}, contentPadding = PaddingValues(top = 24.dp), currentSong = history[1], isPlaying = true,
                )
            }
            capture("history-$tag")
        }
        val downloaded = songs(art, files = true)
        each { dark, size, tag ->
            page(dark, size) {
                CosmoDeviceMusic(
                    title = "Downloads", songs = downloaded, onSongClick = { _, _ -> }, onSongLongPress = {}, contentPadding = PaddingValues(top = 24.dp),
                    isDownloads = true, onShuffle = {}, sortLabels = listOf("Title A–Z", "Title Z–A", "Date added", "Date modified"),
                    onDeleteSelected = {},
                )
            }
            capture("downloads-$tag")
        }
        page(true, phone) {
            CosmoDeviceMusic(title = "Local Music", songs = emptyList(), onSongClick = { _, _ -> }, onSongLongPress = {}, contentPadding = PaddingValues(top = 24.dp))
        }
        capture("local-empty-phone-dark")
        val file = File(temporary.newFolder("listening"), "listening.json")
        val now = System.currentTimeMillis()
        ListeningStore(file).apply {
            watchArtist("UCfixture000000", "Nila Vale")
            update {
                it.copy(inbox = listOf(
                    ReleaseNotice("MPREa", "UCfixture000000", "Nila Vale", "Night Signals", art[4], now, single = false),
                    ReleaseNotice("MPREb", "UCfixture000000", "Coastline", "Blue Hour", art[1], now - 3_600_000L, read = true, single = true),
                    ReleaseNotice("MPREc", "UCfixture000000", "Harbour Lights", "Paper Moons", art[2], now - 8 * 24 * 3_600_000L, read = true, single = false),
                    ReleaseNotice("MPREd", "UCfixture000000", "Mira and the Coast", "Lantern Season", art[0], now - 40 * 24 * 3_600_000L, read = true, single = false),
                ))
            }
        }
        each { dark, size, tag ->
            page(dark, size) {
                CosmoReleasesPage(file = file, listState = rememberLazyListState(), contentPadding = PaddingValues(top = 24.dp), onOpenRelease = { _, _ -> }, onManage = {}, lookup = { emptyList() })
            }
            compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Night Signals")).fetchSemanticsNodes().isNotEmpty() }
            capture("releases-$tag")
        }
    }

    private val entries = listOf(
        SettingsCategory.ACCOUNT to ("Account & sync" to "vijay@example.com"),
        SettingsCategory.APPEARANCE to ("Look and colours" to "Theme, cover colours, motion"),
        SettingsCategory.PLAYBACK to ("Playback" to "Quality, crossfade, normalise"),
        SettingsCategory.LYRICS to ("Lyrics" to "Sources, timing, style"),
        SettingsCategory.SOURCES to ("Sources and add-ons" to "Audio plugins, servers"),
        SettingsCategory.DOWNLOADS to ("Downloads and storage" to "Where, how much"),
        SettingsCategory.INTEGRATIONS to ("Rooms and services" to "Microphone, who can join"),
        SettingsCategory.PRIVACY to ("Privacy" to "History, scrobbling"),
        SettingsCategory.ABOUT to ("About" to "Version 1.0.16, credits"),
    ).map { (category, text) -> SettingsCategoryEntry(category, text.first, text.second, category.icon) }

    @Composable
    private fun AccountCard(compact: Boolean, art: String) {
        SettingsAccountCard("Account · Google", "Vijay", "vijay@example.com · Cloud sync on · synced 2 min ago", art, onClick = {}, compact = compact)
    }

    @Composable
    private fun LookPage() {
        var follow by remember { mutableStateOf(true) }
        var animated by remember { mutableStateOf(true) }
        var cellular by remember { mutableStateOf(false) }
        var theme by remember { mutableStateOf(1) }
        CompositionLocalProvider(LocalSettingsTileColor provides settingsCategoryTint(SettingsCategory.APPEARANCE)) {
            CosmoSettingsGroup(header = "Theme", topSpacing = 0.dp) {
                CosmoSettingsRow(title = "Theme", subtitle = "Light, dark, black or the system's", icon = Icons.Rounded.Palette)
                CosmoSettingsSegmented(listOf("Light", "Dark", "Black", "System"), theme, { theme = it }, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 14.dp))
            }
            CosmoSettingsGroup(header = "Covers") {
                CosmoSettingsRow(title = "Colours follow the cover", subtitle = "The whole app wears the playing song's colours", onClick = { follow = !follow }, trailing = { CosmoSwitch(follow, { follow = it }) })
                CosmoSettingsRow(title = "Animated covers", subtitle = "Moving covers and artist portraits", onClick = { animated = !animated }, trailing = { CosmoSwitch(animated, { animated = it }) })
                CosmoSettingsRow(title = "On mobile data too", subtitle = "Off saves data", onClick = { cellular = !cellular }, trailing = { CosmoSwitch(cellular, { cellular = it }) })
                CosmoSettingsRow(title = "Open the equaliser", subtitle = "Tone and balance", value = "Flat", onClick = {})
            }
        }
    }

    @Test fun settingsHomePageAndAccount() = hosts {
        val art = cover("me", 108, 75, 158)
        each { dark, size, tag ->
            page(dark, size) {
                if (size === phone) {
                    Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
                        SettingsHubTitle("Settings")
                        SettingsSearchPill("", {}, "Search settings", Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
                        SettingsHub(entries, onOpen = {}, accountCard = { AccountCard(true, art) })
                    }
                } else {
                    Column(Modifier.padding(top = 20.dp)) {
                        SettingsHubTitle("Settings")
                        Row {
                            SettingsCategoryRail(entries, SettingsCategory.APPEARANCE, {}, Modifier.width(520.dp).padding(start = 16.dp), columns = 2, accountCard = { AccountCard(false, art) })
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())) {
                                SettingsPageHeader("Look and colours", onBack = null, subtitle = "Theme, cover colours, motion")
                                LookPage()
                            }
                        }
                    }
                }
            }
            capture("settings-$tag")
        }
        for (dark in listOf(true, false)) {
            page(dark, phone) {
                Column(Modifier.verticalScroll(rememberScrollState()).padding(top = 24.dp)) {
                    SettingsPageHeader("Look and colours", onBack = {}, subtitle = "Theme, cover colours, motion")
                    LookPage()
                    Spacer(Modifier.size(24.dp))
                    TmdbAttribution()
                }
            }
            capture("settings-page-phone-${if (dark) "dark" else "light"}")
            page(dark, phone) {
                Column(Modifier.fillMaxSize().padding(top = 40.dp)) {
                    AccountSheetContent(
                        AccountSheetModel(AccountStatus.SIGNED_IN, "Vijay", "@vijay", art, listOf(
                            AccountChoiceRow("a", "Vijay", "@vijay", art, "vijay@example.com"),
                            AccountChoiceRow("b", "Vijay Music", null, null, "vijay@example.com", isBrand = true),
                        ), "a"),
                        {}, {}, {}, {}, onAddAccount = {},
                    )
                }
            }
            capture("account-sheet-phone-${if (dark) "dark" else "light"}")
        }
    }
}
