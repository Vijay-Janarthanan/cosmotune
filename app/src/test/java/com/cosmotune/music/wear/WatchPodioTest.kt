package com.cosmotune.music.wear

import com.cosmotune.podio.*
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.Assert.*
import com.cosmotune.music.wear.WatchPodioWire as W
import com.cosmotune.music.data.model.QueueTier
import com.cosmotune.music.data.model.Song

class WatchPodioTest {
    private inline fun <reified T : Throwable> assertFailsWith(block: () -> Unit) {
        try { block() } catch (failure: Throwable) {
            if (failure is T) return
            throw AssertionError("Expected ${T::class.java.simpleName}", failure)
        }
        fail("Expected ${T::class.java.simpleName}")
    }
    @get:Rule val temporary = TemporaryFolder()
    @Test fun watchEpisodesJoinTheUserQueueAheadOfContextAndAutoplay() {
        fun row(id: String, tier: QueueTier) = Song(videoId = id, title = id, artist = "Publisher", thumbnailUrl = null, queueTier = tier)
        val timeline = listOf(row("current", QueueTier.CONTEXT), row("already-queued", QueueTier.USER_QUEUE),
            row("album-next", QueueTier.CONTEXT), row("suggested", QueueTier.AUTOPLAY))
        val episode = row("podio:episode:one", QueueTier.CONTEXT)
        val (at, queued) = watchPodioQueueEntry(timeline, 0, episode)
        assertEquals(2, at)
        assertEquals(QueueTier.USER_QUEUE, queued.queueTier)
        assertNotNull(queued.queueEntryId)
        val result = timeline.toMutableList().apply { add(at, queued) }
        assertEquals(listOf("current", "already-queued", episode.videoId, "album-next", "suggested"), result.map { it.videoId })
        assertEquals(0, watchPodioQueueEntry(emptyList(), -1, episode).first)
    }
    @Test fun wireRoundTripsBoundedPagesAndRejectsOversizedOrUnversionedMessages() {
        val request = W.Request(42, W.Kind.EPISODES, "show:publisher", 20)
        val page = W.Page(request, listOf(W.Row("podio:episode:one", W.RowKind.EPISODE, "A Tamil episode", "Publisher", 180_000, 45_000)), more = true)
        assertEquals(page, W.decodePage(W.encodePage(page)))
        assertEquals(request, W.decodeRequest(W.encodeRequest(request)))
        val selection = W.Selection(43, "podio:episode:one", true)
        assertEquals(selection, W.decodeSelection(W.encodeSelection(selection)))
        assertEquals(W.Action(43, true, "Added to Up Next"), W.decodeAction(W.encodeAction(W.Action(43, true, "Added to Up Next"))))
        assertFailsWith<IllegalArgumentException> { W.decodePage(ByteArray(W.MAX_PAGE_BYTES + 1)) }
        assertFailsWith<IllegalArgumentException> { W.decodeRequest(byteArrayOf(0, 0, 0, 2)) }
        assertFailsWith<IllegalArgumentException> { W.encodePage(page.copy(rows = List(21) { page.rows.single() })) }
        assertFailsWith<IllegalArgumentException> { W.encodeRequest(request.copy(offset = -1)) }
        assertFailsWith<IllegalArgumentException> { W.decodeSelection(W.encodeSelection(selection) + byteArrayOf(1)) }
    }

    @Test fun radioPagingUsesThePhoneCacheAndExpiredOrInventedSelectionsCannotBecomeUrls() = runBlocking {
        val library = PodioLibrary(temporary.newFile())
        var clock = 100L; var requests = 0
        val source = WatchPodioCatalogue(library, stations = {
            requests++
            List(340) { RadioStation("station-$it", "Station $it", "https://radio.example/$it") }
        }, now = { clock })
        val first = source.browse(W.Request(1, W.Kind.POPULAR_RADIO))
        assertEquals(20, first.rows.size); assertTrue(first.more)
        val next = source.browse(W.Request(2, W.Kind.POPULAR_RADIO, offset = 20))
        assertTrue(next.rows.none { row -> first.rows.any { it.id == row.id } })
        assertEquals(1, requests)
        assertEquals(0L, source.resolve(first.rows.first().id)?.second)
        assertNull(source.resolve("https://unrequested.example/private"))
        val last = source.browse(W.Request(3, W.Kind.POPULAR_RADIO, offset = 280))
        assertEquals(20, last.rows.size); assertFalse(last.more)
        clock += 600_001
        assertNull(source.resolve(first.rows.first().id))
    }

    @Test fun onlyFollowedPhoneShowsCanLoadEpisodesAndResumeUsesThePhoneLibrary() = runBlocking {
        val library = PodioLibrary(temporary.newFile())
        val show = PodcastShow("publisher", "A followed show", "Publisher", "https://publisher.example/feed.xml")
        library.update { it.copy(shows = listOf(show)) }
        library.recordProgress("podio:episode:one", 45_000, 180_000)
        var calls = 0
        val source = WatchPodioCatalogue(library, feed = { address ->
            calls++; assertEquals(show.feed, address)
            PodcastFeed(show, listOf(PodcastEpisode("one", "Episode one", show.title, "https://publisher.example/one.mp3", durationSeconds = 180)))
        })
        assertFailsWith<IllegalStateException> { source.browse(W.Request(1, W.Kind.EPISODES, "https://arbitrary.example/feed.xml")) }
        assertEquals(0, calls)
        val shows = source.browse(W.Request(2, W.Kind.PODCASTS))
        val episodes = source.browse(W.Request(3, W.Kind.EPISODES, shows.rows.single().id))
        assertEquals(45_000L, episodes.rows.single().resumeMs)
        assertEquals(45_000L, source.resolve(episodes.rows.single().id)?.second)
        assertEquals("https://publisher.example/one.mp3", source.resolve(episodes.rows.single().id)?.first?.url)
        assertEquals(1, calls)
    }

    @Test fun retryingTheSameWatchMessageCannotDuplicateAQueueAction() = runBlocking {
        val actions = WatchPodioActions()
        var appended = 0
        val request = W.Selection(42, "podio:episode:one", enqueue = true)
        repeat(2) { actions.execute("watch-one", request) { appended++; W.Action(request.id, true, "Added") } }
        assertEquals(1, appended)
        actions.execute("watch-two", request) { appended++; W.Action(request.id, true, "Added") }
        assertEquals(2, appended)
    }
}
