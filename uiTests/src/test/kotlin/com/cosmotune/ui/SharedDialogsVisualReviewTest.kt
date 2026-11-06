package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.*
import com.cosmotune.music.data.model.ArtistRef
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.providers.*
import com.cosmotune.ui.listening.ListeningTools
import com.cosmotune.ui.listening.ListeningToolsHost
import com.cosmotune.ui.listening.ListeningQueue
import com.cosmotune.ui.listening.ReplayTracks
import com.cosmotune.ui.providers.CatalogueBrowserAccess
import com.cosmotune.ui.providers.CatalogueBrowserDialog
import com.cosmotune.ui.providers.CatalogueSearchPage
import com.cosmotune.ui.providers.ProviderPackagesDialog
import com.cosmotune.ui.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO

/**
 * Not an assertion: the shared tool windows brought into Orbit (Your listening on two of its pages, Provider packages and the
 * catalogue browser) drawn offscreen in the dark and the light palettes, written under build/old-screens/shareddlg for a person
 * to look at.
 */
class SharedDialogsVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private val palettes = listOf(
        "dark" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(true, false), true),
        "light" to CosmoPaletteState(com.cosmotune.theme.OnePalette.of(false, false), false),
    )

    private fun cover(name: String, r: Int, g: Int, b: Int): String {
        val image = BufferedImage(320, 320, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let {
            it.color = java.awt.Color(r, g, b); it.fillRect(0, 0, 320, 320)
            it.color = java.awt.Color(minOf(r + 70, 255), minOf(g + 70, 255), minOf(b + 70, 255)); it.fillOval(50, 50, 220, 220)
            it.dispose()
        }
        return temporary.newFile("$name.png").also { ImageIO.write(image, "png", it) }.path
    }

    private fun hosts(block: () -> Unit) {
        val playerField = PlayerPlatform::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previous = playerField.get(null)
        val uiField = AppUi::class.java.getDeclaredField("installed").apply { isAccessible = true }
        val previousUi = uiField.get(null)
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
        try { block() } finally { playerField.set(null, previous); uiField.set(null, previousUi) }
    }

    private fun page(palette: CosmoPaletteState, content: @Composable () -> Unit) {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.fillMaxSize().background(Color(colors.palette.backgroundTop))) { content() }
                }
            }
        }
    }

    /** The window a dialog opens in is the second root; the whole of it, scrim and all, is what a person sees. */
    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File("../build/old-screens/shareddlg/$name.png").apply { parentFile.mkdirs() }
        val roots = compose.onAllNodes(isRoot())
        val node = if (roots.fetchSemanticsNodes().size > 1) roots[1] else roots[0]
        ImageIO.write(node.captureToImage().toAwtImage(), "png", file)
    }

    private fun listeningHost(file: File, songs: List<Song>) = ListeningToolsHost(
        file = file,
        library = ListeningLibrary(songs, likedIds = setOf(songs.first().videoId)),
        queue = { ListeningQueue(songs, 1, 56_000, 0, false) },
        replay = { ReplayTracks("Replay · November 2026", songs) },
        createPlaylist = { _, _ -> }, restore = {}, play = {}, openRelease = { _, _ -> },
        findMusic = {}, importMusic = {}, applySetup = { _, _ -> },
        exportFile = { _, _ -> true }, importFile = { null }, existingUser = true,
    )

    @Test fun yourListening() {
        val songs = listOf(
            Song("t1", "Golden Hour", "Mira and the Coast", null, durationText = "4:05"),
            Song("t2", "Low Tide Letters", "Harbour Lights", null, durationText = "3:32"),
            Song("t3", "Lantern Season", "Mira and the Coast", null, durationText = "6:18"),
        )
        for ((name, palette) in palettes) {
            val file = File(temporary.newFolder(), "listening.json")
            ListeningStore(file).apply {
                saveSession("Sunday morning", songs, 1, 56_000, 0, false)
                saveSession("Late drive home", songs.reversed(), 0, 0, 1, true)
            }
            page(palette) { ListeningTools(true, listeningHost(file, songs), {}, maxWidth = 960.dp) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:session:name").fetchSemanticsNodes().isNotEmpty() }
            capture("listening-sessions-$name")
            compose.onNodeWithTag("listening:tab:SMART").performScrollTo().performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("listening:smart:name").fetchSemanticsNodes().isNotEmpty() }
            capture("listening-smart-$name")
        }
    }

    private fun manager(): ProviderManager = runBlocking {
        val protection = object : ProviderFileProtection {
            override fun seal(bytes: ByteArray) = bytes
            override fun open(bytes: ByteArray) = bytes
        }
        val manager = ProviderManager(ProviderStore(temporary.newFolder(), protection))
        val manifest = """{"name":"fixture","displayName":"Coastline catalogue","version":"1.4.2","description":"Search, albums and regional charts from a public catalogue.","type":["metadata_provider"],"permissions":{"network":[]},"settings":[{"key":"country","label":"Country","default":"IN"}],"capabilities":{"homeFeed":true,"homeFeedRegionSetting":"country"}}"""
        val code = """registerExtension({ searchTracks: function(query) { return []; } });"""
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to code).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            } }
            output.toByteArray()
        }
        manager.trust(manager.inspect { bytes.inputStream() })
        manager
    }

    @Test fun providerPackages() {
        for ((name, palette) in palettes) {
            manager().use { manager ->
                page(palette) { ProviderPackagesDialog(onDismiss = {}, manager = manager) }
                compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:fixture:access").fetchSemanticsNodes().isNotEmpty() }
                capture("provider-packages-$name")
            }
        }
    }

    @Test fun catalogueBrowser() = hosts {
        val art = listOf(cover("a", 168, 92, 40), cover("b", 30, 120, 116), cover("c", 120, 60, 140))
        fun track(id: String, title: String, artist: String, i: Int) = ProviderTrack("fixture", id, title, artist, listOf(ArtistRef(artist, "fixture:artist:$id")),
            "The Long Light", "album-$id", null, 200_000L + i * 17_000L, null, JsonObject(emptyMap()), artworkReference = art[i % art.size])
        val tracks = listOf(track("h1", "Golden Hour", "Mira and the Coast", 0), track("h2", "Low Tide Letters", "Harbour Lights", 1),
            track("h3", "Lantern Season", "Mira and the Coast", 2))
        val access = object : CatalogueBrowserAccess {
            override val identity = "fixture"
            override val title = "Coastline catalogue"
            override val revision = "1"
            override val enabled = true
            override val settings = JsonObject(emptyMap())
            override val hasHome = true
            override suspend fun search(query: String, force: Boolean) = CatalogueSearchPage(tracks)
            override suspend fun catalogue(key: ProviderBrowseKey, force: Boolean) = ProviderCollection(key, "The Long Light", tracks = tracks)
            override suspend fun home(region: String, language: String, force: Boolean) = listOf(
                ProviderHomeSection("top", "Top songs this week", "fixture", tracks = tracks),
                ProviderHomeSection("new", "New albums", "fixture", collections = listOf(
                    ProviderCollection(ProviderBrowseKey("fixture", "album", "one"), "The Long Light", "Mira and the Coast · 2026"),
                    ProviderCollection(ProviderBrowseKey("fixture", "artist", "two"), "Harbour Lights", "Artist"),
                )),
            )
            override fun song(track: ProviderTrack) = Song("src:${track.id}", track.title, track.artist, track.artworkReference,
                durationText = track.durationMs?.let { "%d:%02d".format(it / 60_000, it / 1000 % 60) })
        }
        for ((name, palette) in palettes) {
            page(palette) { CatalogueBrowserDialog(access, onDismiss = {}, onPlay = { _, _ -> }) }
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:track:h1").fetchSemanticsNodes().isNotEmpty() }
            capture("catalogue-browser-$name")
        }
    }
}
