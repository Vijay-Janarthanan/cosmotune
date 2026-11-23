package com.cosmotune.manga.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaDownloadQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun chapter(n: Int) = MangaChapter("c$n", "b", "kotatsu:X", "Chapter $n", "/$n", number = n.toFloat())
    private val book = MangaBook("b", "kotatsu:X", "Series", chapters = (1..6).map(::chapter))

    @Test fun aheadTakesTheNextUnreadChaptersNotOnTheDevice() {
        val state = MangaLibraryState(books = listOf(book), readChapters = setOf("c3"), downloads = mapOf("c4" to emptyList()))
        assertEquals(listOf("c5", "c6"), mangaChaptersAhead(book, chapter(2), state, 3).map { it.id })
        assertEquals(listOf("c2"), mangaChaptersAhead(book, chapter(1), state, 1).map { it.id })
        assertTrue(mangaChaptersAhead(book.copy(sourceId = "local"), chapter(1), state, 3).isEmpty())
    }

    @Test fun newChaptersToFetchSkipDownloadedAndLocalOnes() {
        val local = MangaBook("l", "local", "Local", chapters = listOf(MangaChapter("l1", "l", "local", "One", "/l")))
        val state = MangaLibraryState(books = listOf(book, local), downloads = mapOf("c6" to emptyList()))
        assertEquals(listOf("c5"), mangaNewToDownload(state, setOf("c5", "c6", "l1")).map { it.second.id })
    }

    private suspend fun waitFor(condition: () -> Boolean) = withTimeout(5_000) { while (!condition()) delay(10) }

    @Test fun theQueueFetchesInOrderPausesAndKeepsFailuresForRetry() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        store.save(book)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val fetched = ArrayList<String>()
        val gate = CompletableDeferred<Unit>()
        val queue = MangaDownloadQueue(store, scope, { chapter, progress ->
            if (chapter.id == "c1") gate.await()
            if (chapter.id == "c2") error("Source unavailable")
            progress(1, 1); synchronized(fetched) { fetched += chapter.id }
        }, { })
        queue.enqueue(book, listOf(chapter(1), chapter(2), chapter(3)))
        queue.enqueue(book, listOf(chapter(1)))
        assertEquals(3, queue.state.value.items.size, "a chapter is queued once")
        waitFor { queue.state.value.running == "c1" }
        queue.pause()
        waitFor { queue.state.value.running == null }
        assertEquals(3, queue.state.value.items.size, "a paused chapter waits again")
        gate.complete(Unit)
        queue.resume()
        waitFor { queue.state.value.items.size == 1 }
        assertEquals(listOf("c1", "c3"), synchronized(fetched) { fetched.toList() })
        assertEquals("Source unavailable", queue.state.value.items.single().error)
        queue.cancel("c2")
        assertTrue(queue.state.value.items.isEmpty())
        scope.cancel()
    }
}
