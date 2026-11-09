package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class ReadingCatalogueTest {
    @Test fun standardEbooksOffersOnlyTheCompatibleEpubFromItsOwnSite() = runBlocking<Unit> {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            assertEquals("https://standardebooks.org/feeds/atom/new-releases", chain.request().url.toString())
            Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(200).message("OK").body("""
                <feed xmlns="http://www.w3.org/2005/Atom" xmlns:media="http://search.yahoo.com/mrss/">
                  <title>New Releases</title><entry><id>the-book</id><title>The Book</title>
                  <author><name>Writer</name></author>
                  <media:thumbnail url="/ebooks/writer/the-book/downloads/cover-thumbnail.jpg"/>
                  <link href="/ebooks/writer/the-book" rel="alternate" type="application/xhtml+xml"/>
                  <link href="https://other.example/ebooks/a/downloads/other.epub" rel="enclosure" title="Compatible" type="application/epub+zip"/>
                  <link href="/ebooks/writer/the-book/downloads/book-advanced.epub" rel="enclosure" title="Advanced" type="application/epub+zip"/>
                  <link href="/ebooks/writer/the-book/downloads/book.epub?source=feed" rel="enclosure" title="Recommended compatible epub" type="application/epub+zip"/>
                  </entry>
                </feed>
            """.trimIndent().toResponseBody("application/atom+xml".toMediaType())).build()
        }.build()
        val book = StandardEbooksCatalogue(client).browse().publications.single()
        assertEquals("standardebooks:the-book", book.id)
        assertEquals("https://standardebooks.org/ebooks/writer/the-book/downloads/cover-thumbnail.jpg", book.coverUrl)
        assertEquals(listOf(ReadingLinkKind.DETAILS, ReadingLinkKind.ACQUIRE), book.links.map { it.kind })
        assertTrue(book.links.last().url.endsWith("/book.epub?source=feed"))
    }

    @Test fun personalAtomCataloguePreservesNavigationAcquisitionKindsAndCredentialBoundaries() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            server.enqueue(MockResponse().setBody("""<feed xmlns="http://www.w3.org/2005/Atom" xmlns:dc="http://purl.org/dc/terms/">
                <title>My library</title><link rel="next" href="?page=2" type="application/atom+xml"/>
                <link rel="search" href="/opds/search?q={searchTerms}" type="application/atom+xml"/>
                <entry><id>all</id><title>All books</title><link href="/opds/all" type="application/atom+xml"/></entry>
                <entry><id>owned</id><title>A commercial book I own</title><author><name>Writer</name></author><dc:language>en</dc:language>
                  <link rel="http://opds-spec.org/acquisition" href="/books/1.epub" type="application/epub+zip"/>
                  <link rel="http://opds-spec.org/image" href="/covers/1.jpg" type="image/jpeg"/>
                </entry>
                <entry><id>borrow</id><title>Library loan</title><link rel="http://opds-spec.org/acquisition/borrow" href="/borrow/2" type="text/html"/></entry>
                <entry><id>buy</id><title>Purchase required</title><link rel="http://opds-spec.org/acquisition/buy" href="https://store.example/book" type="text/html"/></entry>
                <entry><id>metadata</id><title>No readable copy</title><author><name>Another writer</name></author><link rel="alternate" href="/details/3" type="text/html"/></entry>
                </feed>"""))
            val catalogue = PersonalOpdsCatalogue("personal", server.url("/opds").toString(), "reader", "private", allowInsecureHttp = true)
            val page = catalogue.browse()
            assertEquals("My library", page.title)
            assertEquals(server.url("/opds/all").toString(), page.navigation.single().url)
            assertEquals(listOf("owned", "borrow", "buy", "metadata"), page.publications.map { it.id })
            assertEquals(ReadingLinkKind.ACQUIRE, page.publications[0].links.first().kind)
            assertEquals(ReadingLinkKind.BORROW, page.publications[1].links.single().kind)
            assertEquals(ReadingLinkKind.BUY, page.publications[2].links.single().kind)
            assertEquals("metadata-only", page.publications[3].accessEvidence)
            assertEquals(listOf("en"), page.publications.first().languages)
            assertEquals(Credentials.basic("reader", "private"), server.takeRequest().getHeader("Authorization"))
            assertEquals(1, server.requestCount, "listing never fetches covers or book files")
            assertTrue(page.search.single().url.contains("{searchTerms}"))
            server.enqueue(MockResponse().setBody("""<feed xmlns="http://www.w3.org/2005/Atom"><title>Page 2</title></feed>"""))
            assertEquals("Page 2", catalogue.browse(page.next!!.url).title)
            assertEquals(Credentials.basic("reader", "private"), server.takeRequest().getHeader("Authorization"))
            assertFailsWith<IllegalArgumentException> { catalogue.browse(other.url("/opds").toString()) }
            assertEquals(0, other.requestCount)
        } }
    }

    @Test fun opdsTwoGroupsAndMultipleRelationsKeepMetadataDistinctFromBorrowAndSample() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metadata":{"title":"Personal OPDS 2"},
              "links":[{"rel":"next","href":"?page=2","type":"application/opds+json"}],
              "navigation":[{"title":"Recently added","href":"/recent","type":"application/opds+json"}],
              "groups":[{"metadata":{"title":"Shelf"},"publications":[
                {"metadata":{"identifier":"one","title":"Owned","author":[{"name":"Writer"}],"language":["en","ta"]},
                  "links":[{"rel":["alternate","http://opds-spec.org/acquisition/open-access"],"href":"/one.epub","type":"application/epub+zip"}],
                  "images":[{"href":"/one.jpg"}]},
                {"metadata":{"identifier":"two","title":"Sample only"},"links":[{"rel":"http://opds-spec.org/acquisition/sample","href":"/sample.acsm","type":"application/vnd.adobe.adept+xml"}]},
                {"metadata":{"identifier":"three","title":"Catalogue record"},"links":[]}
              ]}]}"""))
            val result = PersonalOpdsCatalogue("private", server.url("/opds").toString(), allowInsecureHttp = true).browse()
            assertEquals(3, result.publications.size)
            assertEquals(ReadingLinkKind.ACQUIRE, result.publications[0].links.single().kind)
            assertEquals(listOf("Writer"), result.publications[0].authors)
            assertEquals(listOf("en", "ta"), result.publications[0].languages)
            assertEquals(ReadingLinkKind.SAMPLE, result.publications[1].links.single().kind)
            assertEquals("metadata-only", result.publications[2].accessEvidence)
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun personalCatalogueRefusesRedirectsDeclarationsAndOversizedMetadata() = runBlocking<Unit> {
        MockWebServer().use { server -> MockWebServer().use { other ->
            val catalogue = PersonalOpdsCatalogue("private", server.url("/opds").toString(), "reader", "private", allowInsecureHttp = true)
            assertFailsWith<IllegalArgumentException> { PersonalOpdsCatalogue("private", server.url("/opds").toString()) }
            server.enqueue(MockResponse().setResponseCode(302).setHeader("Location", other.url("/opds")))
            assertFailsWith<IllegalStateException> { catalogue.browse() }
            assertEquals(0, other.requestCount)
            server.enqueue(MockResponse().setBody("""<!DOCTYPE feed [<!ENTITY xxe SYSTEM "${other.url("/secret")}">]><feed><title>&xxe;</title></feed>"""))
            assertFailsWith<IllegalArgumentException> { catalogue.browse() }
            assertEquals(0, other.requestCount)
            server.enqueue(MockResponse().setBody("x".repeat(2 * 1024 * 1024 + 1)))
            assertFailsWith<IllegalArgumentException> { catalogue.browse() }
            server.enqueue(MockResponse().setResponseCode(403))
            assertFailsWith<IllegalStateException> { catalogue.browse() }
        } }
    }

    @Test fun broadBookSearchKeepsCommercialMetadataAndDoesNotInventAReadableCopy() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"numFound":21,"docs":[
              {"key":"/works/OL123W","title":"A modern commercial novel","author_name":["Writer"],"isbn":["9781234567890"],"ebook_access":"no_ebook","cover_i":42},
              {"key":"/works/OL456W","title":"Borrowable edition","ebook_access":"borrowable"}]}"""))
            val result = OpenLibraryBookCatalogue("Cosmotune (test@example.invalid)", okhttp3.OkHttpClient(), server.url("/search.json"))
                .search("modern novel")
            assertEquals(2, result.publications.size)
            assertEquals("no_ebook", result.publications[0].accessEvidence)
            assertEquals("borrowable", result.publications[1].accessEvidence)
            assertTrue(result.publications.all { book -> book.links.all { it.kind == ReadingLinkKind.DETAILS } })
            assertEquals(2, result.nextOffset)
            val request = server.takeRequest()
            assertEquals("modern novel", request.requestUrl!!.queryParameter("q"))
            assertEquals("20", request.requestUrl!!.queryParameter("limit"))
            assertNull(request.requestUrl!!.queryParameter("ebook_access"), "search is not limited to free books")
            assertEquals("Cosmotune (test@example.invalid)", request.getHeader("User-Agent"))
            assertEquals(1, server.requestCount)
        }
    }

    @Test fun editionsReportRealBindingsPublishersDatesAndIsbnsAndRepeatRequestsUseCache() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"size":2,"entries":[
              {"key":"/books/OL123M","title":"A real paperback edition","physical_format":"Paperback","edition_name":"Illustrated edition","publishers":["Publisher"],"publish_date":"2001","isbn_13":["9781234567890"]},
              {"key":"/books/OL124M","title":"A real hardcover edition","physical_format":"Hardcover","publishers":["Publisher Two"],"publish_date":"2002"}]}"""))
            val catalogue = OpenLibraryBookCatalogue("Cosmotune (test@example.invalid)", okhttp3.OkHttpClient(), server.url("/search.json"))
            val work = ReadingPublication("openlibrary:/works/OL12W", "Work", sourceId = "openlibrary")
            val editions = catalogue.editions(work)
            assertEquals(listOf("Paperback", "Illustrated edition"), editions.publications[0].editionFormats)
            assertEquals(listOf("Hardcover"), editions.publications[1].editionFormats)
            assertEquals(listOf("Publisher"), editions.publications[0].publishers)
            assertEquals("2001", editions.publications[0].publishedDate)
            assertEquals(listOf("9781234567890"), editions.publications[0].identifiers)
            assertTrue(editions.publications.all { it.links.all { link -> link.kind == ReadingLinkKind.DETAILS } })
            assertEquals(editions, catalogue.editions(work))
            assertEquals(1, server.requestCount, "Repeat metadata requests use the bounded cache.")
            assertEquals("/works/OL12W/editions.json", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun privateSearchUsesOnlyAdvertisedOpenSearchAndKeepsBasicAuthOnEveryRequest() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<feed><title>Library</title><link rel="search" type="application/opensearchdescription+xml" href="/opds/search.xml"/></feed>"""))
            server.enqueue(MockResponse().setBody("""<OpenSearchDescription><Url type="application/atom+xml" template="${server.url("/opds/search")}?query={searchTerms}&amp;count={count?}"/></OpenSearchDescription>"""))
            server.enqueue(MockResponse().setBody("""<feed><title>Search result</title><entry><id>book</id><title>My owned book</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="/book.epub"/></entry></feed>"""))
            val catalogue = PersonalOpdsCatalogue("private", server.url("/opds").toString(), "reader", "private", allowInsecureHttp = true)
            assertEquals("My owned book", catalogue.search("title & author").publications.single().title)
            val requests = (1..3).map { server.takeRequest() }
            assertTrue(requests.all { it.getHeader("Authorization") == Credentials.basic("reader", "private") })
            assertEquals("title & author", requests.last().requestUrl!!.queryParameter("query"))
            assertEquals("20", requests.last().requestUrl!!.queryParameter("count"))
        }
    }

    @Test fun gutenbergUsesDocumentedMetadataAndOnlyOffersOfficialFullFileLinks() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""<feed><title>Books: Alice</title><entry><id>${server.url("/ebooks/11.opds")}</id>
              <title>Alice's Adventures in Wonderland</title><content>Carroll, Lewis</content>
              <link rel="subsection" href="/ebooks/11.opds" type="application/atom+xml;profile=opds-catalog"/></entry></feed>"""))
            val catalogue = GutenbergBookCatalogue(okhttp3.OkHttpClient(), server.url("/ebooks/search.opds/"))
            val page = catalogue.browse("Alice")
            assertEquals("gutenberg", page.publications.single().sourceId)
            assertEquals("gutenberg:11", page.publications.single().id)
            assertEquals(listOf("Carroll, Lewis"), page.publications.single().authors)
            assertTrue(page.publications.single().links.none { it.kind == ReadingLinkKind.ACQUIRE }, "Only the selected source acquisition feed supplies full-file links.")
            server.enqueue(MockResponse().setBody("""<feed><title>Alice formats</title>
              <entry><title>Alice</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://www.gutenberg.org/ebooks/11.epub.noimages"/></entry>
              <entry><title>Alice illustrated</title><link rel="http://opds-spec.org/acquisition" type="application/epub+zip" href="https://www.gutenberg.org/ebooks/11.epub3.images"/></entry></feed>"""))
            val details = catalogue.details(page.publications.single())
            val acquisition = details.links.first { it.kind == ReadingLinkKind.ACQUIRE }
            assertTrue(acquisition.url.startsWith("https://www.gutenberg.org/"))
            assertTrue(acquisition.url.endsWith("epub3.images"), "Prefer the actually offered colour/illustrated EPUB when present.")
            assertEquals("application/epub+zip", acquisition.mediaType)
            assertEquals("Alice", server.takeRequest().requestUrl!!.queryParameter("query"))
            assertEquals("/ebooks/11.opds", server.takeRequest().requestUrl!!.encodedPath)
        }
    }

    @Test fun trendingUsesTheProviderDocumentedRangeInsteadOfAnInvalidBareWildcard() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"numFound":1,"docs":[{"key":"/works/OL1W","title":"A currently trending work"}]}"""))
            val catalogue = OpenLibraryBookCatalogue("Cosmotune (test@example.invalid)", okhttp3.OkHttpClient(), server.url("/search.json"))
            assertEquals("A currently trending work", catalogue.trending().publications.single().title)
            val request = server.takeRequest()
            assertEquals("trending_z_score:{0 TO *]", request.requestUrl!!.queryParameter("q"))
            assertEquals("trending", request.requestUrl!!.queryParameter("sort"))
        }
    }
}
