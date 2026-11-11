package com.cosmotune.canvas

import com.opencanvas.core.stream.MusicVideoSearch
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.Collections
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The playing song's search comes before the queue's; recorded-style answers, a slow fake network, no real one. */
class VideoSearchPriorityTest {
    private class Call(val query: String, val startedMs: Long, var endedMs: Long = 0L, var cancelled: Boolean = false)

    private val origin = System.nanoTime()
    private fun nowMs() = (System.nanoTime() - origin) / 1_000_000L
    private val calls: MutableList<Call> = Collections.synchronizedList(ArrayList())

    /** Every search takes [latencyMs]; the playing song's has its video, the queued songs' find nothing (so they go on asking). */
    private fun slowNetwork(latencyMs: Long, inFlight: AtomicInteger = AtomicInteger(), peak: AtomicInteger = AtomicInteger()) =
        OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            val query = RecordedVideoSearch.queryOf(chain.request())
            val call = Call(query, nowMs()).also(calls::add)
            val queued = "Queued" in query
            if (queued) peak.accumulateAndGet(inFlight.incrementAndGet(), ::maxOf)
            try {
                Thread.sleep(latencyMs)
            } finally {
                if (queued) inFlight.decrementAndGet()
            }
            call.endedMs = nowMs()
            call.cancelled = chain.call().isCanceled()
            val body = if ("Playing" in query) RecordedVideoSearch.answers.getValue("Hangova DC video song")
                .replace("Hangova", "Playing Song") else RecordedVideoSearch.EMPTY
            RecordedVideoSearch.respond(chain.request(), body)
        }).build()

    @Test fun thePlayingSongsSearchPreemptsAPrefetch() = runBlocking {
        val search = MusicVideoSearch(slowNetwork(latencyMs = 600))
        val queued = MutableStateFlow(false)
        withTimeout(60_000) {
            val prefetch = async { search.find("Queued Song", "Someone", 200, album = "Film", foreground = queued) }
            delay(300) // the prefetch's first page is on the wire
            val startedMs = nowMs()
            val playing = search.find("Playing Song", "Anirudh Ravichander, Heisenberg", 248, album = "DC")
            val endedMs = nowMs()
            assertTrue(playing.isNotEmpty())
            assertTrue(endedMs - startedMs < 1_500, "the playing song's search took one page, ${endedMs - startedMs} ms")
            prefetch.await()
            val prefetchCalls = synchronized(calls) { calls.filter { "Queued" in it.query } }
            // Nothing of the prefetch was asked while the playing song searched, and what was on the wire was called off.
            assertTrue(prefetchCalls.none { it.startedMs in (startedMs + 50) until endedMs }, prefetchCalls.joinToString { "${it.query}@${it.startedMs}" })
            val overlapping = prefetchCalls.filter { it.startedMs < startedMs && it.endedMs > startedMs }
            assertTrue(overlapping.all { it.cancelled }, "a prefetch page in flight is cancelled when the playing song starts searching")
            // The prefetch still finished its own search afterwards.
            assertTrue(prefetchCalls.any { it.startedMs >= endedMs && !it.cancelled })
        }
    }

    @Test fun onlyOnePrefetchPageIsOnTheWireAtATime() = runBlocking {
        val inFlight = AtomicInteger()
        val peak = AtomicInteger()
        val search = MusicVideoSearch(slowNetwork(latencyMs = 150, inFlight, peak))
        val queued = MutableStateFlow(false)
        withTimeout(60_000) {
            val first = async { search.find("Queued One", "Someone", 200, album = "Film", foreground = queued) }
            val second = async { search.find("Queued Two", "Someone Else", 200, album = "Film", foreground = queued) }
            first.await(); second.await()
        }
        assertEquals(1, peak.get(), "prefetch searches go one page at a time, never hedged")
    }

    @Test fun aPrefetchThatStartsPlayingStopsWaiting() = runBlocking {
        val search = MusicVideoSearch(slowNetwork(latencyMs = 3_000))
        val queued = MutableStateFlow(false)
        withTimeout(60_000) {
            // The playing song's search holds the way for a while; the queued song becomes the playing one meanwhile.
            val playing = async { search.find("Other Queued Song", "Someone", 200, album = "Film") }
            delay(200)
            val promoted = async { search.find("Playing Song", "Anirudh Ravichander, Heisenberg", 248, album = "DC", foreground = queued) }
            delay(300)
            val before = synchronized(calls) { calls.count { "Playing" in it.query } }
            assertEquals(0, before, "a prefetch waits while the playing song searches")
            queued.value = true
            delay(500)
            assertTrue(synchronized(calls) { calls.count { "Playing" in it.query } } >= 1, "promoted: asked at once")
            promoted.await()
            playing.cancel()
        }
    }
}
