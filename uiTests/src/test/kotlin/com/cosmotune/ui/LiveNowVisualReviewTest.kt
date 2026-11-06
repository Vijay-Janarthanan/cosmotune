package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.live.LiveNowFloatingPanel
import com.cosmotune.ui.live.LiveNowHost
import com.cosmotune.ui.live.LiveNowMemoryStore
import com.cosmotune.ui.live.LiveNowPanel
import com.cosmotune.ui.live.LiveNowSettings
import com.cosmotune.ui.live.LiveNowSettingsSection
import com.cosmotune.ui.live.LiveNowTopBarPill
import com.cosmotune.ui.live.LocalLiveNow
import com.cosmotune.ui.live.LocalLiveNowInPage
import com.cosmotune.ui.orbit.orbitFonts
import com.cosmotune.ui.orbit.orbitLook
import com.cosmotune.ui.sheets.CosmoSheetSurface
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import com.cosmotune.ui.watch.ProvideWatch
import com.cosmotune.ui.watch.WatchApp
import com.cosmotune.ui.watch.WatchNav
import com.cosmotune.ui.watch.WatchTab
import com.cosmotune.watch.ChannelFilter
import com.cosmotune.watch.FakeWatchRepository
import com.cosmotune.watch.GuideRow
import com.cosmotune.watch.GuideWindow
import com.cosmotune.watch.WatchRepository
import com.cosmotune.watch.model.Channel
import com.cosmotune.watch.model.EpgProgramme
import com.cosmotune.watch.model.LiveEvent
import com.cosmotune.watch.model.WatchKey
import com.cosmotune.watch.plugin.BuiltInPlugins
import java.io.File
import java.util.TimeZone
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * "Live now" (Canvas repo, design/cosmotune/live-strip/board.html) on the fake repository's events: the pill in Watch's and Podio's
 * phone headers and in a desktop top bar over Watch, the desktop's panel, the phone's sheet, Settings > Live now, Live TV with the
 * DD and official YouTube live channels and the Plugins page with "Find plugins" first, in the dark and the light palettes, written
 * under build/live-review for a look.
 */
class LiveNowVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val evening = 1_762_461_600_000L

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousPlayer = playerField.get(null); val previousUi = uiField.get(null)
        val zone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("Asia/Kolkata"))
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
        try { block() } finally {
            playerField.set(null, previousPlayer); uiField.set(null, previousUi); TimeZone.setDefault(zone); WatchNav.go(WatchTab.HOME); ModeNav.podioTab.value = "Radio"
        }
    }

    private class Size(val name: String, val width: Int, val height: Int)
    private val phone = Size("phone", 390, 844)
    private val wide = Size("wide", 1180, 780)

    /** The fake repository with DD's channels and an official YouTube live news channel in Live TV, as the plugins list them. */
    private class LiveTvRepository(private val fake: FakeWatchRepository = FakeWatchRepository()) : WatchRepository by fake {
        val dd = Channel(WatchKey.channel(BuiltInPlugins.PRASAR_BHARATI, "DDTamil.in"), BuiltInPlugins.PRASAR_BHARATI, "DD Tamil", categories = listOf("Entertainment"),
            countries = listOf("in"), languages = listOf("tam"), tvgId = "DDTamil.in")
        val sports = Channel(WatchKey.channel(BuiltInPlugins.PRASAR_BHARATI, "DDSports.in"), BuiltInPlugins.PRASAR_BHARATI, "DD Sports", categories = listOf("Sports"),
            countries = listOf("in"), languages = listOf("hin", "eng"), tvgId = "DDSports.in")
        val sun = Channel(WatchKey.channel(BuiltInPlugins.YOUTUBE_LIVE, "sunnews"), BuiltInPlugins.YOUTUBE_LIVE, "Sun News", categories = listOf("News"),
            countries = listOf("in"), languages = listOf("tam"))
        override suspend fun channels(filter: ChannelFilter): List<Channel> = listOf(dd, sun, sports) + fake.channels(filter)
        override suspend fun guide(channels: List<Channel>, fromMs: Long, toMs: Long): GuideWindow {
            val base = fake.guide(channels.filter { it.pluginId != BuiltInPlugins.PRASAR_BHARATI && it.pluginId != BuiltInPlugins.YOUTUBE_LIVE }, fromMs, toMs)
            val hour = 3_600_000L
            val start = fromMs / (30 * 60_000) * (30 * 60_000)
            val ours = channels.filter { it.pluginId == BuiltInPlugins.PRASAR_BHARATI || it.pluginId == BuiltInPlugins.YOUTUBE_LIVE }.map { c ->
                GuideRow(c, when (c) {
                    dd -> listOf(EpgProgramme("DDTamil.in", start - hour / 2, start + hour / 2, "Oliyum Oliyum"), EpgProgramme("DDTamil.in", start + hour / 2, start + 2 * hour, "Seithigal"))
                    sports -> listOf(EpgProgramme("DDSports.in", start - 3 * hour, start + 2 * hour, "India v Australia, 2nd T20I"))
                    else -> emptyList()
                })
            }
            return GuideWindow(base.fromMs, base.toMs, ours + base.rows)
        }
    }

    private fun host(repository: WatchRepository, settings: LiveNowSettings = LiveNowSettings(LiveNowMemoryStore())) =
        LiveNowHost(events = { repository.liveEvents() }, settings = settings, play = { _, _ -> }, clock = { evening + 20_000 })

    private fun page(dark: Boolean, size: Size, content: @Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(size.width.dp, size.height.dp).testTag("live:review").background(Color(colors.palette.backgroundBottom))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val file = File("../build/live-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("live:review").captureToImage().toAwtImage(), "png", file)
    }

    private fun each(block: (dark: Boolean, tag: String) -> Unit) { for (dark in listOf(true, false)) block(dark, if (dark) "dark" else "light") }

    @Test fun thePillInTwoModesAndOnTheDesktopBar() = hosts {
        each { dark, tag ->
            val repository = LiveTvRepository()
            val live = host(repository)
            WatchNav.go(WatchTab.HOME)
            page(dark, phone) {
                ProvideWatch(repository = repository, clock = { evening }) {
                    CompositionLocalProvider(LocalLiveNow provides live, LocalLiveNowInPage provides true) { WatchApp(showNavigation = true) }
                }
            }
            capture("pill-watch-phone-$tag")
            // The desktop's top bar (the back button, the pill, the account) over Watch's wide page.
            page(dark, wide) {
                val look = orbitLook()
                ProvideWatch(repository = repository, clock = { evening }) {
                    CompositionLocalProvider(LocalLiveNow provides live) {
                        Column(Modifier.fillMaxSize()) {
                            Row(Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(40.dp).clip(CircleShape).background(look.chip))
                                Spacer(Modifier.weight(1f))
                                LiveNowTopBarPill(Modifier.padding(end = 8.dp))
                                Box(Modifier.size(36.dp).clip(CircleShape).background(look.chip))
                            }
                            WatchApp(showNavigation = false)
                        }
                    }
                }
            }
            capture("pill-watch-desktop-$tag")
        }
        // Podio's phone head, the second mode.
        server().use { server ->
            val base = server.url("/").toString().trimEnd('/')
            val catalogue = PodioCatalogue(radioMirrors = listOf("$base/"), appleBase = base, fyydBase = base, weatherBase = base, wikipediaBase = base, commonsBase = base)
            each { dark, tag ->
                val live = host(FakeWatchRepository())
                val file = temporary.newFile("library-$tag.json")
                ModeNav.podioTab.value = "Radio"
                page(dark, phone) {
                    CompositionLocalProvider(LocalLiveNow provides live, LocalLiveNowInPage provides true) {
                        PodioApp(file, PodioPlayback(), { _, _ -> }, {}, {}, {}, onMusic = {}, directory = catalogue, showNavigation = true,
                            downloadedEpisodes = emptyList(), onDownloadEpisode = {}, onSleepTimer = {}, onSleepAfterEpisode = {})
                    }
                }
                capture("pill-podio-phone-$tag")
            }
        }
    }

    @Test fun thePanelTheSheetAndSettings() = hosts {
        each { dark, tag ->
            val repository = FakeWatchRepository()
            val events: List<LiveEvent> = runBlocking { repository.liveEvents().first() }
            val settings = LiveNowSettings(LiveNowMemoryStore())
            val live = host(repository, settings)
            settings.toggleReminder(events.first { it.status == com.cosmotune.watch.model.LiveEventStatus.UPCOMING })
            page(dark, wide) {
                val look = orbitLook()
                Box(Modifier.fillMaxSize()) {
                    LiveNowFloatingPanel(look, Modifier.align(Alignment.TopEnd).padding(top = 64.dp, end = 16.dp).width(560.dp)) {
                        LiveNowPanel(live, events, orbitFonts(), look, onClose = {})
                    }
                }
            }
            capture("panel-desktop-$tag")
            page(dark, phone) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
                    CosmoSheetSurface { LiveNowPanel(live, events, orbitFonts(), orbitLook(), modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)) }
                }
            }
            capture("sheet-phone-$tag")
            settings.setTeam("India", true)
            settings.setSport(com.cosmotune.watch.model.LiveEventKind.SHOW, false)
            page(dark, phone) {
                Column(Modifier.fillMaxSize().padding(top = 24.dp), verticalArrangement = Arrangement.Top) { LiveNowSettingsSection(settings) }
            }
            capture("settings-phone-$tag")
        }
    }

    @Test fun liveTvAndPlugins() = hosts {
        each { dark, tag ->
            for (size in listOf(phone, wide)) {
                val repository = LiveTvRepository()
                WatchNav.go(WatchTab.LIVE)
                page(dark, size) { ProvideWatch(repository = repository, clock = { evening }) { WatchApp(showNavigation = size === phone) } }
                capture("livetv-${size.name}-$tag")
                WatchNav.go(WatchTab.PLUGINS)
                page(dark, size) { ProvideWatch(repository = repository, clock = { evening }) { WatchApp(showNavigation = size === phone) } }
                capture("plugins-${size.name}-$tag")
            }
        }
    }

    private fun server(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                return MockResponse().setBody(if (path.startsWith("/json/stations")) "[]" else "{}")
            }
        }
    }
}
