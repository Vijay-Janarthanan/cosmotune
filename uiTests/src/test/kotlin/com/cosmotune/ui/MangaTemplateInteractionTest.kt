package com.cosmotune.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.cosmotune.manga.data.*
import com.cosmotune.manga.ui.MangaTemplateControls
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaTemplateInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()
    @Test fun customWebsiteCanBeAddedEditedDisabledAndRemovedWithoutChangingItsIdentity() {
        val repository = MangaRepository(MangaStore(temporary.newFolder()))
        compose.setContent { MaterialTheme { Column { MangaTemplateControls(repository) } } }
        compose.onNodeWithText("Custom websites and templates").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Add website").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Add website").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-template:name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manga-template:name").performScrollTo().performTextInput("My site")
        compose.onNodeWithTag("manga-template:url").performScrollTo().performTextInput("https://reader.example")
        compose.onNodeWithText("Save website").performClick()
        compose.waitUntil(10_000) { repository.store.state.value.sources.size == 1 }
        val id = repository.store.state.value.sources.single().id
        assertEquals("template", repository.store.state.value.sources.single().kind)
        compose.onNodeWithText("Edit My site").performScrollTo().performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga-template:name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manga-template:name").performScrollTo().performTextReplacement("A renamed site")
        compose.onNodeWithText("Save website").performClick()
        compose.waitUntil(10_000) { repository.store.state.value.sources.single().name == "A renamed site" }
        assertEquals(id, repository.store.state.value.sources.single().id)
        compose.onNode(isToggleable()).performScrollTo().performClick()
        compose.waitUntil(10_000) { !repository.store.state.value.sources.single().enabled }
        compose.onNodeWithText("Remove A renamed site").performScrollTo().performClick()
        compose.waitUntil(10_000) { repository.store.state.value.sources.isEmpty() }
        assertTrue(MangaStore(repository.store.root).state.value.sources.isEmpty())
    }
}
