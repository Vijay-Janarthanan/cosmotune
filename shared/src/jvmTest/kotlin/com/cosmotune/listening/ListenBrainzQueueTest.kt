package com.cosmotune.listening

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** ListenBrainz listens through the shared offline queue, against a local HTTP server. */
class ListenBrainzQueueTest {
    @get:Rule val temporary = TemporaryFolder()
    private val server = MockWebServer().apply { start() }
    private val submitter = ListenBrainzSubmitter(OkHttpClient(), server.url("/1/submit-listens").toString())
    private val now = System.currentTimeMillis() / 1000

    @After fun stop() = server.shutdown()

    private fun listen(track: String, ago: Long) = PendingScrobble(ListenBrainzSubmitter.SERVICE, "A. R. Rahman", track, now - ago, "Bombay", 335)

    @Test fun failedListensWaitAndGoOutFirstInListeningOrder() = runBlocking<Unit> {
        val file = File(temporary.root, "queue.json")
        val queue = ScrobbleQueue(file)
        queue.add(PendingScrobble("lastfm", "Someone", "Kept for Last.fm", now - 60))

        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(ScrobbleOutcome.RETRY_LATER, submitter.submitWithQueue(queue, "token-1", listen("Kannalane", 300)))
        // Offline is the same as a server error.
        assertEquals(ScrobbleOutcome.RETRY_LATER, ListenBrainzSubmitter(OkHttpClient(), "http://127.0.0.1:1/").submitWithQueue(queue, "token-1", listen("Humma", 200)))
        assertEquals(listOf("Kept for Last.fm", "Kannalane", "Humma"), queue.pending().map { it.track })
        assertFalse(file.readText().contains("token-1"))

        repeat(3) { server.enqueue(MockResponse().setResponseCode(200)) }
        assertEquals(ScrobbleOutcome.SENT, submitter.submitWithQueue(queue, "token-1", listen("Roja", 100)))
        assertEquals(listOf("Kept for Last.fm"), queue.pending().map { it.track }) // the Last.fm listen was not touched

        server.takeRequest() // the 503
        val sent = List(3) { server.takeRequest() }
        assertTrue(sent.all { it.getHeader("Authorization") == "Token token-1" })
        val payloads = sent.map { Json.parseToJsonElement(it.body.readUtf8()).jsonObject["payload"]!!.jsonArray.single().jsonObject }
        assertEquals(listOf("Kannalane", "Humma", "Roja"), payloads.map { it["track_metadata"]!!.jsonObject["track_name"]!!.jsonPrimitive.content })
        assertEquals(now - 300, payloads[0]["listened_at"]!!.jsonPrimitive.long)
        assertEquals(335_000L, payloads[0]["track_metadata"]!!.jsonObject["additional_info"]!!.jsonObject["duration_ms"]!!.jsonPrimitive.long)
    }

    @Test fun invalidListensAreDroppedAndAWrongTokenWaitsForAFix() = runBlocking<Unit> {
        val queue = ScrobbleQueue(File(temporary.root, "queue.json"))
        server.enqueue(MockResponse().setResponseCode(400))
        assertEquals(ScrobbleOutcome.REJECTED, submitter.submitWithQueue(queue, "token", listen("Bad", 10)))
        assertTrue(queue.pending().isEmpty())

        server.enqueue(MockResponse().setResponseCode(401))
        assertEquals(ScrobbleOutcome.RETRY_LATER, submitter.submitWithQueue(queue, "old-token", listen("Waits", 10)))
        assertEquals(ScrobbleOutcome.RETRY_LATER, submitter.submitWithQueue(queue, "", listen("No token yet", 5)))
        server.enqueue(MockResponse().setResponseCode(200)); server.enqueue(MockResponse().setResponseCode(200))
        assertEquals(2, submitter.flush(queue, "new-token").sent)
        assertTrue(queue.pending().isEmpty())

        // ListenBrainz keeps older listens than Last.fm does: a 30-day-old listen is still sent, a year-old one is dropped.
        queue.add(listen("Month old", 30L * 24 * 3600)); queue.add(listen("Year old", 365L * 24 * 3600))
        server.enqueue(MockResponse().setResponseCode(200))
        val flushed = submitter.flush(queue, "new-token")
        assertEquals(1, flushed.sent); assertEquals(1, flushed.expired)
    }
}
