package com.cosmotune.manga.data

import java.io.File
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaSourcePolicyTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun namesIdsAndNormalizedSpellingAreExcludedFromTheCatalogue() {
        listOf("HENTai", "provider.heN-t", "ＨＥＮＴ", "He\u0301n t").forEach { assertTrue(MangaSourcePolicy.blocked(it), it) }
        assertFalse(MangaSourcePolicy.blocked("MANGA Plus English", "komga:home"))
        assertTrue(KotatsuSource.catalogue().none { MangaSourcePolicy.blocked(it.id, it.title) })
    }

    @Test fun restoringOldInstalledSourcesClearsActiveNavigationAndPreservesReadingData() = runBlocking<Unit> {
        val folder = temporary.newFolder()
        val bad = MangaSourceConfig("numeric:42", "mihon", "Hent fixture")
        val good = MangaSourceConfig("fixture", "mihon", "Reader source")
        val chapter = MangaChapter("chapter", "book", bad.id, "Chapter", "/chapter")
        val book = MangaBook("book", bad.id, "Saved story", chapters = listOf(chapter), addedAtMs = 1_000L)
        val progress = MangaProgress(book.id, chapter.id, 1, 4, updatedAtMs = 2_000L)
        val page = File(folder, "owned-page").apply { writeText("offline") }
        val original = MangaLibraryState(sources = listOf(bad, good), books = listOf(book),
            activeBookId = book.id, activeChapterId = chapter.id, progress = mapOf(book.id to progress),
            collections = listOf(MangaCollection("saved", "Saved", setOf(book.id))),
            downloads = mapOf(chapter.id to listOf(MangaPage("page", localFile = page.path))))
        File(folder, "library.json").writeText(Json.encodeToString(original))
        val store = MangaStore(folder)
        assertEquals(listOf(good), store.state.value.sources)
        assertNull(store.state.value.activeBookId)
        assertNull(store.state.value.activeChapterId)
        assertEquals(original.books, store.state.value.books)
        assertEquals(original.progress, store.state.value.progress)
        assertEquals(original.collections, store.state.value.collections)
        assertEquals(original.downloads, store.state.value.downloads)
        assertTrue(page.exists())
        assertFailsWith<IllegalArgumentException> { store.source(bad) }
        assertFailsWith<IllegalArgumentException> { store.installExtension("org.fixture.hent", listOf(good)) }
        assertFailsWith<IllegalArgumentException> { store.installExtension("org.fixture.safe", listOf(bad)) }
        store.update { it.copy(sources = it.sources + bad) }
        assertEquals(listOf(good), MangaStore(folder).state.value.sources)
        assertFailsWith<IllegalStateException> { MangaRepository(store).browse(bad.id, "", 0) }
    }

    @Test fun globalSearchNeverInvokesExcludedOrDisabledSources() = runBlocking<Unit> {
        val calls = mutableListOf<String>()
        val configs = listOf(MangaSourceConfig("good", name = "Good"),
            MangaSourceConfig("bad", name = "HENT provider"), MangaSourceConfig("disabled", name = "Disabled", enabled = false))
        val result = mangaGlobalSearch(configs, "story") { config, _ ->
            calls += config.id; MangaBrowsePage(emptyList(), null)
        }.last()
        assertEquals(listOf("good"), calls)
        assertEquals(1, result.total)
    }

    @Test fun removingTheLastSourceNeverReinstallsTheDiscoveryDefaultAcrossRestart() = runBlocking<Unit> {
        val folder = temporary.newFolder()
        val store = MangaStore(folder)
        val default = MangaSourceConfig("fixture:default", "mihon", "Official catalogue")
        var lookups = 0
        store.initializeDiscoverySource { lookups++; default }
        assertEquals(listOf(default), store.state.value.sources)
        assertTrue(store.state.value.sourceSetupConsidered)
        store.update { it.copy(sources = emptyList()) }
        store.initializeDiscoverySource { lookups++; default }
        val restored = MangaStore(folder)
        restored.initializeDiscoverySource { lookups++; default }
        assertEquals(1, lookups)
        assertTrue(restored.state.value.sources.isEmpty())
        assertTrue(restored.state.value.sourceSetupConsidered)
    }

    @Test fun existingDisabledSourcesAndAnUnavailableDefaultStillCountAsSetupConsidered() = runBlocking<Unit> {
        val folder = temporary.newFolder()
        val disabled = MangaSourceConfig("fixture:disabled", "mihon", "Disabled catalogue", enabled = false)
        // An old library has no explicit marker; its source list is the migration evidence.
        File(folder, "library.json").writeText(Json.encodeToString(MangaLibraryState(sources = listOf(disabled))))
        val store = MangaStore(folder)
        store.initializeDiscoverySource { error("A configured disabled source must not be replaced") }
        assertEquals(listOf(disabled), store.state.value.sources)
        assertTrue(store.state.value.sourceSetupConsidered)
        val fresh = MangaStore(temporary.newFolder())
        fresh.initializeDiscoverySource { null }
        MangaStore(fresh.root).initializeDiscoverySource { error("An absent pinned default is considered only once") }
        assertTrue(fresh.state.value.sources.isEmpty())
    }
}
