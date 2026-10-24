package com.cosmotune.manga.data

import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Test
import kotlin.test.*

class MangaGlobalSearchTest {
    private fun sources(count: Int) = (0 until count).map { MangaSourceConfig("source:$it", name = "Source $it") }
    private fun page(config: MangaSourceConfig) = MangaBrowsePage(listOf(MangaBook(config.id + ":book", config.id, "A series")), null)
    @Test fun failedAndDisabledSourcesCannotHideResultsOrExceedThreeActiveRequests() = runBlocking<Unit> {
        val configs = sources(8).mapIndexed { index, config -> config.copy(enabled = index != 7) }
        val active = AtomicInteger(); val peak = AtomicInteger()
        val seen = mangaGlobalSearch(configs, " Tamil ") { config, query ->
            assertEquals("Tamil", query)
            val count = active.incrementAndGet(); peak.updateAndGet { maxOf(it, count) }
            try { delay(15); if (config.id == "source:2") error("Unavailable"); page(config) }
            finally { active.decrementAndGet() }
        }.toList()
        val result = seen.last()
        assertEquals(7, result.completed); assertEquals(7, result.total); assertEquals(6, result.books.size)
        assertEquals(setOf("source:2"), result.errors.keys); assertTrue(peak.get() <= 3); assertTrue(seen.any { it.completed in 1..6 })
    }
    @Test fun timeoutsAreIsolatedButCancellingSearchStopsEveryActiveSource() = runBlocking<Unit> {
        val result = mangaGlobalSearch(sources(2), "title", 30) { config, _ -> if (config.id == "source:0") delay(500); page(config) }.last()
        assertEquals(1, result.books.size); assertEquals("Source timed out", result.errors["source:0"])
        val started = CompletableDeferred<Unit>(); val active = AtomicInteger()
        val task = launch { mangaGlobalSearch(sources(10), "title") { config, _ ->
            active.incrementAndGet(); started.complete(Unit)
            try { delay(10_000); page(config) } finally { active.decrementAndGet() }
        }.collect() }
        started.await(); withTimeout(1_000) { task.cancelAndJoin() }; assertEquals(0, active.get())
    }
    @Test fun sourceOwnershipAndAggregateBudgetsAreChecked() = runBlocking<Unit> {
        val configs = sources(100)
        val result = mangaGlobalSearch(configs, "title") { config, _ ->
            MangaBrowsePage((0 until 50).map { MangaBook("${config.id}:$it", config.id, "A series") }, null)
        }.last()
        assertEquals(2_000, result.books.size); assertTrue(result.truncated); assertEquals(100, result.completed)
        val wrong = mangaGlobalSearch(sources(1), "title") { _, _ -> page(sources(2).last()) }.last()
        assertTrue(wrong.books.isEmpty()); assertEquals(1, wrong.errors.size)
    }
}
