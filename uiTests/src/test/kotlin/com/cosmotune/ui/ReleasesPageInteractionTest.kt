package com.cosmotune.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.cosmotune.listening.ListeningStore
import com.cosmotune.listening.ReleaseNotice
import com.cosmotune.ui.listening.CosmoReleasesPage
import com.cosmotune.ui.theme.*
import java.io.File
import java.nio.file.Files
import org.junit.Rule
import org.junit.Test
import kotlin.test.*

/** Music's Releases tab over a real inbox file: the same store the listening tools use. */
class ReleasesPageInteractionTest {
    @get:Rule val compose = createComposeRule()
    private val file = File(Files.createTempDirectory("releases").toFile(), "listening.json")
    private val day = 24L * 60 * 60 * 1000
    private val now = java.time.LocalDate.of(2026, 11, 4).atStartOfDay(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli() + 3_600_000
    private var opened: Pair<String, String>? = null
    private var managed = 0

    private fun show() {
        compose.setContent {
            val colors = remember { CosmoColors::class.java.getConstructor(CosmoPaletteState::class.java)
                .newInstance(CosmoPaletteState(CosmoBrandPalettes.of(true), true)) }
            val type = remember { CosmoType::class.java.getConstructor(Float::class.javaPrimitiveType, FontFamily::class.java, FontFamily::class.java)
                .newInstance(360f, FontFamily.Default, FontFamily.Default) }
            MaterialTheme {
                CompositionLocalProvider(LocalCosmoColors provides colors, LocalCosmoType provides type, LocalReducedMotion provides true) {
                    Box(Modifier.size(360.dp, 800.dp)) {
                        CosmoReleasesPage(
                            file = file,
                            listState = rememberLazyListState(),
                            contentPadding = PaddingValues(),
                            onOpenRelease = { id, title -> opened = id to title },
                            onManage = { managed++ },
                            lookup = { emptyList() },
                            now = { now },
                        )
                    }
                }
            }
        }
    }

    private fun seed(vararg releases: ReleaseNotice) {
        val store = ListeningStore(file)
        store.watchArtist("UCfixture000000", "Fixture Artist")
        store.update { it.copy(inbox = releases.toList()) }
    }

    @Test fun withNobodyFollowedItAsksToFollowSomeone() {
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Follow artists").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Follow artists").performClick()
        assertEquals(1, managed)
        compose.onNodeWithTag("music:releases:check").assertDoesNotExist()
    }

    @Test fun releasesAreGroupedByWeekAndHidingSinglesLeavesTheRest() {
        seed(
            ReleaseNotice("MPREalbum", "UCfixture000000", "Fixture Artist", "An album", discoveredAt = now, single = false),
            ReleaseNotice("MPREsingle", "UCfixture000000", "Fixture Artist", "A single", discoveredAt = now - day * 7, single = true),
            ReleaseNotice("MPREold", "UCfixture000000", "Fixture Artist", "An old one", discoveredAt = now - day * 60),
        )
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("THIS WEEK").assertExists()
        compose.onNodeWithText("LAST WEEK").assertExists()
        compose.onNodeWithText("EARLIER").assertExists()
        compose.onNodeWithText("Album · Fixture Artist").assertExists()
        compose.onNodeWithText("Single · Fixture Artist").assertExists()

        compose.onNodeWithTag("music:releases:hide-singles").performClick()
        compose.onNodeWithText("A single").assertDoesNotExist()
        compose.onNodeWithText("LAST WEEK").assertDoesNotExist()
        compose.onNodeWithText("An album").assertExists()
        compose.onNodeWithText("An old one").assertExists()
    }

    @Test fun openingAReleaseMarksItReadAndOpensItsPage() {
        seed(ReleaseNotice("MPREalbum", "UCfixture000000", "Fixture Artist", "An album", discoveredAt = now))
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("An album").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("An album").performClick()
        compose.waitUntil(5_000) { opened != null }
        assertEquals("MPREalbum" to "An album", opened)
        assertTrue(ListeningStore(file).state.value.inbox.single().read, "the store the tools read says it is read")
    }

    @Test fun followedArtistsOpensTheToolsAndACheckFinishesWithoutFailure() {
        seed()
        show()
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("music:releases:manage").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Nothing new yet").assertExists()
        compose.onNodeWithTag("music:releases:manage").performClick()
        assertEquals(1, managed)
        compose.onNodeWithTag("music:releases:check").performClick()
        compose.waitUntil(5_000) { compose.onAllNodesWithText("Check now").fetchSemanticsNodes().isNotEmpty() }
        assertNotNull(ListeningStore(file).state.value.artists.single().checkedAt, "the check reached the store")
    }
}
