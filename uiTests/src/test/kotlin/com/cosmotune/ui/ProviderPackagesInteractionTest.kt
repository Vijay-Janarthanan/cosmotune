package com.cosmotune.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.music.data.model.Song
import com.cosmotune.music.ui.player.PlayerHost
import com.cosmotune.music.ui.player.PlayerPlatform
import com.cosmotune.music.ui.AppUi
import com.cosmotune.music.ui.AppUiHost
import com.cosmotune.music.data.settings.LibrarySort
import com.cosmotune.music.data.settings.LibraryViewType
import com.cosmotune.providers.*
import com.cosmotune.ui.providers.ProviderPackagesDialog
import com.cosmotune.ui.providers.HttpAddonSettingsEntry
import com.cosmotune.ui.providers.HttpAddonCatalogueEntry
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class ProviderPackagesInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private fun manager(): ProviderManager = runBlocking {
        val protection = object : ProviderFileProtection {
            override fun seal(bytes: ByteArray) = bytes
            override fun open(bytes: ByteArray) = bytes
        }
        val manager = ProviderManager(ProviderStore(temporary.newFolder(), protection))
        val manifest = """{"name":"fixture","displayName":"Fixture provider","version":"1.0.0","type":["metadata_provider"],"permissions":{"network":[]},"settings":[{"key":"country","label":"Country","default":"IN"}],"capabilities":{"homeFeed":true,"homeFeedRegionSetting":"country"}}"""
        val code = """
            registerExtension({
              searchTracks: function(query) { return [{id:'recording',name:query,artists:'Singer',duration_ms:180000}]; },
              getHomeFeed: function() { return {sections:[{title:settings.country,items:[{id:'home-song',name:'A regional song',artists:'Singer',item_type:'track'}]}]}; }
            });
        """.trimIndent()
        val bytes = ByteArrayOutputStream().use { output ->
            ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to code).forEach { (name, content) ->
                zip.putNextEntry(ZipEntry(name)); zip.write(content.toByteArray()); zip.closeEntry()
            } }
            output.toByteArray()
        }
        manager.trust(manager.inspect { bytes.inputStream() })
        manager
    }

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
                else -> error("Provider row unexpectedly requested ${method.name}")
            }
        } as PlayerHost
        PlayerPlatform.install(host)
        try { block() } finally { installedHost.set(null, previous); installedUi.set(null, previousUi) }
    }

    @Test fun providerOperationStatusIsShownDuringWorkAndRemovedAfterCompletion() {
        manager().use { manager ->
            val manifest = """{"name":"fixture","displayName":"Fixture provider","version":"1.1.0","type":["metadata_provider"],"permissions":{"network":[]}}"""
            val code = """registerExtension({searchTracks:function(){utils.setDownloadStatus('Preparing provider audio');utils.sleep(1200);return [];}});"""
            val archive = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip -> listOf("manifest.json" to manifest, "index.js" to code).forEach { (name, text) ->
                    zip.putNextEntry(ZipEntry(name)); zip.write(text.toByteArray(Charsets.UTF_8)); zip.closeEntry()
                } }; output.toByteArray()
            }
            runBlocking { manager.trust(manager.inspect { archive.inputStream() }) }
            compose.setContent { MaterialTheme { ProviderPackagesDialog(onDismiss = {}, manager = manager) } }
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.IO)
            try {
                val loading = scope.async { manager.search("fixture", "query") }
                compose.waitUntil(15_000) { compose.onAllNodesWithText("Preparing provider audio").fetchSemanticsNodes().isNotEmpty() }
                assertEquals("Preparing provider audio", manager.progress.value["fixture"]?.status)
                runBlocking { loading.await() }
                compose.waitUntil(10_000) { manager.progress.value.isEmpty() && compose.onAllNodesWithText("Preparing provider audio").fetchSemanticsNodes().isEmpty() }
            } finally { scope.cancel() }
        }
    }

    @Test fun providerBrowseAndSearchHandTheChosenSourceRecordingToPlayback() = withRowHosts {
        manager().use { manager ->
            var played: Song? = null
            compose.setContent { MaterialTheme { ProviderPackagesDialog(onDismiss = {}, manager = manager,
                onPlay = { songs, index -> played = songs[index] }) } }
            compose.onNodeWithTag("provider:fixture:browse").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("provider:track:home-song").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("provider:query").performTextInput("An exact recording")
            compose.onNodeWithText("Search", substring = false).performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("provider:track:recording").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("provider:track:recording").performClick()
            compose.runOnIdle {
                assertEquals("src:sflx-fixture::recording", played?.videoId)
                assertEquals("An exact recording", played?.title)
                assertNull(played?.thumbnailUrl)
            }
        }
    }

    @Test fun settingsPersistAndTheProviderSwitchActuallyDisablesItsRuntime() {
        manager().use { manager ->
            compose.setContent { MaterialTheme { ProviderPackagesDialog(onDismiss = {}, manager = manager) } }
            compose.onNodeWithTag("provider:fixture:settings").performClick()
            compose.onNodeWithText("IN", substring = false).performTextReplacement("US")
            compose.onNodeWithText("Save", substring = false).performClick()
            compose.waitUntil(10_000) { manager.store.state.value.settings["fixture"]?.get("country") == JsonPrimitive("US") }
            compose.onNodeWithTag("provider:fixture:enabled").performClick()
            compose.waitUntil(10_000) { !manager.store.state.value.installed.single().enabled }
            runBlocking { assertFailsWith<IllegalArgumentException> { manager.search("fixture", "Song") } }
        }
    }

    @Test fun httpAddonSettingsKeepSavedValuesAndMaskCredentialFields() {
        var saved: Map<String, String>? = null
        compose.setContent { MaterialTheme {
            HttpAddonSettingsEntry("An HTTP source", mapOf("country" to "IN"), load = {
                listOf(ProviderSetting("country", "Country", default = JsonPrimitive("US")),
                    ProviderSetting("credential", "Credential", type = "password", default = JsonPrimitive("")))
            }, onSave = { saved = it })
        } }
        compose.onNodeWithText("Source settings").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("IN", substring = false).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("IN", substring = false).performTextReplacement("GB")
        compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsProperties.Password)).performTextInput("fixture-private-value")
        compose.onNodeWithText("Save", substring = false).performClick()
        compose.waitUntil(10_000) { saved != null }
        assertEquals("GB", saved?.get("country"))
        assertEquals("fixture-private-value", saved?.get("credential"))
    }

    @Test fun httpAddonAlbumsAndArtistsStayOnTheirOwnSourceAndHandOffTheChosenRecording() = withRowHosts {
        var played: Song? = null
        val calls = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        compose.setContent { MaterialTheme {
            HttpAddonCatalogueEntry("http-fixture", "Fixture HTTP", true, "one", load = { resource, id, _ ->
                calls += resource to id
                Json.parseToJsonElement(when (resource) {
                    "manifest" -> """{"resources":["search","album","artist"]}"""
                    "search" -> """{"tracks":[],"albums":[{"id":"album/one","title":"An album"}]}"""
                    "album" -> """{"id":"album/one","title":"An album","tracks":[{"id":"recording","title":"An album song","artist":"Singer","artistId":"singer"}]}"""
                    "artist" -> """{"id":"singer","name":"Singer","topTracks":[{"id":"top","title":"A top song","artist":"Singer"}]}"""
                    else -> error("Unexpected source operation")
                }).jsonObject
            }, song = { it.song("http-fixture") }, onPlay = { songs, index -> played = songs[index] })
        } }
        compose.onNodeWithText("Browse Fixture HTTP").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:query").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider:query").performTextInput("An album")
        compose.onNodeWithText("Search", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:collection:album/one").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider:collection:album/one").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:track:recording").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("provider:track:recording").performClick()
        compose.runOnIdle {
            assertEquals("src:http-fixture::recording", played?.videoId)
            assertEquals("addon", ProviderBrowseKey.parse(requireNotNull(played?.albumId))?.namespace)
            assertEquals("album/one", ProviderBrowseKey.parse(requireNotNull(played?.albumId))?.id)
        }
        compose.onNodeWithContentDescription("More", substring = false).performClick()
        compose.onNodeWithText("Artist · Singer").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("provider:track:top").fetchSemanticsNodes().isNotEmpty() }
        assertTrue(calls.contains("album" to "album/one"))
        assertTrue(calls.contains("artist" to "singer"))
    }
}
