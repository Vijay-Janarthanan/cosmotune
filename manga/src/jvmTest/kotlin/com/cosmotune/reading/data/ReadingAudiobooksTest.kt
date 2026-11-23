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
        assertEquals("http://192.168.1.20:13378/api/items/li_1/file/21?token=key%20one", book.tracks[0].url)
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

    @Test fun anApiKeySignsInWithoutAPassword() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"id":"u","username":"reader"}"""))
            server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"lib","name":"Books","mediaType":"book"}]}"""))
            val repository = ReadingRepository(temporary.newFolder())
            repository.audio.connectAudiobookshelf(server.url("/").toString(), "", " my-key ", apiKey = true)
            val saved = assertNotNull(repository.state.value.audiobookshelf)
            assertEquals("my-key", saved.token); assertTrue(saved.apiKey); assertEquals("reader", saved.username)
            val me = server.takeRequest()
            assertEquals("/api/me", me.path); assertEquals("Bearer my-key", me.getHeader("Authorization"))
        }
    }

    @Test fun aShortLivedKeyIsRenewedOnceWithTheRefreshKey() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            server.enqueue(MockResponse().setBody("""{"user":{"username":"me","accessToken":"a1","refreshToken":"r1"}}"""))
            server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"lib","name":"Books"}]}"""))
            val repository = ReadingRepository(temporary.newFolder())
            repository.audio.connectAudiobookshelf(server.url("/").toString(), "me", "secret")
            assertEquals("true", server.takeRequest().getHeader("x-return-tokens"))
            server.takeRequest()
            assertEquals("r1", repository.state.value.audiobookshelf?.refreshToken)

            server.enqueue(MockResponse().setResponseCode(401))
            server.enqueue(MockResponse().setBody("""{"user":{"accessToken":"a2","refreshToken":"r2"}}"""))
            server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"lib","name":"Books"}]}"""))
            assertEquals(listOf("lib" to "Books"), repository.audio.libraries())
            assertEquals("Bearer a1", server.takeRequest().getHeader("Authorization"))
            val refresh = server.takeRequest()
            assertEquals("/auth/refresh", refresh.path); assertEquals("r1", refresh.getHeader("x-refresh-token"))
            assertEquals("Bearer a2", server.takeRequest().getHeader("Authorization"))
            val saved = assertNotNull(repository.state.value.audiobookshelf)
            assertEquals("a2", saved.token); assertEquals("r2", saved.refreshToken)
        }
    }

    @Test fun aDownloadedBookPlaysFromThisDeviceUntilTheDownloadIsRemoved() = runBlocking<Unit> {
        MockWebServer().use { server ->
            server.start()
            val base = server.url("/").toString().trimEnd('/')
            server.enqueue(MockResponse().setBody("""{"user":{"username":"me","token":"k"}}"""))
            server.enqueue(MockResponse().setBody("""{"libraries":[{"id":"lib","name":"Books"}]}"""))
            val repository = ReadingRepository(temporary.newFolder())
            repository.audio.connectAudiobookshelf(base, "me", "secret")
            server.takeRequest(); server.takeRequest()
            server.enqueue(MockResponse().setBody("""{"id":"li_1","media":{"metadata":{"title":"Dune"},"tracks":[
                {"index":1,"duration":10,"contentUrl":"/api/items/li_1/file/1"},{"index":2,"duration":30,"contentUrl":"/api/items/li_1/file/2"}]}}"""))
            server.enqueue(MockResponse().setResponseCode(404))
            val book = repository.audio.addAudiobookshelf(ReadingAudiobook("abs:x", "Dune", source = ReadingAudioSource.AUDIOBOOKSHELF, remoteId = "li_1"))
            server.takeRequest(); server.takeRequest()
            assertFalse(book.downloaded)

            server.enqueue(MockResponse().setBody("part one"))
            server.enqueue(MockResponse().setBody("part two"))
            val heard = mutableListOf<Float>()
            repository.audio.download(book.id) { heard += it }
            val first = server.takeRequest()
            assertEquals("/api/items/li_1/file/1", first.path, "the key goes in the header, not the address")
            assertEquals("Bearer k", first.getHeader("Authorization"))
            server.takeRequest()
            val kept = repository.state.value.audiobooks.single()
            assertTrue(kept.downloaded)
            assertEquals("part one", java.io.File(kept.tracks[0].playable).readText())
            assertEquals(1f, heard.last())
            assertTrue(repository.audio.downloadedBytes(book.id) > 0)

            repository.audio.removeDownload(book.id)
            val streaming = repository.state.value.audiobooks.single()
            assertFalse(streaming.downloaded)
            assertTrue(streaming.tracks[0].playable.startsWith("$base/api/items/li_1/file/1?token="))
            assertEquals(0L, repository.audio.downloadedBytes(book.id))
        }
    }

    @Test fun aStreamAddressTakesTheCurrentKey() {
        assertEquals("https://b.example/api/items/1/file/2?x=1&token=new", ReadingAudioLibrary.withToken("https://b.example/api/items/1/file/2?token=old&x=1", "new"))
    }
}
