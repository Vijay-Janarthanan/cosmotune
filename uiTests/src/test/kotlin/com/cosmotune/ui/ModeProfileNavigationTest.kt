package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.design.AppMode
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.player.PlayerSettingsSource
import com.cosmotune.podio.PodioCatalogue
import com.cosmotune.podio.ui.PodioApp
import com.cosmotune.podio.ui.PodioPlayback
import com.cosmotune.settings.CosmoLookSettings
import com.cosmotune.settings.KeyValueStore
import com.cosmotune.ui.blocks.LocalModeProfileAction
import com.cosmotune.ui.chrome.CosmoDrawerEntry
import com.cosmotune.ui.chrome.CosmoProfileButton
import com.cosmotune.ui.components.CosmoIcons
import com.cosmotune.ui.theme.*
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import kotlin.test.assertEquals

class ModeProfileNavigationTest {
    val compose = createComposeRule()
    val temporary = TemporaryFolder()
    private val installed = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
    private var previousHost: Any? = null
    private val platform = object : ExternalResource() {
        override fun before() {
            previousHost = installed.get(null)
            val reduceBlur = MutableStateFlow(true)
            val settings = java.lang.reflect.Proxy.newProxyInstance(PlayerSettingsSource::class.java.classLoader,
                arrayOf(PlayerSettingsSource::class.java)) { _, method, _ ->
                when (method.name) { "getReduceDynamicBlur" -> reduceBlur; else -> error(method.name) }
            } as PlayerSettingsSource
            PlayerPlatform.install(java.lang.reflect.Proxy.newProxyInstance(PlayerHost::class.java.classLoader,
                arrayOf(PlayerHost::class.java)) { _, method, _ ->
                when (method.name) { "getSettings" -> settings; else -> error(method.name) }
            } as PlayerHost)
        }
        override fun after() { installed.set(null, previousHost) }
    }
    // Keep the host installed until Compose has disposed its drawer and any pending frames.
    @get:Rule val rules: RuleChain = RuleChain.outerRule(platform).around(temporary).around(compose)

    private fun settings(mode: AppMode) = CosmoLookSettings(object : KeyValueStore {
        override fun getBoolean(key: String, default: Boolean) = default
        override fun putBoolean(key: String, value: Boolean) = Unit
    }).apply { setMode(mode) }

    @Composable private fun Theme(settings: CosmoLookSettings, onSettings: () -> Unit, content: @Composable () -> Unit) {
        val shell = remember { CosmoShellState::class.java.getConstructor(CosmoLookSettings::class.java, Boolean::class.javaPrimitiveType)
            .newInstance(settings, true) }
        val type = rememberCosmoType()
        MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
            val action: @Composable () -> Unit = {
                CosmoProfileButton(null, "Vijay", null,
                    listOf(CosmoDrawerEntry(CosmoIcons.Settings, "Settings", onClick = onSettings)), onAccount = {})
            }
            CompositionLocalProvider(LocalCosmoShell provides shell, LocalCosmoColors provides shell.colors,
                LocalCosmoType provides type, LocalReducedMotion provides true, LocalModeProfileAction provides action, content = content)
        }
    }

    @Test fun mangaKeepsItsProfileMenuAndSelectedTabAfterASettingsRoundTrip() {
        val store = MangaStore(temporary.newFolder())
        runBlocking { store.source(MangaSourceConfig("fixture:catalogue", "mihon", "Reading catalogue")) }
        val queries = java.util.concurrent.CopyOnWriteArrayList<String>()
        val repository = MangaRepository(store) { config, _ -> object : MangaSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
                queries += query
                return MangaBrowsePage(listOf(MangaBook("fixture:discovery", config.id, "A story to discover")), null)
            }
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        val settings = settings(AppMode.MANGA)
        var settingsOpen by mutableStateOf(false)
        compose.setContent { Theme(settings, { settingsOpen = true }) {
            val holder = rememberSaveableStateHolder()
            Box(Modifier.requiredSize(390.dp, 740.dp).testTag("mode:phone")) {
                if (settingsOpen) TextButton(onClick = { settingsOpen = false }) { Text("Back to Manga") }
                else holder.SaveableStateProvider("manga") { MangaApp(repository = repository, showLeave = true) }
            }
        } }
        compose.onNodeWithContentDescription("Profile and modes").assertIsDisplayed()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A story to discover").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("A story to discover").assertExists()
        compose.onNodeWithText("Enter at least two characters to search enabled sources").assertDoesNotExist()
        compose.onNodeWithText("No manga found. Try another search or source.").assertDoesNotExist()
        compose.runOnIdle { assertEquals(listOf(""), queries.toList(), "Home browses immediately without a search or source choice") }
        compose.onNode(hasText("Browse") and isSelectable()).performClick().assertIsSelected()
        compose.onNodeWithContentDescription("Manage sources").performClick()
        compose.onNodeWithTag("manga:sources:list").assertExists()
        compose.onNode(hasText("Browse") and isSelectable()).performClick()
        compose.onNode(hasText("Browse") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Profile and modes").performClick()
        compose.onNode(hasText("Manga") and isSelectable()).assertIsSelected()
        compose.onNode(hasText("Settings") and hasAnyAncestor(isPopup())).performClick()
        compose.onNodeWithText("Back to Manga").performClick()
        compose.onNode(hasText("Browse") and isSelectable()).assertIsSelected()
        compose.onNodeWithContentDescription("Profile and modes").assertIsDisplayed()
        compose.onAllNodesWithText("Reading catalogue", substring = true).onFirst().assertIsDisplayed()
        snapshot("manga-phone-header.png")
    }

    @Test fun podioShowsTheSameProfileMenuAndCanSwitchDirectlyToManga() {
        MockWebServer().use { server ->
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setBody("[]")
            }
            server.start()
            val settings = settings(AppMode.PODIO)
            val catalogue = PodioCatalogue(radioMirrors = listOf(server.url("/").toString()))
            val storage = File(temporary.root, "podio.json")
            compose.setContent { Theme(settings, {}) { Box(Modifier.requiredSize(390.dp, 740.dp).testTag("mode:phone")) {
                PodioApp(storage, PodioPlayback(), onPlay = { _, _ -> }, onToggle = {}, onSeek = {}, onSpeed = {},
                    onMusic = {}, directory = catalogue)
            } } }
            compose.onNodeWithContentDescription("Profile and modes").assertIsDisplayed()
            compose.onNodeWithText("Back to Music").assertDoesNotExist()
            snapshot("podio-phone-header.png")
            compose.onNodeWithContentDescription("Profile and modes").performClick()
            compose.onNode(hasText("Podio") and isSelectable()).assertIsSelected()
            compose.onNode(hasText("Manga") and isSelectable()).performClick()
            compose.runOnIdle { assertEquals(AppMode.MANGA, settings.mode.value) }
        }
    }

    private fun snapshot(name: String) {
        val output = File("../build/ui-parity-2026-11-02/$name").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("mode:phone").captureToImage().toAwtImage(), "png", output)
    }
}
