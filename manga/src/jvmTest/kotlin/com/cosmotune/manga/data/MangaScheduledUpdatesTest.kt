package com.cosmotune.manga.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class MangaScheduledUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()
    @Test fun disabledUpdatesDoNotRequestAnythingAndConcurrentDueChecksRunOnce() = runBlocking<Unit> {
        val store = MangaStore(temporary.newFolder())
        val config = MangaSourceConfig("fixture", "mihon", "Fixture")
        store.source(config)
        val first = MangaChapter("first", "book", config.id, "First", "")
        store.save(MangaBook("book", config.id, "Book", chapters = listOf(first)))
        val requests = AtomicInteger()
        val repository = MangaRepository(store) { item, _ -> object : MangaSource {
            override val config = item
            override suspend fun browse(query: String, offset: Int) = MangaBrowsePage(emptyList(), null)
            override suspend fun pages(chapter: MangaChapter) = emptyList<MangaPage>()
            override suspend fun details(book: MangaBook): MangaBook { requests.incrementAndGet(); delay(20); return book.copy(chapters = book.chapters + first.copy(id = "new")) }
        } }
        assertNull(repository.scheduledUpdate(1_000)); assertEquals(0, requests.get())
        store.updateSchedule(24)
        val reports = (1..8).map { async { repository.scheduledUpdate(10_000) } }.awaitAll()
        assertEquals(1, reports.count { it != null }); assertEquals(1, requests.get())
        assertEquals(1, store.state.value.lastUpdateNewChapters)
        assertEquals(10_000L, store.state.value.lastUpdateCompletedMs)
        assertNull(repository.scheduledUpdate(10_001))
        store.updateSchedule(0); repository.checkForUpdates()
        assertEquals(2, requests.get(), "manual updates remain available when scheduling is off")
        store.source(config.copy(enabled = false)); store.updateSchedule(24)
        repository.scheduledUpdate(System.currentTimeMillis() + 172_800_000)
        assertEquals(2, requests.get(), "disabled sources are not contacted")
    }
}
