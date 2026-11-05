package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Test
import kotlin.test.*

/** MG-08: declarative templates over a site's JSON API, with the same site scoping as the HTML templates. */
class JsonTemplateSourceTest {
    private val template = MangaHtmlTemplate("Fixture API", type = "json",
        mangaList = MangaListTemplate("/api/series", pageSize = 2, itemSelector = "data.items", titleSelector = "attributes.title",
            linkSelector = "slug", coverSelector = "cover.url", searchEndpoint = "/api/search", searchParam = "q"),
        mangaDetail = MangaDetailTemplate(titleSelector = "data.title", coverSelector = "data.poster", descriptionSelector = "data.synopsis",
            authorSelector = "data.authors", genreSelector = "data.genres", endpoint = "/api/series/{link}"),
        chapterList = MangaChapterTemplate("data", titleSelector = "name", linkSelector = "id", numberSelector = "number", endpoint = "/api/series/{link}/chapters"),
        pageList = MangaPageTemplate("pages", endpoint = "/api/chapters/{link}", imageField = "src"))

    private fun server(foreign: String) = MockWebServer().apply {
        dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val url = requireNotNull(request.requestUrl)
                return MockResponse().setBody(when ("/" + url.pathSegments.joinToString("/")) {
                    "/api/series" -> """{"data":{"items":[{"slug":"night & day","attributes":{"title":"Night and day"},"cover":{"url":"/covers/1.png"}},
                        {"slug":"no-title"},{"slug":"other","attributes":{"title":"Other"},"cover":{"url":"$foreign/tracker.png"}}]}}"""
                    "/api/search" -> """{"data":{"items":[{"slug":"found","attributes":{"title":"Found ${url.queryParameter("q")}"}}]}}"""
                    "/api/series/night & day" -> """{"data":{"title":"Night & Day","poster":"/posters/1.png","synopsis":"<b>Two</b> friends",
                        "authors":[{"name":"A. Writer"},{"name":"B. Artist"}],"genres":["Drama","Drama","Slice of life"]}}"""
                    "/api/series/night & day/chapters" -> """{"data":[{"id":"c2","name":"The second","number":"2"},{"id":"c1","number":1},{"name":"no link"}]}"""
                    "/api/chapters/c1" -> """{"pages":[{"src":"/img/1.png"},{"src":"img/2.png"},{"src":"$foreign/img/3.png"},{"src":"/img/1.png"},{}]}"""
                    else -> return MockResponse().setResponseCode(404)
                })
            }
        }
    }

    @Test fun apiCatalogueSearchDetailsChaptersAndPagesMapByFieldPaths() = runBlocking<Unit> {
        MockWebServer().use { foreign -> server(foreign.url("/").toString().trimEnd('/')).use { server ->
            val source = TemplateMangaSource(template.configuration(server.url("/").toString(), "Fixture API", "en", localNetwork = true), OkHttpClient())
            val page = source.browse()
            assertEquals(listOf("Night and day", "Other"), page.books.map { it.title }, "an item without a title is skipped")
            assertEquals(2, page.nextOffset)
            val book = page.books.first()
            assertEquals("night & day", book.url)
            assertEquals(server.url("/covers/1.png").toString(), book.coverUrl)
            assertNull(page.books[1].coverUrl, "a cover on another plain-HTTP host is never fetched")
            assertTrue(book.id.startsWith("${source.config.id}:book:"))
            assertEquals("Found தமிழ்", source.browse("தமிழ்").books.single().title)

            val details = source.details(book)
            assertEquals("Night & Day", details.title)
            assertEquals(server.url("/posters/1.png").toString(), details.coverUrl)
            assertEquals(listOf("A. Writer", "B. Artist"), details.authors)
            assertEquals(listOf("Drama", "Slice of life"), details.tags)
            assertEquals("<b>Two</b> friends", details.description)
            assertEquals(listOf("c1", "c2"), details.chapters.map { it.url }, "newest-first lists are reversed; items without a link are skipped")
            assertEquals(listOf("Chapter 1", "The second"), details.chapters.map { it.title })
            assertEquals(listOf(1f, 2f), details.chapters.map { it.number })
            assertTrue(details.chapters.all { it.bookId == book.id && it.sourceId == source.config.id && it.language == "en" })

            val pages = source.pages(details.chapters.first())
            assertEquals(listOf(server.url("/img/1.png").toString(), server.url("/api/chapters/img/2.png").toString()), pages.map { it.url },
                "relative addresses resolve against the API response; foreign plain-HTTP images and duplicates are dropped")
            assertEquals(server.url("/api/chapters/c1").toString(), pages.first().headers["Referer"])
            assertEquals(0, foreign.requestCount)
        } }
    }

    @Test fun jsonPathsAndResponsesAreValidated() = runBlocking<Unit> {
        assertFailsWith<IllegalArgumentException> { template.copy(mangaList = template.mangaList.copy(itemSelector = "data[0]")).validate() }
        assertFailsWith<IllegalArgumentException> { template.copy(pageList = template.pageList.copy(imageField = "src; drop")).validate() }
        assertEquals(template, MangaHtmlTemplate.parse(template.encode()), "a JSON template survives the editor's round trip")
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("<html>Checking your browser</html>"))
            val source = TemplateMangaSource(template.configuration(server.url("/").toString(), "Fixture API", localNetwork = true), OkHttpClient())
            assertTrue("did not return JSON" in assertFailsWith<IllegalStateException> { source.browse() }.message.orEmpty())
            server.enqueue(MockResponse().setBody("""{"data":{"items":{"not":"a list"}}}"""))
            assertTrue("no series list" in assertFailsWith<IllegalStateException> { source.browse() }.message.orEmpty())
            server.enqueue(MockResponse().setResponseCode(503))
            assertFailsWith<MangaBrowserRequired> { source.browse() }
            // With no detail endpoint the series link itself is fetched, and a link to another site is refused before any request.
            val direct = template.copy(mangaDetail = template.mangaDetail.copy(endpoint = ""))
            val linked = TemplateMangaSource(direct.configuration(server.url("/").toString(), "Fixture API", localNetwork = true), OkHttpClient())
            val before = server.requestCount
            assertFailsWith<IllegalArgumentException> { linked.details(MangaBook("x", linked.config.id, "Outside", url = "https://elsewhere.invalid/series")) }
            assertEquals(before, server.requestCount)
        }
    }
}
