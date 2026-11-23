package com.cosmotune.reading.data

import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class ReadingSyncTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun mark(id: String, at: Long) = ReadingBookmark(id, "b", id, ReadingLocator(), at)

    @Test fun mergeKeepsTheLaterOfEachAndRemovalsWin() {
        val now = 1_000_000L
        val a = ReadingSyncDocument(progress = mapOf("b" to ReadingProgress("b", ReadingLocator(progression = .2), 10)),
            bookmarks = mapOf("m1" to mark("m1", 5), "m2" to mark("m2", 5)), finished = mapOf("b" to ReadingFlag(true, 50)), removed = mapOf("m3" to 40))
        val b = ReadingSyncDocument(progress = mapOf("b" to ReadingProgress("b", ReadingLocator(progression = .7), 20)),
            bookmarks = mapOf("m3" to mark("m3", 30), "m4" to mark("m4", 60)), finished = mapOf("b" to ReadingFlag(false, 40)), removed = mapOf("m2" to 9))
        val merged = ReadingSyncMerge.merge(a, b, now)
        assertEquals(.7, merged.progress.getValue("b").locator.progression)
        assertEquals(setOf("m1", "m4"), merged.bookmarks.keys)
        assertEquals(ReadingFlag(true, 50), merged.finished["b"])
        assertEquals(merged, ReadingSyncMerge.merge(b, a, now))
        // Removals are forgotten after half a year.
        assertTrue(ReadingSyncMerge.merge(a, b, now + 200L * 24 * 60 * 60 * 1000).removed.isEmpty())
    }

    @Test fun twoDevicesShareTheirPlaceMarksAndFinishedThroughAWebDavFolder() = runBlocking<Unit> {
        val server = MockWebServer()
        var stored: String? = null
        var tag = 0
        server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
            override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse {
                assertEquals("/books/cosmotune-reading.json", request.path)
                assertNotNull(request.getHeader("Authorization"))
                return when (request.method) {
                    "GET" -> stored?.let { MockResponse().setBody(it).setHeader("ETag", "\"$tag\"") } ?: MockResponse().setResponseCode(404)
                    else -> {
                        val expected = request.getHeader("If-Match")
                        if (stored != null && expected != "\"$tag\"") MockResponse().setResponseCode(412)
                        else { stored = request.body.readUtf8(); tag++; MockResponse().setResponseCode(201) }
                    }
                }
            }
        }
        server.start()
        val address = server.url("/books").toString().replace("http://", "http://").let { it }
        val text = "Chapter 1\n\nOne.\n\nChapter 2\n\nTwo.\n"
        val phone = ReadingRepository(temporary.newFolder())
        val desktop = ReadingRepository(temporary.newFolder())
        val book = phone.importFile("Tale.txt") { text.byteInputStream() }
        desktop.importFile("Tale.txt") { text.byteInputStream() }
        phone.saveLocator(book.id, ReadingLocator("OEBPS/c0002.xhtml", .5))
        val mark = phone.saveBookmark(book.id, "A place", ReadingLocator("OEBPS/c0001.xhtml", .1))
        phone.saveHighlight(book.id, "OEBPS/c0001.xhtml", 0, 3, "One", ReadingMarkStyle.PINK, .1)
        phone.markFinished(book.id)
        // A local server counts as this network, so plain http is allowed for it.
        assertEquals("Synced", phone.connectWebDav(address, "me", "secret"))
        assertEquals("Synced", desktop.connectWebDav(address, "me", "secret"))
        val there = desktop.state.value
        assertEquals("OEBPS/c0002.xhtml", there.progress.getValue(book.id).locator.resourceHref)
        assertEquals(listOf(mark.id), there.bookmarks.map { it.id })
        assertEquals(ReadingMarkStyle.PINK, there.highlights.single().style)
        assertTrue(book.id in there.finished)
        // A removal on one device reaches the other.
        desktop.removeBookmark(mark.id)
        desktop.syncWebDav()
        phone.syncWebDav()
        assertTrue(phone.state.value.bookmarks.isEmpty())
        assertFailsWith<IllegalArgumentException> { phone.connectWebDav("http://example.com/dav", "", "") }
        server.shutdown()
    }
}
