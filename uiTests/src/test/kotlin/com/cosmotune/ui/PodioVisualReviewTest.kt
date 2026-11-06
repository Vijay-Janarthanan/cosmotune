package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.unit.dp
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.podio.PodcastShow
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.PodioItem
import com.cosmotune.podio.PodioLibrary
import com.cosmotune.podio.RadioStation
import com.cosmotune.podio.item
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Podio in Orbit (Canvas repo, design/cosmotune/batch-be/board.html, section C) on a local directory: Radio idle and live, the
 * Globe, a station page, the podcasts home, a show (with an episode playing), Following, Downloads and the search, drawn at phone
 * width and at the desktop page's width in the dark and the light palettes, written under build/podio-review for a look.
 */
class PodioVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val stations = listOf(
        Triple("radio-city", "Radio City 98.3", "Tamil Nadu|India|tamil|13.08|80.27"),
        Triple("suryan", "Suryan FM 93.5", "Tamil Nadu|India|tamil|13.06|80.25"),
        Triple("hello", "Hello FM 106.4", "Tamil Nadu|India|tamil|13.03|80.21"),
        Triple("rainbow", "AIR Rainbow", "Tamil Nadu|India|tamil|13.1|80.28"),
        Triple("mirchi", "Radio Mirchi", "Tamil Nadu|India|tamil|11.0|76.96"),
        Triple("big", "Big FM 92.7", "Karnataka|India|kannada|12.97|77.59"),
        Triple("red", "Red FM Kochi", "Kerala|India|malayalam|9.93|76.27"),
        Triple("club", "Club FM", "Kerala|India|malayalam|9.98|76.28"),
        Triple("one", "Radio One", "Maharashtra|India|hindi|19.07|72.87"),
        Triple("fever", "Fever 104", "Delhi|India|hindi|28.61|77.2"),
    )
    private val shows = listOf("The Long Read", "Salt and Spice", "Tamil Stories", "History Hour", "Small Planet", "The Daily Brief", "Cinema Talk", "Field Notes")
    private val episodes = listOf("The lighthouse keepers" to "48:12", "Salt roads of Gujarat" to "52:04", "The last tram" to "41:30", "A city of rivers" to "39:10",
        "The bell makers" to "55:00", "Night trains of the south" to "44:44")

    private fun server(): MockWebServer = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val path = request.path.orEmpty()
                val base = url("/").toString().trimEnd('/')
                val body = when {
                    path.startsWith("/json/stations") -> stations.joinToString(",", "[", "]") { (id, name, rest) ->
                        val (state, country, language, lat, lon) = rest.split('|')
                        """{"stationuuid":"$id","name":"$name","country":"$country","countrycode":"IN","state":"$state","language":"$language","codec":"MP3","bitrate":"128","url_resolved":"$base/stream/$id","geo_lat":$lat,"geo_long":$lon}"""
                    }
                    path.contains("/rss/toppodcasts") -> shows.indices.joinToString(",", """{"feed":{"entry":[""", "]}}") { """{"id":{"attributes":{"im:id":"${100 + it}"}}}""" }
                    path.startsWith("/lookup") || path.startsWith("/search") -> shows.indices.joinToString(",", """{"results":[""", "]}") { i ->
                        """{"collectionId":"${100 + i}","collectionName":"${shows[i]}","artistName":"${if (i == 0) "The Guardian" else "Studio ${i + 1}"}","feedUrl":"$base/feed/$i"}"""
                    }
                    path.startsWith("/0.2") -> """{"data":[]}"""
                    path.startsWith("/feed/") -> feed(path.removePrefix("/feed/").toIntOrNull() ?: 0)
                    path.startsWith("/stream") -> ""
                    else -> "{}"
                }
                return MockResponse().setBody(body)
            }
        }
    }

    private fun feed(index: Int): String = buildString {
        append("""<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>${shows[index]}</title><author>${if (index == 0) "The Guardian" else "Studio"}</author>""")
        episodes.forEachIndexed { i, (title, length) ->
            append("""<item><guid>$index-$i</guid><title>$title</title><itunes:duration>$length</itunes:duration><pubDate>${20 - i * 3} Oct 2026 06:00:00 GMT</pubDate>""")
            append("""<description>An essay read aloud: $title, and the people who keep it going.</description><enclosure url="https://audio.example/$index/$i.mp3" type="audio/mpeg"/></item>""")
        }
        append("</channel></rss>")
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
        try { block() } finally { playerField.set(null, previousPlayer); uiField.set(null, previousUi); reset() }
    }

    private fun reset() { ModeNav.podioTab.value = "Radio"; ModeNav.podioSaved.value = false; ModeNav.podioDetail.value = false }

    private class Size(val name: String, val width: Int, val height: Int, val phone: Boolean)
    private val phone = Size("phone", 390, 844, true)
    private val wide = Size("wide", 1040, 800, false)

    private fun page(dark: Boolean, size: Size, catalogue: PodioCatalogue, file: File, playback: PodioPlayback = PodioPlayback(), downloaded: List<PodioItem> = emptyList()) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(size.width.dp, size.height.dp).testTag("podio:review").background(Color(colors.palette.backgroundBottom))) {
                        PodioApp(file, playback, { _, _ -> }, {}, {}, {}, onMusic = {}, directory = catalogue, showNavigation = size.phone,
                            downloadedEpisodes = downloaded, onDownloadEpisode = {}, onSleepTimer = {}, onSleepAfterEpisode = {})
                    }
                }
            }
        }
    }

    private fun waitFor(text: String) = compose.waitUntil(20_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun waitTag(tag: String) = compose.waitUntil(20_000) { compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        val file = File("../build/podio-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("podio:review").captureToImage().toAwtImage(), "png", file)
    }

    private fun tab(size: Size, section: String) {
        if (size.phone) compose.onNode(podioTab(section)).performClick() else ModeNav.podioTab.value = section
        compose.waitForIdle()
    }

    @Test fun podioPagesInDarkAndLight() = hosts {
        server().use { server ->
            val base = server.url("/").toString()
            val catalogue = PodioCatalogue(radioMirrors = listOf(base), appleBase = base.trimEnd('/'), fyydBase = base.trimEnd('/'),
                weatherBase = base.trimEnd('/'), wikipediaBase = base.trimEnd('/'), commonsBase = base.trimEnd('/'))
            val longRead = PodioCatalogue.parseFeed(feed(0), server.url("/feed/0").toString())
            val show = PodcastShow(longRead.show.id, longRead.show.title, "The Guardian", longRead.show.feed)
            val station = { id: String, name: String -> RadioStation(id, name, server.url("/stream/$id").toString(), "India", "IN", "tamil", state = "Tamil Nadu", codec = "MP3", bitrate = 128,
                latitude = 13.0, longitude = 80.2) }
            val heard = longRead.episodes[0].item()
            val done = longRead.episodes[2].item()
            val offline = longRead.episodes[1].item().let { it.copy(downloadedPath = "x.mp3", downloadedUri = "file:/x.mp3") }
            val live = station("radio-city", "Radio City 98.3")
            for (dark in listOf(true, false)) for (size in listOf(phone, wide)) {
                val tag = "${size.name}-${if (dark) "dark" else "light"}"
                val file = temporary.newFile("library-$tag.json")
                val library = PodioLibrary(file)
                library.update { it.copy(shows = listOf(show), stations = listOf(station("suryan", "Suryan FM 93.5"), station("hello", "Hello FM 106.4"), station("mirchi", "Radio Mirchi"))) }
                library.recordProgress(heard.id, 30 * 60_000L, heard.durationSeconds * 1000)
                library.update { it.copy(completedEpisodes = setOf(done.id)) }

                reset(); page(dark, size, catalogue, file); waitFor("10 STATIONS"); capture("radio-$tag")
                val onAir = PodioPlayback(live.item().id, live.name, "Tamil Nadu", playing = true)
                reset(); page(dark, size, catalogue, file, onAir); waitFor("10 STATIONS"); capture("radio-live-$tag")
                if (size.phone) {
                    compose.onNodeWithText("Globe").performClick(); waitTag("podio:radio-full"); capture("globe-$tag")
                    compose.onNode(SemanticsMatcher("close") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.contains("Close full screen map") == true })
                        .performClick()
                    compose.onNode(SemanticsMatcher("opens the station") { it.config.getOrNull(SemanticsActions.OnClick)?.label == "Open the station page" })
                        .performSemanticsAction(SemanticsActions.OnClick) { it() }
                } else {
                    reset(); ModeNav.podioTab.value = "Globe"; page(dark, size, catalogue, file, onAir); waitFor("10 STATIONS"); capture("globe-$tag")
                    reset(); ModeNav.podioDetail.value = true; page(dark, size, catalogue, file, onAir)
                }
                waitTag("podio:detail"); capture("station-$tag")

                reset(); page(dark, size, catalogue, file); tab(size, "Podcasts"); waitFor("Top charts"); capture("podcasts-$tag")
                compose.onAllNodesWithText("The Long Read").onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
                waitFor("Latest:"); capture("show-$tag")
                val playing = PodioPlayback(heard.id, heard.title, "The Long Read", playing = true, positionMs = 30 * 60_000L, durationMs = heard.durationSeconds * 1000)
                reset(); page(dark, size, catalogue, file, playing); tab(size, "Podcasts"); waitFor("Top charts")
                compose.onAllNodesWithText("The Long Read").onFirst().performSemanticsAction(SemanticsActions.OnClick) { it() }
                waitFor("Playing:"); capture("show-playing-$tag")

                reset(); page(dark, size, catalogue, file, downloaded = listOf(offline)); tab(size, "Following"); waitFor("Salt roads of Gujarat"); capture("following-$tag")
                reset(); page(dark, size, catalogue, file, downloaded = listOf(offline)); tab(size, "Downloads"); waitFor("Salt roads"); capture("downloads-$tag")
                reset(); page(dark, size, catalogue, file); waitFor("10 STATIONS")
                compose.onNode(SemanticsMatcher("search") { it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.ContentDescription)?.contains("Search") == true })
                    .performClick()
                waitFor("Find a station"); waitFor("10 stations"); capture("search-$tag")
            }
        }
    }
}
