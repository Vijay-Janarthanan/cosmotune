package com.cosmotune.video.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.request.get
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** The mapping of real Ktor failures (an HTTP status, a refused connection) to [AccountFailure], through a local server. */
class FailureMappingTest {
    private fun failureFor(response: MockResponse): AccountFailure = runBlocking {
        val server = MockWebServer()
        server.enqueue(response)
        server.start()
        try {
            HttpClient(OkHttp) { expectSuccess = true }.use { client ->
                try {
                    client.get(server.url("/").toString())
                    error("expected a failure")
                } catch (e: io.ktor.client.plugins.ResponseException) {
                    failureOf(e)
                }
            }
        } finally {
            server.shutdown()
        }
    }

    @Test fun unauthorizedAndForbiddenAreAnExpiredSession() {
        assertIs<AccountFailure.SessionExpired>(failureFor(MockResponse().setResponseCode(401)))
        assertIs<AccountFailure.SessionExpired>(failureFor(MockResponse().setResponseCode(403)))
    }

    @Test fun tooManyRequestsCarriesRetryAfter() {
        val failure = failureFor(MockResponse().setResponseCode(429).addHeader("Retry-After", "45"))
        assertEquals(45L, assertIs<AccountFailure.RateLimited>(failure).retryAfterSec)
    }

    @Test fun otherClientErrorsAreChangedAndServerErrorsAreNetwork() {
        assertIs<AccountFailure.Changed>(failureFor(MockResponse().setResponseCode(400)))
        assertIs<AccountFailure.Network>(failureFor(MockResponse().setResponseCode(503)))
    }

    @Test fun transportAndParsingErrorsAreTyped() {
        assertIs<AccountFailure.Network>(failureOf(IOException("reset")))
        assertIs<AccountFailure.Changed>(failureOf(kotlinx.serialization.SerializationException("x")))
        assertIs<AccountFailure.Network>(failureOf(RuntimeException("odd")))
        val own = AccountFailure.NotSignedIn()
        assertEquals(own, failureOf(own))
    }
}
