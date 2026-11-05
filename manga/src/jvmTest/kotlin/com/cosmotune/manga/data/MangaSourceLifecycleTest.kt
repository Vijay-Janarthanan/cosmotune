package com.cosmotune.manga.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaSourceLifecycleTest {
    @get:Rule val temporary = TemporaryFolder()

    private val config = MangaSourceConfig("fixture", "mihon", "Fixture")

    @Test fun catalogueRequestsAreSharedButLanguagePaginationAndExpiryStayIndependent() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        store.source(config)
        val calls = AtomicInteger()
        var now = 1_000L
        val repository = MangaRepository(store) { source, _ -> object : MangaSource {
            override val config = source
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
                calls.incrementAndGet(); delay(30)
                return MangaBrowsePage(listOf(MangaBook("${source.settings}:$query:$offset", source.id, query)), null)
            }
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        repository.cacheClock = { now }
        val parallel = (1..8).map { async { repository.browse(config.id, "same", 0) } }.awaitAll()
        assertEquals(1, calls.get())
        assertEquals(1, parallel.distinct().size)
        repository.browse(config.id, "same", 0)
        assertEquals(1, calls.get())
        repository.browse(config.id, "same", 10)
        repository.browse(config.id, "another", 0)
        assertEquals(3, calls.get())
        repository.chooseLanguage(config.id, "ja")
        repository.browse(config.id, "same", 0)
        assertEquals(4, calls.get())
        now += MangaRepository.CATALOGUE_TTL_MS
        repository.browse(config.id, "same", 0)
        assertEquals(5, calls.get())
        repository.invalidateSource(config.id)
        repository.browse(config.id, "same", 0)
        assertEquals(6, calls.get())
        store.source(store.state.value.sources.single().copy(enabled = false))
        assertFailsWith<IllegalStateException> { repository.browse(config.id, "same", 0) }
        assertEquals(6, calls.get(), "a disabled source cannot be returned from cache")
    }

    @Test fun aRemovedSourceCannotReturnItsLateCatalogueOrReappearOnRestart() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        store.source(config)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val repository = MangaRepository(store) { source, _ -> object : MangaSource {
            override val config = source
            override suspend fun browse(query: String, offset: Int): MangaBrowsePage {
                entered.complete(Unit); finish.await(); return MangaBrowsePage(emptyList(), null)
            }
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        val pending = async { runCatching { repository.browse(config.id, "", 0) } }
        entered.await()
        store.update { it.copy(sources = emptyList()) }
        finish.complete(Unit)
        assertTrue(pending.await().isFailure)
        assertTrue(MangaStore(store.root).state.value.sources.isEmpty())
        assertFailsWith<IllegalStateException> { repository.browse(config.id, "", 0) }
    }

    @Test fun pageListsExpireAndExplicitRetryRefreshesWhileDownloadsStayOffline() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        store.source(config)
        var now = 2_000L
        val calls = AtomicInteger()
        val repository = MangaRepository(store) { source, _ -> object : MangaSource {
            override val config = source
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage(emptyList(), null)
            override suspend fun details(book: MangaBook) = book
            override suspend fun pages(chapter: MangaChapter) = listOf(MangaPage("page", "https://example.invalid/${calls.incrementAndGet()}.png"))
        } }
        repository.cacheClock = { now }
        val chapter = MangaChapter("chapter", "book", config.id, "Chapter", "/chapter")
        val first = repository.pages(chapter)
        assertEquals(first, repository.pages(chapter))
        assertEquals(1, calls.get())
        assertNotEquals(first, repository.pages(chapter, refresh = true))
        now += MangaRepository.PAGE_LIST_TTL_MS
        repository.pages(chapter)
        assertEquals(3, calls.get())
        val file = temporary.newFile().apply { writeText("offline") }
        store.update { it.copy(downloads = mapOf(chapter.id to listOf(MangaPage("page", localFile = file.path)))) }
        store.source(config.copy(enabled = false))
        now += 10 * MangaRepository.PAGE_LIST_TTL_MS
        assertEquals(file.path, repository.pages(chapter).single().localFile)
        assertEquals(3, calls.get())
    }

    @Test fun imagesExpireButCompletedOfflineDownloadsAreProtected() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("first").addHeader("Content-Type", "image/png"))
            server.enqueue(MockResponse().setBody("updated").addHeader("Content-Type", "image/png"))
            val store = MangaStore(temporary.newFolder())
            store.source(MangaSourceConfig("komga:test", "komga", "Test", baseUrl = server.url("/").toString()))
            val repository = MangaRepository(store)
            var now = System.currentTimeMillis()
            repository.cacheClock = { now }
            val page = MangaPage("image", server.url("/page").toString())
            assertEquals("first", repository.pageFile(page, "komga:test").readText())
            assertEquals("first", repository.pageFile(page, "komga:test").readText())
            assertEquals(1, server.requestCount)
            now += MangaRepository.IMAGE_TTL_MS
            val refreshed = repository.pageFile(page, "komga:test")
            assertEquals("updated", refreshed.readText())
            assertEquals(2, server.requestCount)
            store.update { it.copy(downloads = mapOf("chapter" to listOf(page.copy(url = null, localFile = refreshed.path)))) }
            now += MangaRepository.IMAGE_TTL_MS * 2
            assertEquals("updated", repository.pageFile(page, "komga:test").readText())
            repository.clearCache()
            assertTrue(refreshed.isFile)
            assertEquals(2, server.requestCount)
        }
    }

    @Test fun failedOrStaleExtensionReplacementKeepsThePreviouslyTrustedSourcesAndSwitches() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        val publisher = "a".repeat(64)
        fun source(version: Long, signers: String = publisher) = config.copy(id = "mihon:fixture:1", enabled = true,
            settings = mapOf("package" to "fixture", "versionCode" to "$version", "signers" to signers, "sha256" to "b".repeat(64)))
        store.installExtension("fixture", listOf(source(2)))
        store.source(store.state.value.sources.single().copy(enabled = false))
        val previous = store.state.value.sources
        assertFailsWith<IllegalArgumentException> { store.installExtension("fixture", emptyList()) }
        assertFailsWith<IllegalArgumentException> { store.installExtension("fixture", listOf(source(1))) }
        assertFailsWith<IllegalArgumentException> { store.installExtension("fixture", listOf(source(3, "c".repeat(64)))) }
        assertEquals(previous, store.state.value.sources)
        assertEquals(previous, MangaStore(store.root).state.value.sources)
        store.installExtension("fixture", listOf(source(3)))
        assertFalse(store.state.value.sources.single().enabled)
        assertEquals("3", store.state.value.sources.single().settings["versionCode"])
    }

    @Test fun deletedStoreSubscriptionsRemainDeletedWithoutRemovingInstalledExtensions() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        store.source(config)
        val subscription = MangaExtensionStore("https://example.invalid/repo.json", "Publisher", "a".repeat(64))
        store.saveExtensionStore(subscription)
        assertFailsWith<IllegalArgumentException> { store.saveExtensionStore(subscription.copy(signingKey = "b".repeat(64))) }
        assertEquals(listOf(subscription), store.state.value.extensionStores)
        store.removeExtensionStore(subscription.indexUrl)
        val restored = MangaStore(store.root)
        assertTrue(restored.state.value.extensionStores.isEmpty())
        assertEquals(listOf(config), restored.state.value.sources)
    }
}
