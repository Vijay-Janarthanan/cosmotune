package com.cosmotune.manga.data

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

/** MG-06: checking library series for new chapters, three sources at a time, with failures isolated and new chapters marked until read. */
class MangaLibraryUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun chapter(book: String, source: String, id: String, number: Float) = MangaChapter("$book:$id", book, source, "Chapter $id", id, number)

    @Test fun newChaptersAreFoundMarkedAndClearedAndFailuresStayWithTheirSeries() = runBlocking {
        val store = MangaStore(temporary.newFolder())
        val calls = ConcurrentHashMap<String, Int>()
        val active = AtomicInteger(); val peak = AtomicInteger()
        val repository = MangaRepository(store) { config, _ -> object : MangaSource {
            override val config = config
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage(emptyList(), null)
            override suspend fun details(book: MangaBook): MangaBook {
                val call = calls.merge(book.id, 1, Int::plus)!!
                peak.accumulateAndGet(active.incrementAndGet(), ::maxOf)
                try {
                    delay(50)
                    if (config.id == "broken") error("Source offline")
                    return book.copy(title = book.title + " (refreshed)", chapters = book.chapters + chapter(book.id, config.id, "new$call", 98f + call))
                } finally { active.decrementAndGet() }
            }
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
        } }
        store.source(MangaSourceConfig("working", "mihon", "Working"))
        store.source(MangaSourceConfig("broken", "mihon", "Broken"))
        store.source(MangaSourceConfig("off", "mihon", "Switched off", enabled = false))
        val series = (1..5).map { MangaBook("working:$it", "working", "Series $it", chapters = listOf(chapter("working:$it", "working", "1", 1f))) }
        series.forEach { store.save(it) }
        store.save(MangaBook("working:fresh", "working", "Never opened"))
        store.save(MangaBook("broken:1", "broken", "Unavailable series", chapters = listOf(chapter("broken:1", "broken", "1", 1f))))
        store.save(MangaBook("off:1", "off", "Disabled series", chapters = listOf(chapter("off:1", "off", "1", 1f))))
        store.save(MangaBook("local:1", "local", "Imported comic", chapters = listOf(MangaChapter("local:1:chapter", "local:1", "local", "Read", ""))))
        val added = store.state.value.books.first().addedAtMs

        val progress = ArrayList<MangaUpdateReport>()
        val report = repository.checkForUpdates { progress += it }
        assertEquals(7, report.total, "enabled network series only")
        assertEquals(7, report.checked)
        assertEquals(5, report.newChapters, "a series loaded for the first time has no new chapters, only its chapter list")
        assertEquals(5, report.updatedSeries)
        assertEquals(setOf("broken:1"), report.failures.keys)
        assertEquals("Source offline", report.failures["broken:1"])
        assertEquals((1..7).toList(), progress.map { it.checked }, "one progress report per series")
        assertTrue(peak.get() <= 3, "at most three series are checked at once, saw ${peak.get()}")
        assertNull(calls["off:1"]); assertNull(calls["local:1"])

        val state = MangaStore(store.root).state.value
        assertEquals((1..5).map { "working:$it:new1" }.toSet(), state.newChapters)
        assertEquals("Series 1 (refreshed)", state.books.first { it.id == "working:1" }.title)
        assertEquals(added, state.books.first { it.id == "working:1" }.addedAtMs, "refreshing keeps when the series was added")
        assertEquals(1, state.books.first { it.id == "working:fresh" }.chapters.size)
        assertEquals("Unavailable series", state.books.first { it.id == "broken:1" }.title, "a failed check leaves the series as it was")

        // Reading, marking or removing a new chapter clears its mark; a second check finds nothing new.
        store.markRead(state.books.first { it.id == "working:1" }.chapters.last(), true)
        store.progress(MangaProgress("working:2", "working:2:new1", 0, 3))
        store.remove("working:3")
        assertEquals(setOf("working:4:new1", "working:5:new1"), store.state.value.newChapters)
        val again = repository.checkForUpdates()
        assertEquals(5, again.newChapters, "four remaining series and the one loaded last time each gain a chapter")
        assertTrue("working:fresh:new2" in store.state.value.newChapters && "working:4:new1" in store.state.value.newChapters)
    }
}
