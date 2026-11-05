package com.cosmotune.listening

import com.cosmotune.music.data.model.Song
import java.io.File
import kotlinx.coroutines.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import kotlin.test.*

class PlaylistDownloadUpdatesTest {
    @get:Rule val temporary = TemporaryFolder()
    private fun remote(vararg ids: String) = RemotePlaylist("Evening", ids.map { RemoteTrack(Song(it, "Song $it", "Singer", null)) })
    private fun linked() = newLinkedPlaylist("youtube", "PL123", remote("original"))
    private fun file() = File(temporary.newFolder(), "updates.json")

    @Test fun onlyFutureAdditionsQueueAndDeletingDownloadedFilesNeverMakesKnownIdsNewAgain() = runBlocking<Unit> {
        val file = file(); val store = PlaylistDownloadUpdates(file); val queued = mutableListOf<String>()
        store.follow(linked())
        store.check({ _, _ -> remote("original", "new") }, { rows, _ -> queued += rows.map { it.videoId } }, force = true)
        assertEquals(listOf("new"), queued)
        val reopened = PlaylistDownloadUpdates(file)
        // Missing files and a source removal do not erase the seen recording IDs.
        reopened.check({ _, _ -> remote("new") }, { rows, _ -> queued += rows.map { it.videoId } }, force = true)
        assertEquals(listOf("new"), queued)
        assertEquals(setOf("original", "new"), reopened.watches.value.single().seen)
    }

    @Test fun largeAdditionsNeedPersistedReviewAndApprovalCannotApplyAnUnlinkedWatch() = runBlocking<Unit> {
        val file = file(); val store = PlaylistDownloadUpdates(file); var queued = 0
        store.follow(linked())
        store.check({ _, _ -> remote("original", *(1..51).map { "new$it" }.toTypedArray()) }, { rows, _ -> queued += rows.size }, force = true)
        assertEquals(0, queued)
        val reopened = PlaylistDownloadUpdates(file)
        val review = reopened.watches.value.single()
        assertEquals(51, review.pending.size)
        reopened.approve(review) { rows, _ -> queued += rows.size }
        assertEquals(51, queued); assertTrue(reopened.watches.value.single().pending.isEmpty())
        reopened.unfollow(review.id)
        assertFailsWith<IllegalArgumentException> { reopened.approve(review) { _, _ -> error("Must not enqueue") } }
    }

    @Test fun aFailedContinuationOrQueueWriteCannotAdvanceTheSeenBaseline() = runBlocking<Unit> {
        val store = PlaylistDownloadUpdates(file()); store.follow(linked())
        store.check({ _, _ -> error("Second page failed") }, { _, _ -> error("Must not enqueue") }, force = true)
        assertEquals(setOf("original"), store.watches.value.single().seen)
        assertNotNull(store.watches.value.single().error)
        store.check({ _, _ -> remote("original", "new") }, { _, _ -> error("Journal full") }, force = true)
        assertEquals(setOf("original"), store.watches.value.single().seen)
        var queued = 0
        store.check({ _, _ -> remote("original", "new") }, { rows, _ -> queued += rows.size }, force = true)
        assertEquals(1, queued); assertNull(store.watches.value.single().error)
    }

    @Test fun simultaneousAutomaticChecksSerializeAndStoppingDuringFetchPreventsItsEnqueue() = runBlocking<Unit> {
        val store = PlaylistDownloadUpdates(file()); val playlist = linked(); store.follow(playlist)
        var queued = 0
        val now = PlaylistDownloadUpdates.INTERVAL_MS + 1
        coroutineScope { repeat(2) { launch { store.check({ _, _ -> delay(20); remote("original", "new") }, { rows, _ -> queued += rows.size }, now = now) } } }
        assertEquals(1, queued)
        val started = CompletableDeferred<Unit>(); val release = CompletableDeferred<Unit>()
        val check = launch { store.check({ _, _ -> started.complete(Unit); release.await(); remote("original", "new", "later") }, { _, _ -> error("Unfollowed playlist must not queue") }, force = true) }
        started.await(); store.unfollow(playlist.id); release.complete(Unit); check.join()
        assertTrue(store.watches.value.none { it.enabled })
    }

    @Test fun stoppingAndReenablingRetainsTheDeletedDownloadGuard() = runBlocking<Unit> {
        val file = file(); val store = PlaylistDownloadUpdates(file); val playlist = linked()
        val queued = mutableListOf<String>()
        store.follow(playlist)
        store.check({ _, _ -> remote("original", "new") }, { rows, _ -> queued += rows.map { it.videoId } }, force = true)
        store.unfollow(playlist.id)
        val reopened = PlaylistDownloadUpdates(file)
        assertTrue(reopened.watches.value.none { it.enabled })
        reopened.follow(playlist)
        reopened.check({ _, _ -> remote("original", "new", "later") }, { rows, _ -> queued += rows.map { it.videoId } }, force = true)
        assertEquals(listOf("new", "later"), queued)
    }
}
