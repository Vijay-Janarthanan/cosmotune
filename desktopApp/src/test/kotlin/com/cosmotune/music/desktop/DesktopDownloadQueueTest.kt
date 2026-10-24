package com.cosmotune.music.desktop

import com.cosmotune.music.data.model.Song
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlinx.coroutines.*
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The download queue's bookkeeping.
 *
 * The transfer itself is not exercised — that reaches the network and writes to the listener's own
 * Music folder — so these pin the part that decides what is queued, in what order, and what a
 * cancel does to it.
 */
class DesktopDownloadQueueTest {

    private var original: (suspend (Song, String, (Long, Long?) -> Unit) -> Result<Song>)? = null
    @BeforeTest fun useIsolatedTransfers() = runBlocking {
        DesktopDownloadQueue.cancelAll()
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        original = DesktopDownloadQueue.transfer
        originalQuality = DesktopDownloadQueue.defaultQuality
        DesktopDownloadQueue.defaultQuality = { "LOSSLESS" }
        DesktopDownloadQueue.transfer = { _, _, _ -> awaitCancellation() }
    }
    private var originalQuality: (() -> String)? = null

    private fun song(id: String) = Song(videoId = id, title = "Track $id", artist = "Artist", thumbnailUrl = "")

    @AfterTest
    fun tearDown() {
        DesktopDownloadQueue.cancelAll()
        runBlocking { withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() } }
        original?.let { DesktopDownloadQueue.transfer = it }
        originalQuality?.let { DesktopDownloadQueue.defaultQuality = it }
        DesktopDownloadQueue.detachJournalForTest()
    }

    private fun journalFile(): java.io.File =
        kotlin.io.path.createTempDirectory("cosmotune-queue-").toFile().also { it.deleteOnExit() }.resolve("queue.json")

    @Test fun `a failed transfer stays visible with its reason, is saved, and Retry keeps its quality`() = runBlocking {
        val file = journalFile()
        DesktopDownloadQueue.restore(file)
        DesktopDownloadQueue.defaultQuality = { "HIGH" }
        val asked = java.util.concurrent.CopyOnWriteArrayList<String>()
        val calls = java.util.concurrent.atomic.AtomicInteger()
        DesktopDownloadQueue.transfer = { song, quality, _ ->
            asked += quality
            if (calls.incrementAndGet() == 1) Result.failure(java.io.IOException("The server closed the connection"))
            else Result.success(song)
        }
        val item = song("src:first::fails")
        DesktopDownloadQueue.enqueue(item)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        val failed = DesktopDownloadQueue.active.value.getValue(item.videoId)
        assertEquals(DesktopDownloadState.Failed("The server closed the connection"), failed.state)
        // The saved queue holds the failure, not a running row, so a restart shows it for Retry.
        withTimeout(5_000) { while (!(file.exists() && file.readText().contains("\"FAILED\""))) delay(20) }
        com.cosmotune.music.data.downloads.MusicDownloadJournal(file).use { saved ->
            val entry = saved.entries.value.single()
            assertEquals("HIGH", entry.quality)
            assertEquals("The server closed the connection", entry.error)
        }

        // The listener changes the default afterwards; Retry still asks for what was requested.
        DesktopDownloadQueue.defaultQuality = { "LOW" }
        DesktopDownloadQueue.retry(item.videoId)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertEquals(listOf("HIGH", "HIGH"), asked)
        assertFalse(item.videoId in DesktopDownloadQueue.active.value)
    }

    @Test fun `a timeout inside a transfer is reported as a failure instead of leaving a stuck row`() = runBlocking {
        DesktopDownloadQueue.transfer = { _, _, _ -> withTimeout(20) { awaitCancellation() } }
        val item = song("src:first::slow")
        DesktopDownloadQueue.enqueue(item)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertEquals(
            DesktopDownloadState.Failed("The download timed out. Retry to try again."),
            DesktopDownloadQueue.active.value.getValue(item.videoId).state,
        )
    }

    @Test fun `interrupted work from a previous run is offered for Retry and not fetched until asked`() = runBlocking {
        val file = journalFile()
        com.cosmotune.music.data.downloads.MusicDownloadJournal(file).use { previous ->
            previous.enqueue(song("src:first::one"), 4, "HIGH")
            previous.enqueue(song("src:first::two"), 5, "LOSSLESS")
            previous.phase("src:first::two", 5, com.cosmotune.music.data.downloads.MusicDownloadPhase.FAILED, "Disk full")
            previous.flush()
        }
        val asked = java.util.concurrent.ConcurrentHashMap<String, String>()
        DesktopDownloadQueue.transfer = { song, quality, _ -> asked[song.videoId] = quality; Result.success(song) }
        DesktopDownloadQueue.restore(file)
        val rows = DesktopDownloadQueue.active.value
        assertEquals(DesktopDownloadState.Failed("Interrupted download. Retry to resume."), rows.getValue("src:first::one").state)
        assertEquals(DesktopDownloadState.Failed("Disk full"), rows.getValue("src:first::two").state)
        delay(100)
        assertTrue(asked.isEmpty(), "restoring must not start network transfers")
        DesktopDownloadQueue.retry("src:first::one")
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertEquals(mapOf("src:first::one" to "HIGH"), asked.toMap())
        // A later request for the same track keeps the order after the restored ones.
        assertTrue("src:first::two" in DesktopDownloadQueue.active.value)
    }

    @Test fun `cancelling a running job leaves other providers able to finish`() = runBlocking {
        val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        DesktopDownloadQueue.transfer = { song, _, _ ->
            started += song.videoId
            if (song.videoId.endsWith("hold")) awaitCancellation()
            delay(30)
            Result.success(song)
        }
        val held = song("src:first::hold")
        val other = song("src:second::finish")
        DesktopDownloadQueue.enqueue(held)
        withTimeout(5_000) { while (held.videoId !in started) delay(10) }
        DesktopDownloadQueue.cancel(held.videoId)
        DesktopDownloadQueue.enqueue(other)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertTrue(other.videoId in started)
        assertTrue(DesktopDownloadQueue.active.value.isEmpty())
    }

    @Test fun `a queued third job cannot occupy the slots of another provider`() = runBlocking {
        val release = CompletableDeferred<Unit>()
        val started = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()
        DesktopDownloadQueue.transfer = { song, _, _ ->
            started += song.videoId
            if (song.videoId.startsWith("src:first::")) release.await()
            Result.success(song)
        }
        listOf("src:first::one", "src:first::two", "src:first::three", "src:other::one").forEach {
            DesktopDownloadQueue.enqueue(song(it))
        }
        withTimeout(5_000) { while ("src:other::one" !in started || started.count { it.startsWith("src:first::") } < 2) delay(10) }
        assertEquals(2, started.count { it.startsWith("src:first::") })
        release.complete(Unit)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertTrue("src:first::three" in started)
    }

    @Test fun `late progress from a cancelled attempt cannot overwrite its retry`() = runBlocking {
        val calls = java.util.concurrent.atomic.AtomicInteger()
        val secondStarted = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var oldProgress: ((Long, Long?) -> Unit)? = null
        DesktopDownloadQueue.transfer = { song, _, progress ->
            if (calls.incrementAndGet() == 1) {
                oldProgress = progress
                awaitCancellation()
            } else {
                secondStarted.complete(Unit)
                release.await()
                Result.success(song)
            }
        }
        val item = song("src:first::retry")
        DesktopDownloadQueue.enqueue(item)
        withTimeout(5_000) { while (oldProgress == null) delay(10) }
        DesktopDownloadQueue.cancel(item.videoId)
        DesktopDownloadQueue.enqueue(item)
        withTimeout(5_000) { secondStarted.await() }
        requireNotNull(oldProgress)(100, 100)
        assertEquals(0f, (DesktopDownloadQueue.active.value.getValue(item.videoId).state as DesktopDownloadState.Running).fraction)
        release.complete(Unit)
        withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() }
        assertTrue(DesktopDownloadQueue.active.value.isEmpty())
    }

    @Test
    fun `a track is queued once however many times it is asked for`() {
        DesktopDownloadQueue.enqueue(song("a"))
        DesktopDownloadQueue.enqueue(song("a"))
        assertEquals(1, DesktopDownloadQueue.active.value.count { it.key == "a" })
    }

    @Test
    fun `the queue keeps the order it was asked in`() {
        listOf("a", "b", "c").forEach { DesktopDownloadQueue.enqueue(song(it)) }
        val order = DesktopDownloadQueue.active.value.values
            .sortedBy(DesktopDownloadItem::sequence)
            .map { it.song.videoId }
        assertEquals(listOf("a", "b", "c"), order.take(3))
    }

    @Test
    fun `a cancelled track leaves the manager rather than sitting in it`() {
        DesktopDownloadQueue.enqueue(song("gone"))
        assertTrue("gone" in DesktopDownloadQueue.active.value)
        DesktopDownloadQueue.cancel("gone")
        assertFalse("gone" in DesktopDownloadQueue.active.value)
    }

    @Test
    fun `cancel all empties the queue`() {
        listOf("a", "b", "c").forEach { DesktopDownloadQueue.enqueue(song(it)) }
        DesktopDownloadQueue.cancelAll()
        assertTrue(DesktopDownloadQueue.active.value.isEmpty())
    }

    @Test
    fun `a blank id is not something that can be downloaded`() {
        DesktopDownloadQueue.enqueue(song(""))
        assertFalse("" in DesktopDownloadQueue.active.value)
    }

    @Test
    fun `the summary line says what the queue is doing`() {
        assertEquals("Nothing downloading", summary(total = 0, failed = 0, busy = false))
        assertEquals("1 track", summary(total = 1, failed = 0, busy = true))
        assertEquals("4 tracks", summary(total = 4, failed = 0, busy = true))
        assertEquals("1 download failed", summary(total = 1, failed = 1, busy = false))
        assertEquals("3 downloads failed", summary(total = 3, failed = 3, busy = false))
        // Still going, so the count is what matters rather than the failures behind it.
        assertEquals("5 tracks", summary(total = 5, failed = 1, busy = true))
    }

    @Test
    fun `four transfers run at once, matching Android`() {
        assertEquals(4, DesktopDownloadQueue.WORKERS)
    }
}
