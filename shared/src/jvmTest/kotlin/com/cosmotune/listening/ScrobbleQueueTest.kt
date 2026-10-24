package com.cosmotune.listening

import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import kotlin.test.*

class ScrobbleQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val now = 2_000_000_000L
    private fun listen(n: Int, at: Long = now - 600 + n) = PendingScrobble("lastfm", "Artist $n", "Track $n", at)

    @Test fun offlineListensAreKeptInOrderAndSentWhenTheNetworkReturns() = runBlocking<Unit> {
        val file = File(temporary.root, "queue.json")
        val queue = ScrobbleQueue(file)
        (1..3).forEach { queue.add(listen(it)) }
        queue.add(listen(2)) // the same listen twice is stored once
        var online = false
        val sent = mutableListOf<String>()
        val offline = queue.flush(now) { if (online) { sent += it.track; ScrobbleOutcome.SENT } else ScrobbleOutcome.RETRY_LATER }
        assertEquals(ScrobbleFlush(0, 0, 0, 3), offline)
        assertEquals(1, ScrobbleQueue(file).pending().first().attempts) // survives a restart, with its attempt counted
        online = true
        val back = ScrobbleQueue(file).flush(now) { sent += it.track; ScrobbleOutcome.SENT }
        assertEquals(3, back.sent); assertEquals(listOf("Track 1", "Track 2", "Track 3"), sent)
        assertTrue(ScrobbleQueue(file).pending().isEmpty())
        assertFalse(file.readText().contains("sk"))
    }

    @Test fun refusalsAndExpiredListensAreDroppedRetriesStopInOrderAndTheQueueIsBounded() = runBlocking<Unit> {
        val queue = ScrobbleQueue(File(temporary.root, "q.json"), maxItems = 3)
        queue.add(listen(0, at = now - ScrobbleQueue.MAX_AGE_SECONDS - 1))
        (1..4).forEach { queue.add(listen(it)) }
        assertEquals(listOf("Track 2", "Track 3", "Track 4"), queue.pending().map { it.track }) // oldest dropped when full
        val result = queue.flush(now) { when (it.track) { "Track 2" -> ScrobbleOutcome.REJECTED; "Track 3" -> throw IOException("offline"); else -> ScrobbleOutcome.SENT } }
        assertEquals(ScrobbleFlush(sent = 0, rejected = 1, expired = 0, remaining = 2), result)
        assertEquals(listOf("Track 3", "Track 4"), queue.pending().map { it.track })
        val expiredQueue = ScrobbleQueue(File(temporary.root, "e.json"))
        expiredQueue.add(listen(9, at = now - ScrobbleQueue.MAX_AGE_SECONDS - 5))
        assertEquals(1, expiredQueue.flush(now) { fail("expired listens are not sent") }.expired)
        assertEquals(ScrobbleOutcome.RETRY_LATER, ScrobbleQueue.outcomeOf(RuntimeException(IOException("reset"))))
        assertEquals(ScrobbleOutcome.REJECTED, ScrobbleQueue.outcomeOf(IllegalStateException("Invalid session")))
        assertEquals(ScrobbleOutcome.SENT, ScrobbleQueue.outcomeOf(null))
        assertFailsWith<IllegalArgumentException> { queue.add(PendingScrobble("lastfm", " ", "x", now)) }
    }

    @Test fun aDamagedQueueFileStartsEmptyWithoutLosingNewListens() = runBlocking<Unit> {
        val file = File(temporary.root, "bad.json").apply { writeText("{broken") }
        val queue = ScrobbleQueue(file)
        assertTrue(queue.pending().isEmpty())
        queue.add(listen(1))
        assertEquals(1, ScrobbleQueue(file).pending().size)
    }
}
