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
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.ui.theme.*
import com.sun.net.httpserver.HttpServer
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MG-06: downloading a series' unread chapters in one transfer, and checking the library for new chapters, on screen. */
@OptIn(ExperimentalTestApi::class)
class MangaLibraryInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private val requests = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        val image = ByteArrayOutputStream().also { ImageIO.write(BufferedImage(8, 8, BufferedImage.TYPE_INT_RGB), "png", it) }.toByteArray()
        createContext("/") { exchange ->
            requests.incrementAndGet()
            exchange.responseHeaders.add("Content-Type", "image/png")
            exchange.sendResponseHeaders(200, image.size.toLong())
            exchange.responseBody.use { it.write(image) }
        }
        start()
    }
    @After fun stop() = server.stop(0)

    @Test fun unreadChaptersDownloadTogetherAndAnUpdateCheckMarksTheNewChapter() {
        val store = MangaStore(temporary.newFolder())
        val details = AtomicInteger()
        val base = "http://127.0.0.1:${server.address.port}"
        val repository = MangaRepository(store) { config, _ -> object : MangaSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage(listOf(MangaBook("net:book", config.id, "Network series")), null)
            override suspend fun details(book: MangaBook): MangaBook {
                val count = if (details.incrementAndGet() == 1) 2 else 3
                return book.copy(chapters = (1..count).map { MangaChapter("net:c$it", book.id, config.id, "Chapter $it", "c$it", it.toFloat()) })
            }
            override suspend fun pages(chapter: MangaChapter) = (1..2).map { MangaPage("${chapter.id}:p$it", "$base/${chapter.url}/$it.png") }
        } }
        runBlocking { store.source(MangaSourceConfig("fixture:net", "mihon", "Net source")) }
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                CompositionLocalProvider(LocalCosmoColors provides colors) {
                    Box(Modifier.width(600.dp).fillMaxHeight()) { MangaApp(repository = repository) }
                }
            }
        }
        fun waitText(text: String, substring: Boolean = false) =
            compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Check for updates").assertDoesNotExist()
        compose.onNodeWithText("My library").performClick()
        waitText("Net source", substring = true)
        compose.onNodeWithText("Net source", substring = true).performClick()
        waitText("Network series")
        compose.onNodeWithText("Network series").performClick()
        waitText("Download 2 unread chapters")
        compose.onNodeWithText("Download 2 unread chapters").performClick()
        compose.waitUntil(10_000) { store.state.value.downloads.keys == setOf("net:c1", "net:c2") }
        assertEquals(4, requests.get(), "two pages for each of the two chapters")
        assertTrue(store.state.value.books.any { it.id == "net:book" }, "downloading adds the series to the library")
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Remove download").fetchSemanticsNodes().size == 2 }
        compose.onNodeWithText("Download 2 unread chapters").assertDoesNotExist()

        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Net source", substring = true).performClick()
        compose.onNodeWithText("My library").performClick()
        waitText("Check for updates")
        compose.onNodeWithText("2 unread", substring = false).assertExists()
        compose.onNodeWithText("Check for updates").performClick()
        waitText("1 new chapter in 1 series")
        compose.onNodeWithText("3 unread · 1 new").assertExists()
        assertEquals(setOf("net:c3"), MangaStore(store.root).state.value.newChapters, "the mark survives a restart")
        compose.onNodeWithText("Network series").performClick()
        waitText("New", substring = true)
        compose.onAllNodesWithText("Mark read")[2].performClick()
        compose.waitUntil(5_000) { store.state.value.newChapters.isEmpty() }
    }
}
