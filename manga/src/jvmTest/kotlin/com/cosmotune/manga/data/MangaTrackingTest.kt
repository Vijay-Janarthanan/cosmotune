package com.cosmotune.manga.data

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaTrackingTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun chapter(n: Float, id: String = "c$n") = MangaChapter(id, "b", "kotatsu:X", "Chapter $n", "/c/$n", number = n)
    private val book = MangaBook("b", "kotatsu:X", "Solo Leveling", chapters = listOf(chapter(1f), chapter(2f), chapter(2.5f), chapter(3f)))

    @Test fun theChapterCountIsTheHighestWholeChapterRead() {
        assertEquals(0, mangaTrackedChapter(book, emptySet()))
        assertEquals(2, mangaTrackedChapter(book, setOf("c1.0", "c2.0", "c2.5")))
        assertEquals(3, mangaTrackedChapter(book, setOf("c3.0")))
        val unnumbered = book.copy(chapters = listOf(MangaChapter("a", "b", "s", "One", "/a"), MangaChapter("z", "b", "s", "Two", "/z")))
        assertEquals(2, mangaTrackedChapter(unnumbered, setOf("a", "z")), "a source without numbers counts the read chapters")
    }

    @Test fun titlesMatchIgnoringCaseAndPunctuation() {
        assertEquals(1.0, mangaTitleScore("Solo Leveling!", "solo leveling"))
        assertTrue(mangaTitleScore("Solo Leveling: Ragnarok", "Solo Leveling") in 0.5..0.99)
        assertEquals(0.0, mangaTitleScore("One Piece", "Naruto"))
    }

    @Test fun signInLinkAndFinishedChaptersUpdateTheReadingList() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"status":"success","context":{"session_token":"session-1","uid":7}}"""))
            server.enqueue(MockResponse().setResponseCode(404)) // not on a list yet
            server.enqueue(MockResponse().setBody("{}")) // added to the Reading list
            server.enqueue(MockResponse().setBody("""{"list_id":0,"status":{"chapter":0}}""")) // list id lookup before the count
            server.enqueue(MockResponse().setBody("{}")) // the count
            val store = MangaStore(temporary.newFolder())
            store.save(book)
            store.update { it.copy(readChapters = setOf("c1.0")) }
            val tracking = MangaTracking(store, OkHttpClient(), MangaUpdatesTracker(OkHttpClient(), server.url("/v1").toString()))
            tracking.signIn("reader", "secret")
            val login = server.takeRequest()
            assertEquals("PUT", login.method); assertEquals("/v1/account/login", login.path)
            assertEquals("session-1", store.state.value.trackerAccounts.getValue(MangaTrackers.MANGAUPDATES).token)
            assertFalse(store.state.value.toString().contains("secret"), "the password is never kept")

            tracking.link(book, MangaTrackerMatch("42", "Solo Leveling", "Manhwa", null, "https://www.mangaupdates.com/series/x"))
            assertEquals("/v1/lists/series/42", server.takeRequest().path)
            val add = server.takeRequest()
            assertEquals("/v1/lists/series", add.path); assertEquals("Bearer session-1", add.getHeader("Authorization"))
            server.takeRequest()
            val count = server.takeRequest()
            assertEquals("/v1/lists/series/update", count.path)
            assertTrue(count.body.readUtf8().contains("\"chapter\":1"))
            assertEquals(1, store.state.value.tracks.getValue("b").single().chapter)

            // A further finished chapter sends the new count; a chapter below it sends nothing.
            server.enqueue(MockResponse().setBody("""{"list_id":0,"status":{"chapter":1}}"""))
            server.enqueue(MockResponse().setBody("{}"))
            store.update { it.copy(readChapters = it.readChapters + "c3.0") }
            tracking.send("b", listOf(chapter(3f)))
            server.takeRequest()
            assertTrue(server.takeRequest().body.readUtf8().contains("\"chapter\":3"))
            assertEquals(3, store.state.value.tracks.getValue("b").single().chapter)
            tracking.send("b", listOf(chapter(2f)))
            assertEquals(7, server.requestCount, "nothing is sent when the list is already as far")
        }
    }

    @Test fun komgaBooksAreMarkedReadOnTheServerWhenWriteBackIsOn() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val komgaBook = MangaBook("komga:k:s1", "komga:k", "Series", chapters = listOf(MangaChapter("komga:k:book:b1", "komga:k:s1", "komga:k", "One", "b1", 1f)))
        store.update { it.copy(sources = listOf(MangaSourceConfig("komga:k", "komga", "Home", baseUrl = "https://komga.example/")), books = listOf(komgaBook)) }
        val marked = ArrayList<String>()
        val tracking = MangaTracking(store, OkHttpClient(), server = { _, id -> marked += id })
        tracking.send(komgaBook.id, komgaBook.chapters)
        assertEquals(listOf("b1"), marked)
        store.update { it.copy(serverWriteBack = false) }
        tracking.send(komgaBook.id, komgaBook.chapters)
        assertEquals(listOf("b1"), marked, "write-back off sends nothing")
    }

    @Test fun komgaReadProgressIsAPatchToTheServer() {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(204))
            KomgaSource(MangaSourceConfig("komga:k", "komga", "Home", baseUrl = server.url("/").toString(), settings = mapOf("apiKey" to "key")), OkHttpClient()).markRead("b1")
            val request = server.takeRequest()
            assertEquals("PATCH", request.method); assertEquals("/api/v1/books/b1/read-progress", request.path)
            assertEquals("key", request.getHeader("X-API-Key"))
        }
    }
}
