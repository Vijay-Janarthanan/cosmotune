package com.cosmotune.manga.data

import java.util.Locale
import kotlinx.coroutines.runBlocking
import okhttp3.Headers
import okhttp3.Interceptor
import okhttp3.Response
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.koitharu.kotatsu.parsers.MangaLoaderContext
import org.koitharu.kotatsu.parsers.config.ConfigKey
import org.koitharu.kotatsu.parsers.core.AbstractMangaParser
import org.koitharu.kotatsu.parsers.exception.AuthRequiredException
import org.koitharu.kotatsu.parsers.exception.TooManyRequestExceptions
import org.koitharu.kotatsu.parsers.InternalParsersApi
import org.koitharu.kotatsu.parsers.model.Manga
import org.koitharu.kotatsu.parsers.model.MangaListFilter
import org.koitharu.kotatsu.parsers.model.MangaListFilterCapabilities
import org.koitharu.kotatsu.parsers.model.MangaListFilterOptions
import org.koitharu.kotatsu.parsers.model.MangaParserSource
import org.koitharu.kotatsu.parsers.model.SortOrder
import org.koitharu.kotatsu.parsers.model.MangaChapter as ParserChapter
import org.koitharu.kotatsu.parsers.model.MangaPage as ParserPage
import kotlin.test.*

/**
 * MG-03: the shared Kotatsu adapter, offline. A fixture parser stands in for a website so the mapping, the source-qualified identities,
 * the language filter, the page request headers and the actionable failures are checked without any third-party network.
 */
@OptIn(InternalParsersApi::class)
class KotatsuAdapterTest {
    @get:Rule val temporary = TemporaryFolder()
    private val identity = MangaParserSource.MANGAPLUSPARSER_EN

    private class FixtureParser(context: MangaLoaderContext, source: MangaParserSource, val imageUrl: String = "",
        val search: Boolean = true, val failure: Exception? = null, val browser: Boolean = false) : AbstractMangaParser(context, source) {
        val filters = ArrayList<MangaListFilter>()
        override val configKeyDomain = ConfigKey.Domain("fixture.invalid")
        override val availableSortOrders = linkedSetOf(SortOrder.POPULARITY, SortOrder.UPDATED)
        override val filterCapabilities = MangaListFilterCapabilities(isSearchSupported = search)
        override suspend fun getFilterOptions() = MangaListFilterOptions(availableLocales = setOf(Locale.ENGLISH, Locale.forLanguageTag("ta"), Locale.ROOT))
        override suspend fun getList(offset: Int, order: SortOrder, filter: MangaListFilter): List<Manga> {
            failure?.let { throw it }
            if (browser) context.requestBrowserAction(this, "https://fixture.invalid/verify")
            filters += filter
            return if (offset >= 2) emptyList() else listOf(book(offset + 1L), book(offset + 2L))
        }
        override suspend fun getDetails(manga: Manga) = manga.copy(description = "Described", chapters = listOf(
            ParserChapter(11, "One", 1f, 0, "/c/1", "Team A", 0, "English", source),
            ParserChapter(12, null, 2f, 0, "/c/2", null, 0, "Tamil", source),
        ))
        override suspend fun getPages(chapter: ParserChapter) = listOf(ParserPage(chapter.id * 10, "/p/${chapter.id}", null, source))
        override suspend fun getPageUrl(page: ParserPage) = imageUrl
        override fun getRequestHeaders(): Headers = Headers.headersOf("Referer", "https://fixture.invalid/")
        override fun intercept(chain: Interceptor.Chain): Response = chain.proceed(chain.request().newBuilder().header("X-Parser", "fixture").build())
        private fun book(id: Long) = Manga(id, "Book $id", emptySet(), "/b/$id", "https://fixture.invalid/b/$id", -1f, null, null,
            emptySet(), null, setOf("Writer"), null, null, null, source)
    }

    private fun repository(parser: (MangaLoaderContext) -> FixtureParser): Pair<MangaRepository, MangaSourceConfig> {
        val store = MangaStore(temporary.newFolder())
        val config = MangaSourceConfig("kotatsu:${identity.name}", name = "Fixture", language = "", settings = mapOf(KotatsuSource.LOCALE to "ta"))
        runBlocking { store.source(config) }
        return MangaRepository(store, kotatsuParser = { _, context -> parser(context) }) to config
    }

