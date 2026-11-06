package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.reading.data.*
import com.cosmotune.reading.ui.ReadApp
import com.cosmotune.ui.theme.*
import java.awt.Color
import java.awt.Font
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/** Actual shared Compose screens. All book titles/covers below are original test fixtures. */
@OptIn(ExperimentalTestApi::class)
class BooksVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun show(width: Int) {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val json = """{"numFound":2,"docs":[{"key":"/works/OL100W","title":"The illustrated atlas","author_name":["Mira Sen"],"ebook_access":"no_ebook"},{"key":"/works/OL101W","title":"Field notes","author_name":["Ravi Patel"],"ebook_access":"no_ebook"}]}"""
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK")
                .body(json.toResponseBody("application/json".toMediaType())).build()
        }.build()
        val repository = ReadingRepository(temporary.newFolder(), client = client)
        fun cover(filename: String, title: String, bg: Color, accent: Color): String {
            val image = BufferedImage(240, 340, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { g ->
                g.color = bg; g.fillRect(0, 0, 240, 340)
                g.color = accent; g.fillOval(65, 30, 220, 220); g.color = bg; g.fillOval(86, 51, 178, 178)
                g.color = Color(238, 232, 218); g.font = Font("Serif", Font.BOLD, 29)
                title.split(" ").forEachIndexed { index, word -> g.drawString(word, 24, 235 + index * 32) }
                g.dispose()
            }
            return temporary.newFile(filename).also { ImageIO.write(image, "png", it) }.path
        }
        val atlas = ReadingBook("atlas", "The illustrated atlas", listOf("Mira Sen"), ReadingFormat.EPUB, "atlas.epub", cover("atlas.png", "Illustrated atlas", Color(29, 56, 53), Color(183, 143, 86)))
        val notes = ReadingBook("notes", "Field notes", listOf("Ravi Patel"), ReadingFormat.PDF, "notes.pdf", cover("notes.png", "Field notes", Color(70, 44, 41), Color(187, 157, 124)))
        runBlocking { repository.store.update { it.copy(books = listOf(atlas, notes), progress = mapOf("atlas" to ReadingProgress("atlas", ReadingLocator(progression = .35)))) } }
        val manga = MangaRepository(MangaStore(temporary.newFolder()))
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme()), typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type) {
                    Box(Modifier.size(width.dp, 800.dp)) { ReadApp(repository = repository) }
                }
            }
        }
    }

    private fun screenshots(size: String) {
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onAllNodesWithText("The illustrated atlas", substring = false).onFirst().assertExists()
        capture("books-library-$size")
        compose.onNode(hasText("Browse") and isSelectable()).performClick()
        waitForDiscoverResults()
        compose.onAllNodesWithText("Open Library · Book metadata", substring = true).onFirst().assertExists()
        capture("books-discover-$size")
    }

    /**
     * The search results arrive through the fixture client after the Browse tab opens. A slow runner can still be laying out the
     * narrow tab row when the first click lands and miss it, so after a long wait Browse is pressed once more (it is idempotent).
     */
    private fun waitForDiscoverResults() {
        val shown = { compose.onAllNodesWithText("The illustrated atlas", substring = false).fetchSemanticsNodes().isNotEmpty() }
        try {
            compose.waitUntil(15_000, shown)
        } catch (_: ComposeTimeoutException) {
            compose.onNode(hasText("Browse") and isSelectable()).performClick()
            compose.waitUntil(30_000, shown)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val file = File("../build/video-playback-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("books:root").captureToImage().toAwtImage(), "png", file)
    }

    @Test fun narrowLibraryAndDiscoverScreenshots() { show(400); screenshots("narrow") }
    @Test fun wideLibraryAndDiscoverScreenshots() { show(1180); screenshots("wide") }
}
