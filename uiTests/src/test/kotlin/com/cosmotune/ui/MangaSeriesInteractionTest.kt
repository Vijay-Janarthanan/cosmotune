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
import org.junit.rules.TemporaryFolder
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** MG-04: a multi-language source's catalogue language, and a series page's edition, order and unread filters. */
@OptIn(ExperimentalTestApi::class)
class MangaSeriesInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    private fun fixture(store: MangaStore) = MangaRepository(store) { config, _ -> object : MangaSource {
        override val config = config
        private val language get() = config.settings[MANGA_LANGUAGE_SETTING] ?: "default"
        override suspend fun languages() = listOf("en", "ta")
        override suspend fun browse(query: String, offset: Int) =
            MangaBrowsePage(listOf(MangaBook("${config.id}:book:$language", config.id, "Series in $language")), null)
        override suspend fun details(book: MangaBook) = book.copy(chapters = listOf(
            MangaChapter("${book.id}:c1en", book.id, config.id, "Chapter 1 English", "1", 1f, "English"),
            MangaChapter("${book.id}:c2en", book.id, config.id, "Chapter 2 English", "2", 2f, "English"),
            MangaChapter("${book.id}:c1ta", book.id, config.id, "Chapter 1 Tamil", "1t", 1f, "Tamil"),
        ))
        override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
    } }

    private fun show(repository: MangaRepository) {
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                CompositionLocalProvider(LocalCosmoColors provides colors) {
                    Box(Modifier.width(600.dp).fillMaxHeight()) { MangaApp(repository = repository) }
                }
            }
        }
    }

    private fun waitText(text: String, substring: Boolean = false) =
        compose.waitUntil(10_000) { compose.onAllNodesWithText(text, substring = substring).fetchSemanticsNodes().isNotEmpty() }

    @Test fun catalogueLanguageReloadsTheSourceAndSeriesFiltersEditionsOrderAndUnread() {
        val store = MangaStore(temporary.newFolder())
        runBlocking { store.source(MangaSourceConfig("fixture:multi", "mihon", "Multi source")) }
        val repository = fixture(store)
        show(repository)
        compose.onNodeWithText("My library").performClick()
        waitText("Multi source", substring = true)
        compose.onNodeWithText("Multi source", substring = true).performClick()
        waitText("Series in default")
        compose.onNodeWithTag("manga:catalogue:language").assertTextContains("Source default", substring = true).performClick()
        waitText("(ta)", substring = true)
        compose.onNodeWithText("(ta)", substring = true).performClick()
        waitText("Series in ta")
        assertEquals("ta", store.state.value.sources.single().settings[MANGA_LANGUAGE_SETTING], "the choice is saved with the source")
        compose.onNodeWithText("Series in default").assertDoesNotExist()

        compose.onNodeWithText("Series in ta").performClick()
        waitText("Chapter 1 Tamil")
        compose.onNodeWithText("3 of 3 chapters · 3 unread").assertExists()
        compose.onNode(hasText("Tamil") and hasAnyAncestor(hasTestTag("manga:editions"))).performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Chapter 2 English").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("Chapter 1 Tamil").assertExists()
        compose.onNodeWithText("1 of 3 chapters · 3 unread").assertExists()
        compose.onNodeWithText("All editions").performClick()
        waitText("Chapter 2 English")

        // Mark the first English chapter read, then hide read chapters.
        compose.onAllNodesWithText("Mark read")[0].performClick()
        compose.waitUntil(5_000) { "fixture:multi:book:ta:c1en" in store.state.value.readChapters }
        compose.onNodeWithText("Unread only").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Chapter 1 English").fetchSemanticsNodes().isEmpty() }
        compose.onNodeWithText("2 of 3 chapters · 2 unread").assertExists()

        // Newest first puts chapter 2 above chapter 1.
        compose.onNodeWithText("Oldest first").performClick()
        waitText("Newest first")
        val newer = compose.onNodeWithText("Chapter 2 English").getUnclippedBoundsInRoot().top
        val older = compose.onNodeWithText("Chapter 1 Tamil").getUnclippedBoundsInRoot().top
        assertTrue(newer < older, "newest first lists chapter 2 above chapter 1")
    }
}
