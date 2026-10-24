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
        DesktopDownloadQueue.transfer = { _, _, _ -> awaitCancellation() }
    }

    private fun song(id: String) = Song(videoId = id, title = "Track $id", artist = "Artist", thumbnailUrl = "")

    @AfterTest
    fun tearDown() {
        DesktopDownloadQueue.cancelAll()
        runBlocking { withTimeout(5_000) { DesktopDownloadQueue.awaitIdle() } }
        original?.let { DesktopDownloadQueue.transfer = it }
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
