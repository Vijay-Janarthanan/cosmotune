package com.cosmotune.video.local

import com.cosmotune.video.library.MemoryLibraryFiles
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** The recent list of local files (`local-videos.json`): order, the cap, positions, the setting, and a write 500 ms after the last change. */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalRecentStoreTest {

    private var now = 1_000L

    private fun TestScope.store(
        files: MemoryLibraryFiles = MemoryLibraryFiles(),
        logs: MutableList<String> = ArrayList(),
        io: CoroutineDispatcher = StandardTestDispatcher(testScheduler),
    ) = LocalRecentStore(files, backgroundScope, { now }, io) { logs += it }

    private fun TestScope.pass(ms: Long) {
        advanceTimeBy(ms)
        runCurrent()
    }

    @Test
    fun aNewFileGoesToTheFrontWithTheTitleAndNoPosition() = runTest {
        val store = store()
        val first = store.touch("/v/a.mp4", "A")
        now += 10
        val second = store.touch("/v/b.mp4", "B")
        assertEquals(listOf(second.id, first.id), store.entries.value.map { it.id })
        assertEquals(0L, store.get(first.id)!!.positionMs)
        assertEquals(LocalVideo.idFor("/v/a.mp4"), first.id)
        assertEquals("/v/a.mp4", first.source)
    }

    @Test
    fun openingAFileAgainMovesItToTheFrontAndKeepsItsPositionAndSubtitle() = runTest {
        val store = store()
        val a = store.touch("/v/a.mp4", "A")
        store.updateProgress(a.id, 42_000, 600_000)
        store.setSubtitle(a.id, "/v/a.en.srt")
        store.touch("/v/b.mp4", "B")
        now += 5
        store.touch("/v/a.mp4", "A")
        assertEquals(listOf(a.id), store.entries.value.take(1).map { it.id })
        val again = store.get(a.id)!!
        assertEquals(42_000, again.positionMs)
        assertEquals(600_000, again.durationMs)
        assertEquals("/v/a.en.srt", again.subtitle)
        assertEquals(2, store.entries.value.size)
    }

    @Test
    fun aLengthOfZeroKeepsTheOneKnownAndAPositionNeverGoesNegative() = runTest {
        val store = store()
        val a = store.touch("/v/a.mp4", "A")
        store.updateProgress(a.id, 1_000, 90_000)
        store.updateProgress(a.id, -5, 0)
        val entry = store.get(a.id)!!
        assertEquals(0, entry.positionMs)
        assertEquals(90_000, entry.durationMs)
    }

    @Test
    fun atMostFiftyFilesAreKeptTheOldestGoFirst() = runTest {
        val store = store()
        for (i in 1..60) {
            now += 1
            store.touch("/v/$i.mp4", "$i")
        }
        assertEquals(LocalRecentStore.MAX_ENTRIES, store.entries.value.size)
        assertEquals("60", store.entries.value.first().title)
        assertEquals("11", store.entries.value.last().title)
        assertNull(store.get(LocalVideo.idFor("/v/1.mp4")))
    }

    @Test
    fun aFileCanBeForgottenAndTheListCleared() = runTest {
        val store = store()
        val a = store.touch("/v/a.mp4", "A")
        store.touch("/v/b.mp4", "B")
        store.remove(a.id)
        assertEquals(listOf("B"), store.entries.value.map { it.title })
        store.clear()
        assertTrue(store.entries.value.isEmpty())
    }

    @Test
    fun theFileIsWrittenFiveHundredMillisecondsAfterTheLastChangeAndReadBackByANewStore() = runTest {
        val files = MemoryLibraryFiles()
        val store = store(files)
        val a = store.touch("/v/a.mp4", "A")
        pass(499)
        assertTrue(files.writes.isEmpty())
        store.updateProgress(a.id, 7_000, 70_000)
        pass(499)
        assertTrue(files.writes.isEmpty(), "the next change restarted the wait")
        pass(2)
        assertEquals(listOf(LocalRecentStore.FILE), files.writes)

        val reread = store(files)
        assertEquals(store.entries.value, reread.entries.value)
        assertEquals(7_000, reread.get(a.id)!!.positionMs)
    }

    @Test
    fun rememberPositionIsOnByDefaultIsKeptInTheFileAndFlushWritesAtOnce() = runTest {
        val files = MemoryLibraryFiles()
        val store = store(files)
        assertTrue(store.rememberPositions.value)
        store.setRememberPositions(false)
        store.flush()
        assertEquals(listOf(LocalRecentStore.FILE), files.writes)
        assertFalse(store(files).rememberPositions.value)
    }

    @Test
    fun aFileThatCannotBeReadIsSetAsideAndTheListStartsEmpty() = runTest {
        val files = MemoryLibraryFiles()
        files.files[LocalRecentStore.FILE] = "{ not json"
        val logs = ArrayList<String>()
        val store = store(files, logs)
        assertTrue(store.entries.value.isEmpty())
        assertTrue(files.files.keys.any { it.startsWith("${LocalRecentStore.FILE}.broken-") })
        assertTrue(logs.isNotEmpty())
    }
}
