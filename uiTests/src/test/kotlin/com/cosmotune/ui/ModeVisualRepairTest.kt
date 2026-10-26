package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.music.data.canvas.AppleArtistArt
import com.cosmotune.music.ui.player.*
import com.cosmotune.podio.*
import com.cosmotune.podio.ui.*
import com.cosmotune.ui.pages.ArtistMotionArtwork
import com.cosmotune.ui.home.HomeDiscoveryControls
import com.cosmotune.music.data.recommendations.DiscoveryPreferences
import com.cosmotune.music.data.recommendations.DiscoveryRegion
import com.cosmotune.ui.theme.*
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ModeVisualRepairTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Composable private fun Theme(content: @Composable () -> Unit) {
        val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
        val type = rememberCosmoType()
        MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme()),
            typography = type.toMaterialTypography(MaterialTheme.typography)) {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, content = content)
        }
    }

    @Test fun lateOrFailedAppleArtworkNeverClearsTheWorkingArtistImage() {
        val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = installed.get(null)
        val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader, arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
            when (method.name) {
                "getAnimatedCanvas", "getCanvasOverCellular" -> MutableStateFlow(false)
                "getMeteredConnection" -> MutableStateFlow<Boolean?>(false)
                else -> error(method.name)
            }
        } as PlayerSettingsSource
        PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader, arrayOf(PlayerHost::class.java)) { _, method, _ ->
            if (method.name == "getSettings") settings else error(method.name)
        } as PlayerHost)
        fun picture(name: String, rgb: Int): String {
            val image = BufferedImage(24, 24, BufferedImage.TYPE_INT_RGB)
            for (x in 0..23) for (y in 0..23) image.setRGB(x, y, rgb)
            return temporary.newFile(name).also { ImageIO.write(image, "png", it) }.toURI().toString().replace("file:/", "file:///")
        }
        val blue = 0xff2864a0.toInt(); val red = 0xffb43232.toInt()
        val fallback = picture("original.png", blue); val hero = picture("apple.png", red)
        val broken = File(temporary.root, "missing.png").toURI().toString()
        val art = mutableStateOf<AppleArtistArt?>(null)
        fun pixel(): Int = compose.onNodeWithTag("artist:repair").captureToImage().toPixelMap()[60, 60].toArgb()
        try {
            compose.setContent { Theme { ArtistMotionArtwork(art.value, fallback, Modifier.size(160.dp).testTag("artist:repair")) } }
            try { compose.waitUntil(10_000) { pixel() == blue } }
            catch (error: Throwable) { snapshot("artist-first-frame.png", "artist:repair"); println("Artist pixel: ${pixel().toUInt().toString(16)}"); throw error }
            compose.runOnIdle { art.value = AppleArtistArt(hero, null, 1f, null, 0, 0, null) }
            compose.waitUntil(10_000) { pixel() == red }
            compose.runOnIdle { art.value = AppleArtistArt(broken, null, 1f, null, 0, 0, null) }
            Thread.sleep(500)
            compose.waitUntil(10_000) { pixel() == blue }
            snapshot("artist-fallback.png", "artist:repair")
        } finally { installed.set(null, previous) }
    }

    @Test fun tamilRadioAndExpandedMapStayUsableWithTheNativeTheme() {
        val requests = java.util.concurrent.CopyOnWriteArrayList<String>()
        MockWebServer().use { server -> MockWebServer().use { apple -> MockWebServer().use { fyyd ->
            val dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest): MockResponse {
                    requests += request.requestUrl.toString()
                    if (request.path.orEmpty().startsWith("/search")) return MockResponse().setBody("""{"results":[{"collectionName":"A regional show","feedUrl":"https://publisher.example/rss"}]}""")
                    if (request.path.orEmpty().startsWith("/0.2")) return MockResponse().setBody("""{"data":[]}""")
                    val language = request.requestUrl?.queryParameter("language")
                    val result = if (language != null && language != "tamil") "[]" else """[
                        {"stationuuid":"tamil-one","name":"Tamil One","url_resolved":"https://audio.example/one","language":"tamil","country":"India","geo_lat":20,"geo_long":78},
                        {"stationuuid":"tamil-two","name":"Tamil Two","url_resolved":"https://audio.example/two","language":"tamil","country":"Sri Lanka"}]
                    """
                    return MockResponse().setBody(result)
                }
            }
            server.dispatcher = dispatcher; apple.dispatcher = dispatcher; fyyd.dispatcher = dispatcher
            val played = mutableListOf<PodioItem>()
            val storage = temporary.newFile("library.json")
            val catalogue = PodioCatalogue(radioMirrors = listOf(server.url("/").toString()),
                appleBase = apple.url("/").toString().trimEnd('/'), fyydBase = fyyd.url("/").toString().trimEnd('/'))
            compose.setContent { Theme { Box(Modifier.size(1040.dp, 780.dp)) {
                PodioApp(storage, PodioPlayback(), { item, _ -> played += item }, {}, {}, {}, {},
                    directory = catalogue)
            } } }
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Tamil Two").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("All languages").performClick()
            compose.onNodeWithText("Tamil", useUnmergedTree = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("2 stations · Worldwide").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Tamil Two").performClick()
            compose.runOnIdle { assertEquals("Tamil Two", played.single().title) }
            snapshot("podio-desktop.png", "podio:root")
            compose.onNodeWithText("Expand map").performClick()
            compose.onNodeWithText("Radio map").assertIsDisplayed()
            compose.onAllNodesWithTag("podio:globe").onLast().performTouchInput { click(center) }
            compose.onAllNodesWithContentDescription("Zoom in").onLast().performClick()
            snapshot("podio-map.png", "podio:map")
            compose.onNodeWithText("Close map").performClick()
            compose.onNodeWithText("Find a station").assertIsDisplayed()
            compose.onNodeWithText("Podcasts").performClick()
            compose.onNodeWithText("Podcasts").assertIsSelected()
            try {
                compose.waitUntil(20_000) { compose.onAllNodesWithText("A regional show").fetchSemanticsNodes().isNotEmpty() }
            } catch (error: Throwable) {
                println("Mode fixture requests: $requests")
                println(runCatching { compose.onNodeWithTag("podio:root").printToString() }.getOrNull())
                throw error
            }
        } } }
    }

    @Test fun mangaLibraryAndDiscoveryShareTheNativeModeChrome() {
        val repository = MangaRepository(MangaStore(temporary.newFolder()))
        runBlocking { repository.store.save(MangaBook("fixture:book", "local", "An evening story")) }
        compose.setContent { Theme { Box(Modifier.width(412.dp).fillMaxHeight()) { MangaApp(repository = repository) } } }
        compose.onNodeWithText("Library").assertIsDisplayed()
        compose.onNodeWithText("Discover").assertIsDisplayed()
        compose.onNodeWithText("An evening story").assertExists()
        snapshot("manga-phone.png", "manga:root")
        compose.onNodeWithText("Discover").performClick()
        compose.onNodeWithText("All enabled sources").assertIsDisplayed()
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("Done").performClick()
    }

    @Test fun discoveryChoicesUseTheSamePaletteAndOfferTheRequestedLanguages() {
        DiscoveryPreferences.configure(File(temporary.root, "discovery.json"))
        DiscoveryPreferences.save(DiscoveryRegion("IN", "ta"))
        compose.setContent { Theme { Box(Modifier.width(412.dp).testTag("home:repair")) { HomeDiscoveryControls({}) } } }
        compose.onNodeWithText("Tamil").assertIsDisplayed()
        snapshot("home-discovery-phone.png", "home:repair")
        compose.onNodeWithTag("home:language").performClick()
        compose.onNodeWithTag("home:language:en").assertExists()
        compose.onNodeWithTag("home:language:de").assertDoesNotExist()
    }

    private fun snapshot(name: String, tag: String) {
        val output = File("../build/ui-1.0.8/$name").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag(tag).captureToImage().toAwtImage(), "png", output)
    }
}