    @Test fun catalogueDetailsPagesAndImageUseSourceQualifiedIdentitiesAndTheParsersRequests() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(Buffer().write(byteArrayOf(1, 2, 3))))
            lateinit var fixture: FixtureParser
            val (repository, config) = repository { context -> FixtureParser(context, identity, server.url("/image/1.png").toString()).also { fixture = it } }
            val page = repository.browse(config.id, "night", 0)
            assertEquals(listOf("kotatsu:${identity.name}:1", "kotatsu:${identity.name}:2"), page.books.map { it.id })
            assertEquals(2, page.nextOffset)
            assertNull(repository.browse(config.id, "night", 2).nextOffset, "an empty page ends the catalogue")
            assertEquals("night", fixture.filters.first().query)
            assertEquals("ta", fixture.filters.first().locale?.toLanguageTag(), "the chosen catalogue language reaches the parser")
            assertEquals(listOf("en", "ta"), (repository.source(config.id) as KotatsuSource).languages())

            val book = repository.chapters(page.books.first())
            assertEquals("Described", book.description)
            assertEquals(listOf("kotatsu:${identity.name}:chapter:11", "kotatsu:${identity.name}:chapter:12"), book.chapters.map { it.id })
            assertEquals(listOf("English", "Tamil"), book.chapters.map { it.language }, "a chapter's branch is its edition")
            assertEquals("Chapter 2.0", book.chapters[1].title)
            assertTrue(book.chapters.all { it.bookId == book.id && it.sourceId == config.id })

            val pages = repository.pages(book.chapters.first())
            assertEquals("kotatsu:${identity.name}:page:110", pages.single().id)
            assertEquals("https://fixture.invalid/", pages.single().headers["Referer"])
            val image = repository.pageFile(pages.single(), config.id)
            assertContentEquals(byteArrayOf(1, 2, 3), image.readBytes())
            val request = server.takeRequest()
            assertEquals("fixture", request.getHeader("X-Parser"), "image requests pass through the parser's own interceptor")
            assertEquals("https://fixture.invalid/", request.getHeader("Referer"))
            assertEquals(ParserContext.USER_AGENT, request.getHeader("User-Agent"))
        }
    }

    @Test fun unsupportedSearchAndParserFailuresBecomeActionableMessages() = runBlocking<Unit> {
        val (plain, config) = repository { FixtureParser(it, identity, search = false) }
        val search = assertFailsWith<MangaSourceFailure> { plain.browse(config.id, "night", 0) }
        assertTrue("does not support search" in search.message.orEmpty())
        assertEquals(2, plain.browse(config.id, "", 0).books.size, "browsing still works")

        val (auth, _) = repository { FixtureParser(it, identity, failure = AuthRequiredException(identity)) }
        assertTrue("requires signing in" in assertFailsWith<MangaSourceFailure> { auth.browse(config.id, "", 0) }.message.orEmpty())
        val (limited, _) = repository { FixtureParser(it, identity, failure = TooManyRequestExceptions("https://fixture.invalid", 1_000)) }
        assertTrue("Wait a minute" in assertFailsWith<MangaSourceFailure> { limited.browse(config.id, "", 0) }.message.orEmpty())
        val (verify, _) = repository { FixtureParser(it, identity, browser = true) }
        assertEquals("https://fixture.invalid/verify", assertFailsWith<MangaBrowserRequired> { verify.browse(config.id, "", 0) }.address)
        val context = verify.context
        assertEquals(ParserContext.BROWSER_ENGINE_REQUIRED, assertFailsWith<MangaSourceFailure> { context.captureWebViewUrls("https://fixture.invalid", Regex(".*"), 1_000) }.message)
    }

    @Test fun sourceSettingsReachTheParserAsTypedValues() {
        val store = MangaStore(temporary.newFolder())
        val flag = ConfigKey.ShowSuspiciousContent(false)
        val broken = ConfigKey.SplitByTranslations(false)
        val domain = ConfigKey.Domain("fixture.invalid")
        runBlocking { store.source(MangaSourceConfig("kotatsu:${identity.name}", name = "Fixture",
            settings = mapOf(domain.key to "mirror.invalid", flag.key to "true", broken.key to "maybe"))) }
        val context = ParserContext(store.root) { store.state.value.sources }
        val config = context.getConfig(identity)
        assertEquals("mirror.invalid", config[domain])
        assertTrue(config[flag])
        assertFalse(config[broken], "an unreadable value falls back to the key's default")
        assertFailsWith<IllegalArgumentException> { context.createBitmap(10_000, 10_000) }
    }
}
