package com.cosmotune.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.cosmotune.manga.data.KotatsuSource
import com.cosmotune.manga.data.MangaRepository
import com.cosmotune.manga.data.MangaStore
import com.cosmotune.manga.ui.MangaApp
import com.cosmotune.ui.theme.CosmoColors
import com.cosmotune.ui.theme.CosmoPaletteState
import com.cosmotune.ui.theme.CosmoBrandPalettes
import com.cosmotune.ui.theme.LocalCosmoColors
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

class MangaSourceManagementInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun largeTextSourceManagementKeepsFiltersAndNamedSwitchesReachable() {
        val repository = MangaRepository(MangaStore(temporary.newFolder()))
        val source = KotatsuSource.catalogue().first { it.title == "MANGA Plus English" && it.language == "en" }
        val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
            .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
        compose.setContent {
            CompositionLocalProvider(LocalCosmoColors provides colors, LocalDensity provides Density(1f, 1.5f)) {
                MaterialTheme { Box(Modifier.requiredWidth(400.dp)) { MangaApp(repository = repository) } }
            }
        }
        compose.onNodeWithText("Sources", useUnmergedTree = true).performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga:sources:list").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("manga:sources:query").performScrollTo().performTextInput("MANGA Plus")
        compose.onNodeWithTag("manga:sources:language").performScrollTo().performTextInput("en")
        compose.onNodeWithTag("manga:sources:list").performScrollToNode(hasTestTag("manga:source:${source.id}"))
        compose.onNodeWithTag("manga:source:${source.id}").assertContentDescriptionEquals("MANGA Plus English (en)").assertIsToggleable().performClick()
        compose.waitUntil(10_000) { repository.store.state.value.sources.any { it.id == source.id && it.enabled } }
        compose.onNodeWithTag("manga:source:${source.id}").assertIsOn()
        compose.onNodeWithText("Done").assertIsDisplayed().performClick()
        assertTrue(MangaStore(repository.store.root).state.value.sources.any { it.id == source.id && it.enabled })
    }
}
