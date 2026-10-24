package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.ui.theme.*
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** MG-09: connecting, switching off and removing a Komga server from Manga sources, against a loopback server. */
@OptIn(ExperimentalTestApi::class)
class MangaKomgaInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val keys = CopyOnWriteArrayList<String>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/api/v1/series") { exchange ->
            val key = exchange.requestHeaders.getFirst("X-API-Key").orEmpty()
            keys += key
            val body = (if (key == "good-key") """{"content":[{"id":"s1","name":"Server series","metadata":{"title":"Server series"}}],"last":true}""" else "").toByteArray()
            exchange.sendResponseHeaders(if (key == "good-key") 200 else 401, if (body.isEmpty()) -1 else body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    @After fun stop() = server.stop(0)

    @Test fun aServerIsTestedBeforeSavingAndCanBeSwitchedOffAndRemoved() {
        val store = MangaStore(temporary.newFolder())
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                CompositionLocalProvider(LocalCosmoColors provides colors) {
                    Box(Modifier.width(600.dp).fillMaxHeight()) { MangaApp(repository = MangaRepository(store)) }
                }
            }
        }
        compose.onNodeWithText("Sources").performClick()
        compose.onNodeWithText("Connect Komga library").performClick()
        compose.onNodeWithTag("manga:komga:server").performTextInput("http://127.0.0.1:${server.address.port}")
        compose.onNodeWithTag("manga:komga:key").performTextInput("wrong-key")
        compose.onNodeWithText("Test and save").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("rejected the API key", substring = true).fetchSemanticsNodes().isNotEmpty() }
        assertTrue(store.state.value.sources.none { it.kind == "komga" }, "a rejected key is not saved")

        compose.onNodeWithTag("manga:komga:key").performTextReplacement("good-key")
        compose.onNodeWithText("Test and save").performClick()
        compose.waitUntil(10_000) { store.state.value.sources.any { it.kind == "komga" } }
        val saved = store.state.value.sources.single { it.kind == "komga" }
        assertEquals("good-key", saved.settings["apiKey"])
        assertEquals(listOf("wrong-key", "good-key"), keys)
        compose.onNodeWithText("Connect Komga library").assertExists()
        compose.onNodeWithTag("manga:komga:server").assertDoesNotExist()

        compose.onNodeWithContentDescription("Use ${saved.name}").performClick()
        compose.waitUntil(5_000) { store.state.value.sources.single { it.kind == "komga" }.enabled.not() }
        compose.onNode(hasText("Remove") and hasAnyAncestor(hasTestTag("manga:komga:${saved.id}"))).performClick()
        compose.waitUntil(5_000) { store.state.value.sources.none { it.kind == "komga" } }
        assertFalse(MangaStore(store.root).state.value.sources.any { it.kind == "komga" }, "removal is saved")
    }
}
