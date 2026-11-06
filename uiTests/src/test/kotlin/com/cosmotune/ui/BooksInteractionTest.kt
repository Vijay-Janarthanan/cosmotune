package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.reading.data.*
import com.cosmotune.reading.ui.ReadApp
import com.cosmotune.ui.theme.*
import com.cosmotune.ui.blocks.ModeNav
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.RuleChain
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class BooksInteractionTest {
    private val compose = createComposeRule()
    private val temporary = TemporaryFolder()
    @get:Rule val rules: RuleChain = RuleChain.outerRule(RetryOnComposeHarnessRace()).around(temporary).around(compose)

    private fun library(fontScale: Float = 1f): ReadingRepository {
        val repository = ReadingRepository(temporary.newFolder())
        val first = ReadingBook("atlas", "The illustrated atlas", listOf("Mira Sen"), ReadingFormat.EPUB, File(repository.root, "atlas.epub").path)
        val second = ReadingBook("notes", "Field notes", listOf("Ravi Patel"), ReadingFormat.PDF, File(repository.root, "notes.pdf").path)
        runBlocking {
            repository.store.update { it.copy(books = listOf(first, second), progress = mapOf(first.id to ReadingProgress(first.id, ReadingLocator(progression = .35)))) }
            repository.savePublication(ReadingPublication("openlibrary:/works/OL82563W", "Harry Potter and the Philosopher's Stone", listOf("J. K. Rowling"),
                links = listOf(ReadingLink("Open Library", "https://openlibrary.org/works/OL82563W", kind = ReadingLinkKind.DETAILS)), sourceId = "openlibrary"))
        }
        ModeNav.mangaTab.value = "Library"
        compose.setContent {
            val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
            MaterialTheme(colorScheme = darkColorScheme()) {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides rememberCosmoType(), LocalDensity provides Density(1f, fontScale)) {
                    Box(Modifier.width(400.dp).fillMaxHeight()) { ReadApp(repository) }
                }
            }
        }
        return repository
    }

    @Test fun continueAndFinishedShelvesUpdateTheSavedLibrary() {
        val repository = library()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNodeWithTag("books:shelf:Reading").performScrollTo().performClick()
        compose.onNodeWithText("The illustrated atlas", substring = false).assertExists()
        compose.onNodeWithText("Field notes", substring = false).assertDoesNotExist()
        // A book's actions are on its page.
        compose.onNodeWithText("The illustrated atlas", substring = false).performClick()
        compose.onNodeWithText("Mark finished", substring = false).performScrollTo().performClick()
        compose.waitUntil { repository.state.value.finished.contains("atlas") }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Start your next chapter").assertExists()
        compose.onNodeWithTag("books:shelf:Finished").performScrollTo().performClick()
        compose.onNodeWithText("The illustrated atlas", substring = false).assertExists().performClick()
        compose.onNodeWithText("Mark unfinished").performScrollTo().performClick()
        compose.waitUntil { repository.state.value.finished.isEmpty() }
        assertTrue(ReadingRepository(repository.root).state.value.finished.isEmpty())
    }

    @Test fun commercialSavedBookHasSourceDetailsWithoutAnInventedDownload() {
        val repository = library()
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNodeWithTag("books:shelf:Saved").performScrollTo().performClick()
        compose.onNodeWithText("Harry Potter and the Philosopher's Stone", substring = false).performScrollTo().performClick()
        compose.onNodeWithTag("books:details").assertExists()
        compose.onNodeWithText("Download EPUB", substring = false).assertDoesNotExist()
        compose.onNodeWithText("Download PDF", substring = false).assertDoesNotExist()
        compose.onNode(hasText("Open Library", substring = false) and hasClickAction()).performScrollTo().assertExists()
        compose.onNodeWithTag("books:save").performScrollTo().performClick()
        compose.waitUntil { repository.state.value.saved.isEmpty() }
        compose.onNodeWithContentDescription("Back to books").performClick()
        compose.onNodeWithText("Save something for later").assertExists()
    }

    @Test fun largeTextKeepsLibrarySearchAndShelvesUsable() {
        library(fontScale = 2f)
        compose.onNode(hasText("Library") and isSelectable()).performClick()
        compose.onNode(hasSetTextAction() and hasAnyAncestor(hasTestTag("books:library-search"))).performTextInput("Ravi")
        compose.onNodeWithText("Field notes", substring = false).assertExists()
        compose.onNodeWithText("The illustrated atlas", substring = false).assertDoesNotExist()
        compose.onNodeWithTag("books:shelf:Downloaded").performScrollTo().performClick()
        compose.onNodeWithText("Field notes", substring = false).assertExists()
        compose.onNodeWithTag("books:import").assertExists()
    }
}
