package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.*

class ReadingHardcoverTest {
    @Test fun theSearchHitWithTheSameTitleAndAuthorIsTheBook() {
        val results = Json.parseToJsonElement("""{"found":3,"hits":[
            {"document":{"id":"11","title":"Dune Messiah","author_names":["Frank Herbert"],"pages":256}},
            {"document":{"id":"12","title":"Dune","author_names":["Someone Else"],"pages":100}},
            {"document":{"id":"13","title":"Dune","author_names":["Frank Herbert"],"pages":612}}]}""")
        val book = assertNotNull(ReadingHardcoverClient.pick(results, "Dune", "Frank Herbert"))
        assertEquals(13, book.bookId); assertEquals(612, book.pages)
        assertEquals(11, ReadingHardcoverClient.pick(results, "Something else", "")?.bookId, "with no match, the first hit")
        assertNull(ReadingHardcoverClient.pick(Json.parseToJsonElement("""{"hits":[]}"""), "Dune", ""))
        assertEquals("Bearer abc", ReadingHardcoverClient.bearer(" Bearer abc "))
    }

    @Test fun aBookBeingReadIsAddedThenItsPageIsSentAndFinishingMarksItRead() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val client = ReadingHardcoverClient(OkHttpClient(), server.url("/v1/graphql").toString())
            server.enqueue(MockResponse().setBody("""{"data":{"me":[{"username":"reader"}]}}"""))
            assertEquals("reader", client.me("key-123"))
            assertEquals("Bearer key-123", server.takeRequest().getHeader("authorization"))

            val book = ReadingHardcoverBook(13, "Dune", pages = 600)
            server.enqueue(MockResponse().setBody("""{"data":{"insert_user_book":{"id":77,"error":null}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"insert_user_book_read":{"id":88,"error":null}}}"""))
            val reading = client.send("key-123", book, ReadingHardcoverClient.READING, 0.25, "2026-11-23")
            assertEquals(77, reading.userBookId); assertEquals(88, reading.readId); assertEquals(150, reading.sentPages)
            val insert = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("2", insert["variables"]!!.jsonObject["s"]!!.jsonPrimitive.content)
            val read = server.takeRequest().body.readUtf8()
            assertContains(read, "\"progress_pages\":150"); assertContains(read, "\"started_at\":\"2026-11-23\"")

            server.enqueue(MockResponse().setBody("""{"data":{"update_user_book":{"id":77,"error":null}}}"""))
            server.enqueue(MockResponse().setBody("""{"data":{"update_user_book_read":{"id":88,"error":null}}}"""))
            val done = client.send("key-123", reading, ReadingHardcoverClient.READ, 0.9, "2026-11-30")
            assertEquals(ReadingHardcoverClient.READ, done.status); assertEquals(600, done.sentPages, "finished means every page")
            assertContains(server.takeRequest().body.readUtf8(), "update_user_book(")
            val finish = server.takeRequest().body.readUtf8()
            assertContains(finish, "\"finished_at\":\"2026-11-30\""); assertContains(finish, "\"id\":88")

            server.enqueue(MockResponse().setBody("""{"errors":[{"message":"invalid key"}]}"""))
            assertEquals("Hardcover: invalid key", assertFailsWith<IllegalStateException> { client.me("bad") }.message)
        }
    }
}
