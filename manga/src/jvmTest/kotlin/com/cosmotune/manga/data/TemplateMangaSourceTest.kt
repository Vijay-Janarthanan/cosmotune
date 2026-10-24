package com.cosmotune.manga.data

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class TemplateMangaSourceTest {
    @get:Rule val temporary = TemporaryFolder()
    private val template = MangaHtmlTemplate("Fixture HTML", mangaList = MangaListTemplate("/catalogue", pageSize = 1,
        itemSelector = ".book", titleSelector = ".title", searchParams = mapOf("type" to "comic")),
        mangaDetail = MangaDetailTemplate(descriptionSelector = ".description", authorSelector = ".author", genreSelector = ".genre"),
        chapterList = MangaChapterTemplate(".chapters li"), pageList = MangaPageTemplate(".reader img"))
    private fun source(server: MockWebServer, model: MangaHtmlTemplate = template) = TemplateMangaSource(model.configuration(server.url("/").toString(), "Fixture", "ta", true), OkHttpClient())
    private val catalogue = """<div class="book"><a href="/series/one"><span class="title">A Tamil series</span><img data-src="/cover.png"></a></div>"""
    @Test fun catalogueSearchChaptersAndPagesPreserveSourceIdentityAndRelativeAddresses() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val source = source(server)
            server.enqueue(MockResponse().setBody(catalogue))
            val page = source.browse("தமிழ்", 1)
            val request = server.takeRequest()
            assertEquals("தமிழ்", request.requestUrl?.queryParameter("s")); assertEquals("comic", request.requestUrl?.queryParameter("type")); assertEquals("2", request.requestUrl?.queryParameter("page"))
            val book = page.books.single(); assertEquals(2, page.nextOffset); assertEquals("ta", book.language)
            assertEquals(server.url("/series/one").toString(), book.url); assertEquals(server.url("/cover.png").toString(), book.coverUrl)
            server.enqueue(MockResponse().setBody("""<h1>A Tamil series</h1><p class="description">Story</p><a class="author">Artist</a><a class="genre">Adventure</a><ul class="chapters"><li><a href="/chapter/2">Chapter 2</a></li><li><a href="/chapter/1">Chapter 1</a></li></ul>"""))
            val details = source.details(book)
            assertEquals(listOf(1f, 2f), details.chapters.map { it.number }); assertEquals(listOf("Artist"), details.authors)
            assertEquals("Story", details.description); assertEquals(listOf("Adventure"), details.tags)
            server.takeRequest()
            server.enqueue(MockResponse().setBody("""<div class="reader"><img data-lazy-src="/pages/1.png"><img src="/pages/2.png"><img src="/pages/2.png"></div>"""))
            val pages = source.pages(details.chapters.first())
            assertEquals(2, pages.size); assertEquals(server.url("/pages/1.png").toString(), pages.first().url)
            assertEquals(details.chapters.first().url, pages.first().headers["Referer"])
            assertTrue(pages.all { it.id.startsWith(source.config.id + ":page:") })
            val store = MangaStore(temporary.newFolder())
            store.source(source.config); store.save(details)
            val reopened = MangaStore(store.root)
            assertEquals(source.config, reopened.state.value.sources.single()); assertEquals(details, reopened.state.value.books.single())
        }
    }
    @Test fun ajaxChapterFallbackStaysOnTheSiteAndForeignRedirectsAreNotFetched() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { foreign ->
            val model = template.copy(chapterList = template.chapterList.copy(endpoint = "{bookUrl}/ajax/chapters/", method = "POST", parameters = mapOf("action" to "chapters")))
            val source = source(server, model)
            server.enqueue(MockResponse().setBody(catalogue)); val book = source.browse().books.single(); server.takeRequest()
            server.enqueue(MockResponse().setBody("<h1>Title</h1>")); server.enqueue(MockResponse().setBody("""<ul class="chapters"><li><a href="/chapter/1">Chapter 1</a></li></ul>"""))
            assertEquals(1, source.details(book).chapters.size); server.takeRequest()
            val ajax = server.takeRequest(); assertEquals("POST", ajax.method); assertEquals("action=chapters", ajax.body.readUtf8())
            assertEquals("/series/one/ajax/chapters/", ajax.path)
            server.enqueue(MockResponse().setResponseCode(302).addHeader("Location", foreign.url("/collect")))
            assertFailsWith<IllegalArgumentException> { source.browse() }
            assertEquals(0, foreign.requestCount)
            server.enqueue(MockResponse().setBody(catalogue.replace("/series/one", foreign.url("/wrong-site").toString())))
            assertFailsWith<IllegalArgumentException> { source.browse() }
            assertEquals(0, foreign.requestCount)
        } }
    }
    @Test fun errorsAndLimitsAreVisibleAndBlockedReadsAreCancellable() = runBlocking<Unit> {
        MockWebServer().use { server ->
            val source = source(server)
            server.enqueue(MockResponse().setResponseCode(403))
            assertFailsWith<MangaBrowserRequired> { source.browse() }
            server.enqueue(MockResponse().setChunkedBody("x".repeat(2 * 1024 * 1024 + 1), 4096))
            assertFailsWith<IllegalArgumentException> { source.browse() }
            server.enqueue(MockResponse().setBody(catalogue).setBodyDelay(5, java.util.concurrent.TimeUnit.SECONDS))
            val pending = launch { source.browse() }
            withContext(Dispatchers.IO) { repeat(3) { assertNotNull(server.takeRequest(5, java.util.concurrent.TimeUnit.SECONDS)) } }
            withTimeout(2_000) { pending.cancelAndJoin() }
        }
    }
    @Test fun templateValidationRejectsUnsupportedScriptsRegexAndPrivateNetworkAccessWithoutGrant() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { template.copy(type = "javascript").validate() }
        assertFailsWith<IllegalArgumentException> { template.copy(pageList = MangaPageTemplate("img:matches((a+)+)")).validate() }
        assertFails { MangaHtmlTemplate.parse(" ".repeat(128 * 1024 + 1)) }
        MockWebServer().use { server ->
            assertFailsWith<IllegalArgumentException> { template.configuration(server.url("/").toString(), "No local grant") }
            val config = template.configuration(server.url("/").toString(), "Fixture", localNetwork = true)
                .copy(baseUrl = server.url("/").toString().replace("http://", "https://"), settings = mapOf("template" to template.encode(), "allowLocalNetwork" to "false"))
            assertFails { TemplateMangaSource(config, OkHttpClient()).browse() }
            assertEquals(0, server.requestCount)
        }
        MangaHtmlTemplate.presets.forEach { assertEquals(it, MangaHtmlTemplate.parse(it.encode())) }
    }
}
