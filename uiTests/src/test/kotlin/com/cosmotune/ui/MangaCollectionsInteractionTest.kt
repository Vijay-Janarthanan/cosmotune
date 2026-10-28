package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
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

@OptIn(ExperimentalTestApi::class)
class MangaCollectionsInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    private fun waitText(text: String) = compose.waitUntil(10_000) { compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    @Test fun createAssignFilterRenameAndDeleteCollectionKeepsItsSeries() {
        val store = MangaStore(temporary.newFolder())
        runBlocking { store.save(MangaBook("one", "local", "One")); store.save(MangaBook("two", "local", "Two")) }
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java).newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent { MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
            CompositionLocalProvider(LocalCosmoColors provides colors) { Box(Modifier.width(850.dp).fillMaxHeight()) { MangaApp(repository = MangaRepository(store)) } }
        } }
        compose.onNodeWithTag("manga:collection:manage").performClick()
        compose.onNodeWithTag("manga:collection:name").performTextInput("Favourites")
        compose.onNodeWithTag("manga:collection:save").performClick()
        compose.waitUntil(5_000) { store.state.value.collections.size == 1 }
        val id = store.state.value.collections.single().id
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("One").performClick()
        compose.onNodeWithTag("manga:collection:assign").performClick()
        compose.onNodeWithTag("manga:collection:member:$id").performClick()
        compose.waitUntil(5_000) { "one" in store.state.value.collections.single().bookIds }
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Back").performClick()
        compose.onNodeWithTag("manga:collection:filter").performClick()
        compose.onNodeWithText("Favourites").performClick()
        compose.onNodeWithText("One").assertExists(); compose.onNodeWithText("Two").assertDoesNotExist()
        compose.onNodeWithTag("manga:collection:manage").performClick()
        compose.onNodeWithTag("manga:collection:rename:$id").performClick()
        compose.onNodeWithTag("manga:collection:name").performTextReplacement("Finished")
        compose.onNodeWithTag("manga:collection:save").performClick()
        compose.waitUntil(5_000) { store.state.value.collections.single().name == "Finished" }
        compose.onNodeWithTag("manga:collection:delete:$id").performClick()
        compose.onNodeWithText("Delete collection").performClick()
        compose.waitUntil(5_000) { store.state.value.collections.isEmpty() }
        compose.onNodeWithText("Done").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Two").fetchSemanticsNodes().isNotEmpty() }
        assertEquals(2, store.state.value.books.size)
    }
}
