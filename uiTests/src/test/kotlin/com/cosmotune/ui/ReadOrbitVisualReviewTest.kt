package com.cosmotune.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.reading.data.*
import com.cosmotune.reading.ui.ReadApp
import com.cosmotune.ui.blocks.ModeNav
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.LocalCosmoColors
import com.cosmotune.ui.theme.LocalCosmoType
import com.cosmotune.ui.theme.LocalReducedMotion
import com.cosmotune.ui.theme.rememberCosmoType
import com.cosmotune.ui.theme.toColorScheme
import com.cosmotune.ui.theme.toMaterialTypography
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * Manga and Read in Orbit (Canvas repo, design/cosmotune/batch-be/board.html, section D) on fixture libraries: the Library with the
 * series being read as the colour card, a series page, Updates, Browse, Sources, History and the reader's slim card, and for books
 * the Library, a book's page and the reader. Drawn at phone width (the page with its chips) and at the desktop's page width, in
 * the dark and the light palettes, under build/read-review.
 */
@OptIn(ExperimentalTestApi::class)
class ReadOrbitVisualReviewTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private class Size(val name: String, val width: Int, val height: Int)
    private val phone = Size("phone", 390, 844)
    private val wide = Size("wide", 1004, 780)

    private fun picture(title: String, top: java.awt.Color, bottom: java.awt.Color, width: Int = 300, height: Int = 450): BufferedImage {
        val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
        image.createGraphics().let { g ->
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            g.paint = GradientPaint(0f, 0f, top, 0f, height.toFloat(), bottom); g.fillRect(0, 0, width, height)
            g.color = java.awt.Color(255, 236, 226, 60); g.fillOval(width / 5, height / 6, width * 3 / 5, width * 3 / 5)
            g.color = java.awt.Color(250, 240, 230); g.font = Font("Serif", Font.BOLD, width / 9)
            title.split(" ").forEachIndexed { i, word -> g.drawString(word, width / 10, height * 2 / 3 + i * width / 8) }
            g.dispose()
        }
        return image
    }

    private fun comic(title: String, top: java.awt.Color, bottom: java.awt.Color, pages: Int): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip -> repeat(pages) { index ->
            zip.putNextEntry(ZipEntry("${index + 1}.png")); ImageIO.write(picture(if (index == 0) title else "Page ${index + 1}", top, bottom, 600, 900), "png", zip); zip.closeEntry()
        } }
        output.toByteArray()
    }

    /** A library: one online series half read with new chapters, three local comics (one in progress), and a source to browse. */
    private fun manga(): MangaRepository {
        val store = MangaStore(temporary.newFolder())
        val repository = MangaRepository(store) { config, _ -> object : MangaSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage((1..8).map { MangaBook("star:find:$it", config.id, listOf("Moon Harbour", "The Glass Orchard", "Kite Season", "Salt Road", "Night Ferry", "Paper Comets", "Tide Clock", "Lantern Street")[it - 1]) }, null)
            override suspend fun details(book: MangaBook) = store.state.value.books.find { it.id == book.id } ?: book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        runBlocking {
            store.source(MangaSourceConfig("fixture:star", "mihon", "Star Library", language = "en"))
            store.source(MangaSourceConfig("fixture:tamil", "mihon", "Kadhai Comics", language = "ta"))
            val titles = listOf("Departure", "The long dock", "Salt and starlight", "A quiet orbit", "Brass windows", "The parcel", "Comet weather", "Signal fires",
                "The comet road", "Night shift", "Two moons", "Return")
            val chapters = titles.mapIndexed { i, t -> MangaChapter("star:c${i + 1}", "star", "fixture:star", "Chapter ${i + 1} · $t", "c${i + 1}", (i + 1).toFloat(), "en",
                uploadDate = System.currentTimeMillis() - (12 - i) * 86_400_000L) }
            val star = MangaBook("star", "fixture:star", "Star Courier", authors = listOf("Anu Raman"), chapters = chapters, language = "en",
                description = "A courier who carries stranger things than starlight takes one last parcel across the comet road.",
                tags = listOf("Adventure", "Space", "Slice of life"), parserData = MangaParserBookData("https://example.org/star", state = "ONGOING"))
            store.save(star)
            chapters.take(8).forEach { store.markRead(it, true) }
            store.progress(MangaProgress("star", "star:c9", 5, 22))
            store.update { it.copy(newChapters = setOf("star:c11", "star:c12")) }
            val colours = listOf(
                Triple("Evening story", java.awt.Color(46, 58, 92), java.awt.Color(201, 162, 74)),
                Triple("Harbour lights", java.awt.Color(31, 74, 58), java.awt.Color(122, 208, 160)),
                Triple("Paper kites", java.awt.Color(92, 42, 42), java.awt.Color(224, 112, 64)),
            )
            colours.forEachIndexed { i, (title, top, bottom) ->
                val bytes = comic(title, top, bottom, 3)
                val book = store.archives.import("$title.cbz") { bytes.inputStream() }
                store.save(book)
                if (i == 1) store.progress(MangaProgress(book.id, book.chapters.first().id, 1, 3, updatedAtMs = System.currentTimeMillis() - 3_600_000))
            }
        }
        return repository
    }

    private fun page(dark: Boolean, size: Size, content: @androidx.compose.runtime.Composable () -> Unit) {
        val palette = CosmoPaletteState(com.cosmotune.theme.OnePalette.of(dark, false), dark)
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(palette) }
            val type = rememberCosmoType()
            MaterialTheme(colorScheme = colors.palette.toColorScheme(if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()),
                typography = type.toMaterialTypography(MaterialTheme.typography)) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true,
                    com.cosmotune.manga.ui.LocalReadModeSwitcher provides { com.cosmotune.ui.kit.KitModePill(com.cosmotune.design.AppMode.MANGA, onClick = {}) }) {
                    Box(Modifier.size(size.width.dp, size.height.dp).testTag("read:review").background(Color(colors.palette.backgroundBottom))) { content() }
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        val file = File("../build/read-review/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("read:review").captureToImage().toAwtImage(), "png", file)
    }

    private fun waitFor(text: String) = compose.waitUntil(15_000) { compose.onAllNodesWithText(text, substring = true).fetchSemanticsNodes().isNotEmpty() }

    private fun each(block: (dark: Boolean, size: Size, tag: String) -> Unit) {
        for (dark in listOf(true, false)) for (size in listOf(phone, wide)) block(dark, size, "${size.name}-${if (dark) "dark" else "light"}")
    }

    private fun tab(name: String) { compose.runOnIdle { ModeNav.mangaTab.value = name }; compose.waitForIdle() }

    @Test fun mangaPagesInDarkAndLight() {
        each { dark, size, tag ->
            val repository = manga()
            ModeNav.mangaTab.value = "Library"
            page(dark, size) { MangaApp(repository = repository, showNavigation = size === phone) }
            waitFor("Star Courier")
            capture("manga-library-$tag")
            compose.onNodeWithTag("manga:hero").performClick()
            waitFor("Salt and starlight")
            capture("manga-series-$tag")
            compose.onAllNodesWithContentDescription("Back").onFirst().performClick()
            tab("Updates"); waitFor("Two moons"); capture("manga-updates-$tag")
            tab("Browse"); waitFor("Moon Harbour"); capture("manga-browse-$tag")
            tab("Sources"); capture("manga-sources-$tag")
            tab("History"); waitFor("Harbour lights"); capture("manga-history-$tag")
            // A history row opens the reader where that series was left.
            compose.onAllNodesWithText("Harbour lights").onFirst().performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithContentDescription("Manga page").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manga:reader").performTouchInput { click(center) }
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("manga:reader:card").fetchSemanticsNodes().isNotEmpty() }
            capture("manga-reader-$tag")
        }
    }

    /** A one-page PDF with a paragraph, written by hand (PDFBox rebuilds the cross-reference table). */
    private fun pdf(file: File) {
        val text = listOf("The courier set the parcel on the table and listened.", "Somewhere above the station a comet was turning,", "slow as a door, and every window in the corridor",
            "had gone the colour of brass.")
        val stream = "BT /F1 15 Tf 48 760 Td 22 TL " + text.joinToString(" ") { "($it) Tj T*" } + " ET"
        file.writeText("%PDF-1.4\n1 0 obj << /Type /Catalog /Pages 2 0 R >> endobj\n2 0 obj << /Type /Pages /Kids [3 0 R] /Count 1 >> endobj\n" +
            "3 0 obj << /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] /Contents 4 0 R /Resources << /Font << /F1 5 0 R >> >> >> endobj\n" +
            "4 0 obj << /Length ${stream.length} >> stream\n$stream\nendstream endobj\n5 0 obj << /Type /Font /Subtype /Type1 /BaseFont /Times-Roman >> endobj\n" +
            "trailer << /Root 1 0 R >>\n%%EOF\n")
    }

    private fun books(): ReadingRepository {
        val repository = ReadingRepository(temporary.newFolder(), client = okhttp3.OkHttpClient.Builder().addInterceptor { chain ->
            okhttp3.Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(503).message("Offline").body("".toResponseBody(null)).build()
        }.build())
        val fixtures = temporary.newFolder()
        fun cover(name: String, title: String, top: java.awt.Color, bottom: java.awt.Color) = File(fixtures, name).also { ImageIO.write(picture(title, top, bottom), "png", it) }.path
        val notes = File(fixtures, "notes.pdf").also { pdf(it) }
        val atlas = ReadingBook("atlas", "The illustrated atlas", listOf("Mira Sen"), ReadingFormat.EPUB, "atlas.epub", cover("atlas.png", "Illustrated atlas", java.awt.Color(29, 56, 53), java.awt.Color(183, 143, 86)),
            description = "Maps of places that only exist on the evening ferry, drawn by a cartographer who never left the harbour.")
        val field = runBlocking { repository.importFile("notes.pdf") { notes.inputStream() } }
            .copy(title = "Field notes", authors = listOf("Ravi Patel"), coverPath = cover("notes.png", "Field notes", java.awt.Color(70, 44, 41), java.awt.Color(187, 157, 124)))
        val courier = ReadingBook("courier", "The comet road", listOf("Anu Raman"), ReadingFormat.EPUB, "courier.epub", cover("courier.png", "Comet road", java.awt.Color(62, 31, 82), java.awt.Color(192, 112, 224)))
        runBlocking {
            repository.store.update { it.copy(books = listOf(atlas, field, courier), progress = mapOf(
                "atlas" to ReadingProgress("atlas", ReadingLocator(progression = .35)),
                field.id to ReadingProgress(field.id, ReadingLocator(progression = .1), updatedAtMs = System.currentTimeMillis() - 7_200_000),
            )) }
            repository.saveBookmark("atlas", "Chapter 3 · 35%", ReadingLocator(progression = .35))
        }
        return repository
    }

    @Test fun readPagesInDarkAndLight() {
        each { dark, size, tag ->
            val repository = books()
            ModeNav.mangaTab.value = "Library"
            page(dark, size) { ReadApp(repository = repository, showNavigation = size === phone) }
            waitFor("The illustrated atlas")
            capture("read-library-$tag")
            compose.onNodeWithTag("books:hero").performClick()
            waitFor("Mark finished")
            capture("read-book-$tag")
            compose.onAllNodesWithContentDescription("Back").onFirst().performClick()
            tab("Bookmarks"); waitFor("Chapter 3"); capture("read-bookmarks-$tag")
            tab("Library"); waitFor("Field notes")
            compose.onAllNodesWithText("Field notes").onFirst().performClick()
            waitFor("Mark finished")
            compose.onNodeWithTag("orbit:card:continue").performClick()
            compose.waitUntil(15_000) { compose.onAllNodesWithTag("books:show-tools").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("books:show-tools").performClick()
            compose.waitUntil(5_000) { compose.onAllNodesWithTag("books:reader-card").fetchSemanticsNodes().isNotEmpty() }
            capture("read-reader-$tag")
        }
    }
}
