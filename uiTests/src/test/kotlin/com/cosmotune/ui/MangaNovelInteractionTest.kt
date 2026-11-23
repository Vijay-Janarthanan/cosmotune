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
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MangaNovelInteractionTest {
    val compose = createComposeRule()
    val temporary = TemporaryFolder()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(RetryOnComposeHarnessRace()).around(temporary).around(compose)

    private val words = MangaNovelChapter("Chapter one", (1..60).map { MangaNovelBlock(MangaNovelBlock.Kind.PARAGRAPH, "Paragraph $it of a quiet evening by the sea, long enough to wrap.") })

    private fun show(): Pair<MangaRepository, MangaBook> {
        val store = MangaStore(temporary.newFolder())
        val repository = MangaRepository(store) { config, _ -> object : MangaSource, MangaTextSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage(emptyList(), null)
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
            override suspend fun chapterText(chapter: MangaChapter) = words
        } }
        val book = MangaBook("novel:book", "fixture:novel", "A sea novel", chapters = listOf(MangaChapter("novel:one", "novel:book", "fixture:novel", "Chapter one", "one", 1f, text = true)))
        runBlocking { store.source(MangaSourceConfig("fixture:novel", "mihon", "Novel fixture")); store.save(book) }
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides rememberCosmoType()) {
                    Box(Modifier.width(360.dp).fillMaxHeight()) { MangaApp(repository = repository) }
                }
            }
        }
        return repository to book
    }

    @Test fun aTextChapterOpensInTheTextReaderAndKeepsItsPlaceInPages() {
        val (repository, book) = show()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("A sea novel").fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodesWithText("A sea novel").onFirst().performClick()
        // The chapter's own row opens it.
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("Chapter one", substring = true) and hasClickAction()).fetchSemanticsNodes().isNotEmpty() }
        compose.onAllNodes(hasText("Chapter one", substring = true) and hasClickAction()).onLast().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Paragraph 1 of", substring = true).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manga:novel:scroll-column").assertExists()
        compose.onNodeWithTag("manga:novel:scroll-column").performTouchInput { swipeUp() }
        compose.waitUntil(5_000) { (repository.store.state.value.progress[book.id]?.page ?: 0) > 0 }
        val place = repository.store.state.value.progress.getValue(book.id)
        assertEquals(60, place.pageCount)
        // The card comes up with a tap on the middle; every icon button has its name.
        compose.onNodeWithTag("manga:novel:scroll-column").performTouchInput { click(center) }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("manga:novel:card").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Text settings").performClick()
        compose.onNodeWithTag("manga:novel:paged").performClick()
        compose.waitUntil(5_000) { repository.store.state.value.novelStyle.paged }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("manga:novel:pages").fetchSemanticsNodes().isNotEmpty() }
        // Turning to pages stays on the same words: the page shown holds the paragraph the column was at.
        compose.waitForIdle()
        assertTrue(compose.onAllNodesWithText("Paragraph ${place.page + 1} of", substring = true).fetchSemanticsNodes().isNotEmpty())
        assertEquals(MangaReaderMode.PAGES_RTL, repository.store.state.value.readerMode, "a text chapter leaves the manga default alone")
    }
}
