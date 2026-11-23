package com.cosmotune.media.segments

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Sending a segment marked by hand, against a local server. */
class SegmentSubmitTest {
    private lateinit var server: MockWebServer
    private val video = "dQw4w9WgXcQ"
    private val submission = SegmentSubmission(video, "u".repeat(36), 212_000, 10_500, 42_250, SegmentCategory.SPONSOR)

    @BeforeTest fun start() { server = MockWebServer().also { it.start() } }

    @AfterTest fun stop() { runCatching { server.shutdown() } }

    private fun client() = SponsorBlockClient(baseUrl = server.url("/").toString().trimEnd('/'), http = OkHttpClient(), userAgent = "Cosmotune/test")

    @Test fun theBodyIsSponsorBlocksJsonForm() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        assertEquals(SubmitResult.SENT, runBlocking { client().submit(submission) })
        val request = server.takeRequest(5, TimeUnit.SECONDS)!!
        assertEquals("POST", request.method)
        assertEquals("/api/skipSegments", request.requestUrl!!.encodedPath)
        val body = Json.parseToJsonElement(request.body.readUtf8()).jsonObject
        assertEquals(video, body["videoID"]!!.jsonPrimitive.content)
        assertEquals("Cosmotune/test", body["userAgent"]!!.jsonPrimitive.content)
        assertEquals(212.0, body["videoDuration"]!!.jsonPrimitive.content.toDouble())
        val segment = body["segments"]!!.jsonArray.single().jsonObject
        assertEquals(listOf(10.5, 42.25), segment["segment"]!!.jsonArray.map { it.jsonPrimitive.content.toDouble() })
        assertEquals("sponsor", segment["category"]!!.jsonPrimitive.content)
        assertEquals("skip", segment["actionType"]!!.jsonPrimitive.content)
    }

    @Test fun theAnswersBecomePlainResults() {
        for ((code, result) in listOf(409 to SubmitResult.ALREADY_THERE, 429 to SubmitResult.TOO_MANY, 403 to SubmitResult.REFUSED, 500 to SubmitResult.FAILED)) {
            server.enqueue(MockResponse().setResponseCode(code))
            assertEquals(result, runBlocking { client().submit(submission) }, "answer $code")
        }
    }

    @Test fun aSegmentThatCannotBeSentIsNotSent() {
        assertFalse(submission.copy(endMs = submission.startMs + 400).isValid, "under a second")
        assertFalse(submission.copy(category = SegmentCategory.POI_HIGHLIGHT).isValid, "not skippable")
        assertFalse(submission.copy(endMs = 300_000).isValid, "past the end")
        assertTrue(submission.isValid)
        assertEquals(SubmitResult.REFUSED, runBlocking { client().submit(submission.copy(endMs = submission.startMs)) })
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS))
    }

    @Test fun onlySkippableCategoriesCanBeMarked() {
        assertFalse(SegmentCategory.EXCLUSIVE_ACCESS in SegmentSubmission.CATEGORIES)
        assertTrue(SegmentCategory.SPONSOR in SegmentSubmission.CATEGORIES && SegmentCategory.FILLER in SegmentSubmission.CATEGORIES)
    }
}
