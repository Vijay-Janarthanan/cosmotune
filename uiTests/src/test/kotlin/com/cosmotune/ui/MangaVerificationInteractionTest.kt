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
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.assertTrue

@OptIn(ExperimentalTestApi::class)
class MangaVerificationInteractionTest {
    @get:Rule val compose = createComposeRule()
    @get:Rule val temporary = TemporaryFolder()

    @Test fun explicitBrowserCompletionRetriesTheFailedSourceAndClosesThePrivateBrowser() {
        val store = MangaStore(temporary.newFolder())
        val config = MangaSourceConfig("fixture:verify", "mihon", "Verification source", baseUrl = "https://source.example/")
        runBlocking { store.source(config) }
        val repository = MangaRepository(store) { item, context -> object : MangaSource {
            override val config = item
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
                if (!context.verification.active(item.id)) throw MangaBrowserRequired("https://source.example/catalogue")
                return MangaBrowsePage(listOf(MangaBook("verified", item.id, "Verified catalogue")), null)
            }
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        val closed = AtomicBoolean()
        val previous = MangaVerificationWindows.launcher
        MangaVerificationWindows.launcher = { request -> object : MangaVerificationBrowser {
            override suspend fun complete() = MangaBrowserProof(request.address, "clearance=fixture", "FixtureBrowser/1")
            override fun close() { closed.set(true) }
        } }
        try {
            val colors = CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
                .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true))
            compose.setContent {
                MaterialTheme(colorScheme = CosmoBrandPalettes.of(true).toColorScheme(darkColorScheme())) {
                    CompositionLocalProvider(LocalCosmoColors provides colors) {
                        Box(Modifier.width(650.dp).fillMaxHeight()) { MangaApp(repository = repository) }
                    }
                }
            }
            compose.onNodeWithText("My library").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Verification source", substring = true).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("Verification source", substring = true).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("manga:verify:${config.id}").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithTag("manga:verify:${config.id}").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Use verified session").fetchSemanticsNodes().isNotEmpty() }
            compose.waitUntil(10_000) { compose.onNodeWithText("Use verified session").fetchSemanticsNode().config.contains(androidx.compose.ui.semantics.SemanticsProperties.Disabled).not() }
            compose.onNodeWithText("Use verified session").performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithText("Verified catalogue").fetchSemanticsNodes().isNotEmpty() }
            assertTrue(repository.context.verification.active(config.id))
            compose.waitUntil(5_000) { closed.get() }
        } finally { MangaVerificationWindows.launcher = previous }
    }
}
