package com.cosmotune.cache

import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer

@OptIn(ExperimentalCoroutinesApi::class)
class PageCacheTest {
    private val folder: File = Files.createTempDirectory("page-cache").toFile()
    private val codec = CacheCodec.json(ListSerializer(String.serializer()))

    @AfterTest fun cleanUp() { folder.deleteRecursively() }

    private fun TestScope.cache(now: LongArray, disk: CacheDisk? = FileCacheDisk(folder)): PageCache {
        // A scope of the test's scheduler without a parent, so advanceUntilIdle runs its work (it skips backgroundScope's).
        val dispatcher = StandardTestDispatcher(testScheduler)
        return PageCache(scope = CoroutineScope(dispatcher), clock = { now[0] }, disk = { disk }, io = dispatcher)
    }

    @Test fun cachedContentShowsFirstAndTheRefreshSwapsItIn() = runTest {
        val now = longArrayOf(1_000)
        val cache = cache(now)
        var answer = listOf("old")
        val gate = CompletableDeferred<Unit>()
        val entry = cache.entry("home", FEED_FRESH_MS, codec) { gate.await(); answer }
        gate.complete(Unit)
        entry.load(); advanceUntilIdle()
        assertEquals(listOf("old"), entry.state.value.data)

        // Past the freshness window the page shows the old rows while the new ones load, then swaps them in.
        now[0] += FEED_FRESH_MS
        answer = listOf("new")
        val slow = CompletableDeferred<Unit>()
        entry.fetcher = { slow.await(); answer }
        entry.load(); runCurrent()
        assertEquals(listOf("old"), entry.state.value.data)
        assertTrue(entry.state.value.refreshing)
        assertFalse(entry.state.value.showPlaceholders)
        slow.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("new"), entry.state.value.data)
        assertFalse(entry.state.value.refreshing)
    }

    @Test fun aFailedRefreshKeepsTheContentAndSaysSo() = runTest {
        val now = longArrayOf(1_000)
        val cache = cache(now)
        var fail = false
        val entry = cache.entry("films", FEED_FRESH_MS, codec) { if (fail) error("offline") else listOf("Harbour Town") }
        entry.load(); advanceUntilIdle()
        fail = true
        entry.refresh(); advanceUntilIdle()
        val state = entry.state.value
        assertEquals(listOf("Harbour Town"), state.data)
        assertEquals("offline", state.error)
        assertTrue(state.refreshFailed)
        assertFalse(state.failedEmpty)
        // The next good answer clears the note.
        fail = false
        entry.refresh(); advanceUntilIdle()
        assertNull(entry.state.value.error)
    }

    @Test fun aFailureWithNothingCachedIsTheEmptyError() = runTest {
        val entry = cache(longArrayOf(1_000)).entry("live", FEED_FRESH_MS, codec) { error("no network") }
        assertTrue(entry.state.value.showPlaceholders)
        entry.load(); advanceUntilIdle()
        assertTrue(entry.state.value.failedEmpty)
        assertFalse(entry.state.value.showPlaceholders)
    }

    @Test fun freshContentIsNotFetchedAgainUntilTheWindowEnds() = runTest {
        val now = longArrayOf(1_000)
        val cache = cache(now)
        var calls = 0
        val entry = cache.entry("series", FEED_FRESH_MS, codec) { calls++; listOf("call $calls") }
        entry.load(); advanceUntilIdle()
        assertEquals(1, calls)
        // Coming back within the window (another page asking for the same key) fetches nothing.
        now[0] += FEED_FRESH_MS - 1
        assertNull(cache.entry("series", FEED_FRESH_MS, codec) { calls++; listOf("other") }.load())
        advanceUntilIdle()
        assertEquals(1, calls)
        // A pull refreshes whatever the age.
        entry.refresh(); advanceUntilIdle()
        assertEquals(2, calls)
        // After the window, coming back refreshes.
        now[0] += FEED_FRESH_MS
        entry.load(); advanceUntilIdle()
        assertEquals(3, calls)
    }

    @Test fun twoLoadsShareOneFetch() = runTest {
        var calls = 0
        val gate = CompletableDeferred<Unit>()
        val entry = cache(longArrayOf(1_000)).entry("one", FEED_FRESH_MS, codec) { calls++; gate.await(); listOf("x") }
        val first = entry.load(); runCurrent()
        val second = entry.load()
        assertTrue(first === second)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(1, calls)
    }

    @Test fun theDiskCopyComesBackAfterARestartAndIsRefreshed() = runTest {
        val now = longArrayOf(5_000)
        val disk = FileCacheDisk(folder)
        val before = cache(now, disk).entry("music:home:acc", FEED_FRESH_MS, codec) { listOf("Quick picks", "Mixed for you") }
        before.load(); advanceUntilIdle()
        assertTrue(folder.listFiles().orEmpty().any { it.name.endsWith(".json") })

        // A new process: a new cache over the same folder. The copy shows before the network answers, then the answer replaces it.
        val gate = CompletableDeferred<Unit>()
        val after = cache(now, disk).entry("music:home:acc", FEED_FRESH_MS, codec) { gate.await(); listOf("Fresh shelf") }
        after.load(); runCurrent()
        assertEquals(listOf("Quick picks", "Mixed for you"), after.state.value.data)
        assertEquals(5_000, after.state.value.updatedAtMs)
        assertTrue(after.state.value.refreshing)
        gate.complete(Unit); advanceUntilIdle()
        assertEquals(listOf("Fresh shelf"), after.state.value.data)

        val saved = cache(now, disk).readSaved("music:home:acc", codec)
        assertNotNull(saved)
        assertEquals(listOf("Fresh shelf"), saved.second)
    }

    @Test fun aCopyInAnOldFormatIsIgnored() = runTest {
        val disk = FileCacheDisk(folder)
        disk.write("broken", "12\n{not json")
        val entry = cache(longArrayOf(1_000), disk).entry("broken", FEED_FRESH_MS, codec) { listOf("ok") }
        entry.load(); advanceUntilIdle()
        assertEquals(listOf("ok"), entry.state.value.data)
    }

    @Test fun theFolderStaysWithinItsSize() {
        val disk = FileCacheDisk(folder, maxBytes = 3_000, maxEntryBytes = 2_000)
        repeat(5) { i -> disk.write("page $i", "x".repeat(900)); Thread.sleep(15) }
        val total = folder.listFiles().orEmpty().sumOf { it.length() }
        assertTrue(total <= 3_000, "kept $total bytes")
        assertNotNull(disk.read("page 4"))
        assertNull(disk.read("page 0"))
        disk.write("too big", "y".repeat(2_001))
        assertNull(disk.read("too big"))
    }

    @Test fun forgetDropsAnAccountsPages() = runTest {
        val cache = cache(longArrayOf(1_000))
        val mine = cache.entry("music:library:a", FEED_FRESH_MS, codec) { listOf("mine") }
        val theirs = cache.entry("music:library:b", FEED_FRESH_MS, codec) { listOf("theirs") }
        mine.load(); theirs.load(); advanceUntilIdle()
        cache.forget("music:library:a"); advanceUntilIdle()
        assertNull(mine.state.value.data)
        assertNull(cache.peek<List<String>>("music:library:a"))
        assertEquals(listOf("theirs"), theirs.state.value.data)
        assertNull(cache.readSaved("music:library:a", codec))
    }

    @Test fun anInjectedOwnerGetsItsOwnMemoryOnlyCache() {
        val owner = Any()
        val other = Any()
        assertTrue(PageCache.of(owner, persistent = false) === PageCache.of(owner, persistent = false))
        assertFalse(PageCache.of(owner, persistent = false) === PageCache.of(other, persistent = false))
        assertTrue(PageCache.of(owner, persistent = true) === PageCache.shared)
    }
}
