package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.ui.theme.*
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import javax.imageio.ImageIO
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MangaInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun show(fontScale: Float = 1f, pageCount: Int = 3, imageHeight: Int = 240,
        mode: MangaReaderMode = MangaReaderMode.PAGES_RTL, repositoryOverride: MangaRepository? = null): Pair<MangaRepository, MangaBook> {
        val repository = repositoryOverride ?: MangaRepository(MangaStore(temporary.newFolder()))
        val book = runBlocking {
            repository.store.update { it.copy(readerMode = mode) }
            val image = BufferedImage(160, imageHeight, BufferedImage.TYPE_INT_RGB)
            image.createGraphics().let { graphics ->
                graphics.color = Color(35, 40, 60); graphics.fillRect(0, 0, 160, 240)
                graphics.color = Color(240, 180, 130); graphics.drawString("A local comic", 25, 80); graphics.dispose()
            }
            val bytes = ByteArrayOutputStream().use { output ->
                ZipOutputStream(output).use { zip -> repeat(pageCount) { index ->
                    zip.putNextEntry(ZipEntry("${index + 1}.png")); ImageIO.write(image, "png", zip); zip.closeEntry()
                } }
                output.toByteArray()
            }
            repository.store.archives.import("Evening story.cbz") { bytes.inputStream() }.also { repository.store.save(it) }
        }
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(400.dp).fillMaxHeight()) { MangaApp(repository = repository) }
                }
            }
        }
        return repository to book
    }

    @Test fun globalSearchKeepsWorkingSourcesAndOpensTheSeriesOnItsOwnSource() {
        val calls = java.util.concurrent.CopyOnWriteArrayList<String>()
        val store = MangaStore(temporary.newFolder())
        val repository = MangaRepository(store) { config, _ -> object : MangaSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
                calls += config.id
                if (config.id == "fixture:failed") error("Unavailable fixture")
                return MangaBrowsePage(listOf(MangaBook(config.id + ":book", config.id, "A searched series")), null)
            }
            override suspend fun details(book: MangaBook) = book.copy(chapters = listOf(MangaChapter("chapter", book.id, config.id, "Chapter one", "chapter")))
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        runBlocking {
            store.source(MangaSourceConfig("fixture:working", "mihon", "Working source"))
            store.source(MangaSourceConfig("fixture:failed", "mihon", "Unavailable source"))
            store.source(MangaSourceConfig("fixture:disabled", "mihon", "Disabled source", enabled = false))
        }
        show(repositoryOverride = repository)
        compose.onNodeWithText("My library", substring = false).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("All enabled sources").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("All enabled sources").performClick()
        compose.onNodeWithText("Search manga").performTextInput("Tamil")
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A searched series").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Working source", substring = false).assertExists()
        compose.onNodeWithText("1 sources unavailable.", substring = true).assertExists()
        assertFalse(calls.contains("fixture:disabled"))
        compose.onNodeWithText("A searched series").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Chapter one").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test fun readingControlsPersistThePageAndReopenAtTheSamePosition() {
        val (repository, book) = show()
        compose.onNodeWithText("Evening story").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10000) { repository.store.state.value.progress[book.id] != null }
        compose.onNodeWithText("Next", substring = false).performClick()
        compose.waitUntil(10000) { repository.store.state.value.progress[book.id]?.page == 1 }
        screenshot("reader")
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10000) { compose.onAllNodesWithText("2 / 3").fetchSemanticsNodes().isNotEmpty() }
        val restored = MangaStore(repository.store.root)
        assertEquals(1, restored.state.value.progress[book.id]?.page, restored.recoveryMessage ?: "Library file missing")
    }

    @Test fun narrowLargeTextLibraryKeepsImportAndSourceControlsReachable() {
        show(1.5f)
        compose.onNodeWithText("Import comic").assertIsDisplayed()
        compose.onNodeWithText("Image folder").assertIsDisplayed()
        compose.onNodeWithText("Sources").assertIsDisplayed()
        compose.onNodeWithText("Evening story").assertIsDisplayed()
        screenshot("library-narrow-large-text")
    }

    @Test fun longWebtoonFinishesOnlyAtItsBottomAndRestoresItsScrollOffset() {
        val (repository, book) = show(pageCount = 1, imageHeight = 2_200, mode = MangaReaderMode.WEBTOON)
        compose.onNodeWithText("Evening story").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithContentDescription("Manga page").fetchSemanticsNodes().isNotEmpty() &&
                compose.onNodeWithContentDescription("Manga page").getUnclippedBoundsInRoot().let { it.bottom - it.top > 1_000.dp } &&
                repository.store.state.value.progress[book.id] != null
        }
        assertFalse(repository.store.state.value.progress.getValue(book.id).finished)
        assertFalse(book.chapters.single().id in repository.store.state.value.readChapters)
        compose.onNodeWithText("Next", substring = false).performClick()
        compose.waitUntil(10_000) { (repository.store.state.value.progress[book.id]?.pageOffsetPx ?: 0) > 0 }
        val offset = repository.store.state.value.progress.getValue(book.id).pageOffsetPx
        assertFalse(repository.store.state.value.progress.getValue(book.id).finished)
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10_000) {
            compose.onAllNodesWithTag("manga:webtoon").fetchSemanticsNodes().singleOrNull()?.config
                ?.get(SemanticsProperties.VerticalScrollAxisRange)?.value?.invoke()?.let { it > 0f } == true &&
                repository.store.state.value.progress[book.id]?.pageOffsetPx == offset
        }
        compose.onNodeWithTag("manga:webtoon").performScrollToNode(hasTestTag("manga:chapter-end"))
        compose.waitUntil(10_000) { repository.store.state.value.progress[book.id]?.finished == true }
        assertTrue(book.chapters.single().id in MangaStore(repository.store.root).state.value.readChapters)
        compose.onNodeWithText("Finish", substring = false).assertIsDisplayed()
    }

    private fun zoomOf(node: SemanticsNodeInteraction) = node.fetchSemanticsNode().config.getOrElseNullable(SemanticsProperties.StateDescription) { null }

    @Test fun fitWidthScrollsATallPageAndButtonsAndKeysZoomTheCurrentPage() {
        val (repository, _) = show(pageCount = 3, imageHeight = 2_200, mode = MangaReaderMode.PAGES_LTR)
        compose.onNodeWithText("Evening story").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Manga page").fetchSemanticsNodes().isNotEmpty() }
        val reader = compose.onNodeWithTag("manga:reader").getUnclippedBoundsInRoot()
        val fitted = compose.onAllNodesWithContentDescription("Manga page").onFirst().getUnclippedBoundsInRoot()
        assertTrue(fitted.bottom - fitted.top <= reader.bottom - reader.top + 1.dp, "fit page keeps the whole page on screen")
        compose.onNodeWithText("Fit page").performClick()
        compose.waitUntil(10_000) { repository.store.state.value.readerFit == MangaReaderFit.WIDTH }
        compose.waitUntil(10_000) { compose.onAllNodesWithContentDescription("Manga page").onFirst().getUnclippedBoundsInRoot().let { it.bottom - it.top > 2_000.dp } }
        compose.onNodeWithText("Fit width").assertExists()

        val page = compose.onAllNodesWithContentDescription("Manga page").onFirst()
        assertEquals("Zoom 100%", zoomOf(page))
        compose.onNodeWithContentDescription("Zoom in").performClick()
        compose.waitUntil(5_000) { zoomOf(page) == "Zoom 125%" }
        compose.onNodeWithTag("manga:reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Equals) }
        compose.waitUntil(5_000) { zoomOf(page) == "Zoom 156%" }
        compose.onNodeWithContentDescription("Zoom out").performClick()
        compose.waitUntil(5_000) { zoomOf(page) == "Zoom 125%" }
        compose.onNodeWithTag("manga:reader").performKeyInput { pressKey(androidx.compose.ui.input.key.Key.Zero) }
        compose.waitUntil(5_000) { zoomOf(page) == "Zoom 100%" }
        compose.onNodeWithContentDescription("Zoom out").performClick()
        compose.waitForIdle()
        assertEquals("Zoom 100%", zoomOf(page), "zoom never goes below the fitted size")
        assertEquals(MangaReaderFit.WIDTH, MangaStore(repository.store.root).state.value.readerFit, "the fit survives a restart")
    }

    @Test fun theNextTwoPagesAreFetchedAheadOfTheReaderAndNoMore() {
        val (repository, _) = show(pageCount = 5)
        val cache = File(repository.store.root, "cache/pages")
        fun cached() = cache.listFiles().orEmpty().count { it.isFile && it.extension != "part" }
        compose.onNodeWithText("Evening story").performClick()
        compose.onNodeWithText("Read").performClick()
        compose.waitUntil(10_000) { cached() == 3 }
        Thread.sleep(500); compose.waitForIdle()
        assertTrue(cached() == 3, "the current page and the next two only, not ${cached()}")
        compose.onNodeWithText("Next", substring = false).performClick()
        compose.waitUntil(10_000) { cached() == 4 }
    }

    private fun screenshot(name: String) {
        compose.waitForIdle()
        val file = File("build/reports/manga-ui/$name.png").apply { parentFile.mkdirs() }
        ImageIO.write(compose.onNodeWithTag("manga:root").captureToImage().toAwtImage(), "png", file)
    }
}
