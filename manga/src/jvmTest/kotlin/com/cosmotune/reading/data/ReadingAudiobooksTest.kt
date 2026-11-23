package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingAudiobooksTest {
    @get:Rule val temporary = TemporaryFolder()
    private val account = ReadingAudiobookshelf("http://192.168.1.20:13378", "me", "key one", "lib")

    @Test fun aPlaceAcrossTheBookFindsItsTrackAndChapter() {
        val tracks = listOf(ReadingAudioTrack("a", "One", 60_000), ReadingAudioTrack("b", "Two", 120_000), ReadingAudioTrack("c", "Three", 30_000))
        assertEquals(0 to 10_000L, ReadingAudioMath.locate(tracks, 10_000))
        assertEquals(1 to 30_000L, ReadingAudioMath.locate(tracks, 90_000))
        assertEquals(2 to 30_000L, ReadingAudioMath.locate(tracks, 999_000), "past the end stays at the end")
        assertEquals(90_000L, ReadingAudioMath.overall(tracks, 1, 30_000))
        val book = ReadingAudiobook("x", "Book", tracks = tracks)
        assertEquals(listOf("One" to 0L, "Two" to 60_000L, "Three" to 180_000L), ReadingAudioMath.chapters(book).map { it.title to it.startMs }, "tracks stand in for chapters")
        assertEquals(1, ReadingAudioMath.chapterAt(book, 100_000))
        assertEquals(210_000L, book.durationMs)
    }

    @Test fun anAudiobookshelfItemGivesTracksWithTheKeyChaptersAndItsEbook() {
        val item = Json.parseToJsonElement("""{"id":"li_1","media":{"metadata":{"title":"Dune","authorName":"Frank Herbert","narratorName":"Scott Brick",
            "description":"<p>Desert   planet</p>"},"chapters":[{"start":0,"end":600.5,"title":"Book One"},{"start":600.5,"end":1200,"title":"Book Two"}],
            "tracks":[{"index":2,"duration":700,"title":"Part 2","contentUrl":"/api/items/li_1/file/22"},{"index":1,"duration":500.25,"title":"Part 1","contentUrl":"/api/items/li_1/file/21"}],
            "ebookFile":{"ino":"9"}}}""").jsonObject
        val book = assertNotNull(ReadingAudioLibrary.parseAudiobookshelf(account, item, expanded = true))
        assertEquals("Dune", book.title); assertEquals("Frank Herbert", book.author); assertEquals("Scott Brick", book.narrator)
        assertEquals("Desert planet", book.description)
        assertEquals(listOf("Part 1", "Part 2"), book.tracks.map { it.title })
        assertEquals("http://192.168.1.20:13378/api/items/li_1/file/21?token=key+one", book.tracks[0].url)
        assertEquals(500_250L, book.tracks[0].durationMs)
        assertEquals(listOf(0L, 600_500L), book.chapters.map { it.startMs })
        assertTrue(book.hasEbook)
        assertEquals(ReadingAudioSource.AUDIOBOOKSHELF, book.source)
        assertEquals(emptyList(), ReadingAudioLibrary.parseAudiobookshelf(account, item, expanded = false)!!.tracks, "a list row has no tracks yet")
    }

    @Test fun librivoxChaptersAreItsLowBitRateMp3sInOrder() {
        val metadata = Json.parseToJsonElement("""{"files":[
            {"name":"tale_02_dickens_64kb.mp3","format":"64Kbps MP3","title":"Chapter 2","length":"12:30"},
            {"name":"tale_01_dickens_64kb.mp3","format":"64Kbps MP3","title":"Chapter 1","length":"615.5"},
            {"name":"tale_01_dickens.mp3","format":"128Kbps MP3","title":"Chapter 1","length":"615.5"},
            {"name":"tale.jpg","format":"JPEG"}]}""").jsonObject
        val tracks = ReadingAudioLibrary.librivoxTracks("tale_two_cities_librivox", metadata)
        assertEquals(listOf("Chapter 1", "Chapter 2"), tracks.map { it.title })
        assertEquals("https://archive.org/download/tale_two_cities_librivox/tale_01_dickens_64kb.mp3", tracks[0].url)
        assertEquals(listOf(615_500L, 750_000L), tracks.map { it.durationMs })
    }

    @Test fun anAudiobookshelfAddressNeedsHttpsAwayFromHome() {
        assertEquals("https://books.example.org/", ReadingAudioLibrary.audiobookshelfBase("books.example.org/").toString())
        assertEquals("http://192.168.1.20:13378/", ReadingAudioLibrary.audiobookshelfBase("http://192.168.1.20:13378").toString())
        assertFailsWith<IllegalArgumentException> { ReadingAudioLibrary.audiobookshelfBase("http://books.example.org") }
    }

    @Test fun signingInListsBooksAndSendsThePlaceBack() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"user":{"id":"u","username":"me","token":"old","accessToken":"fresh"}}"""))
            server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"pods","name":"Podcasts","mediaType":"podcast"},{"id":"lib","name":"Books","mediaType":"book"}]}"""))
            val repository = ReadingRepository(temporary.newFolder())
            repository.audio.connectAudiobookshelf(base, "me", "secret")
            val saved = assertNotNull(repository.state.value.audiobookshelf)
            assertEquals("fresh", saved.token); assertEquals("lib", saved.libraryId)
            assertEquals("/login", server.takeRequest().path)
            assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))

            server.enqueue(MockResponse().setBody("""{"id":"li_1","media":{"metadata":{"title":"Dune"},"tracks":[{"index":1,"duration":100,"contentUrl":"/api/items/li_1/file/1"}]}}"""))
            server.enqueue(MockResponse().setBody("""{"currentTime":42.5,"isFinished":false}"""))
            val book = repository.audio.addAudiobookshelf(ReadingAudiobook("abs:x", "Dune", source = ReadingAudioSource.AUDIOBOOKSHELF, remoteId = "li_1"))
            assertEquals(42_500L, book.positionMs, "the server's place")
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody("{}"))
            repository.audio.savePosition(book.id, 60_000, sendRemote = true)
            val patch = server.takeRequest()
            assertEquals("PATCH", patch.method); assertEquals("/api/me/progress/li_1", patch.path)
            assertContains(patch.body.readUtf8(), "\"currentTime\":60.0")
        }
    }
}
