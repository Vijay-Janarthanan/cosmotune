package com.cosmotune.cloud

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GoogleDesktopSignInTest {
    @Test fun theChallengeIsTheRfcExample() {
        assertEquals("E9Melhoa2OwvFrEMTJguCHaoeK1t8URWbuGJSstw-cM", GoogleDesktopSignIn.challenge("dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk"))
    }

    @Test fun theAddressAsksForAnIdTokenWithPkceAndAState() {
        val flow = GoogleDesktopSignIn(GoogleOAuthConfig("client-1", "secret-1"), OkHttpClient())
        val url = flow.begin("http://127.0.0.1:5555").authUrl
        assertTrue(url.startsWith("https://accounts.google.com/o/oauth2/v2/auth?"))
        listOf("client_id=client-1", "response_type=code", "code_challenge_method=S256", "scope=openid+email+profile", "state=", "redirect_uri=http%3A%2F%2F127.0.0.1%3A5555")
            .forEach { assertTrue(url.contains(it), "missing $it in $url") }
    }

    @Test fun theCodeIsTradedForTheIdTokenWithTheVerifier() = runBlocking<Unit> {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setBody("""{"id_token":"google-id-token","access_token":"x"}"""))
            val flow = GoogleDesktopSignIn(GoogleOAuthConfig("client-1", "secret-1", tokenEndpoint = server.url("/token").toString()), OkHttpClient())
            val pending = flow.begin("http://127.0.0.1:5555")
            assertEquals("google-id-token", flow.complete(pending, "the-code"))
            val sent = server.takeRequest().body.readUtf8()
            listOf("code=the-code", "grant_type=authorization_code", "code_verifier=", "client_secret=secret-1").forEach { assertTrue(sent.contains(it), it) }
        } finally {
            server.shutdown()
        }
    }

    @Test fun aRefusedExchangeIsAnError() = runBlocking<Unit> {
        val server = MockWebServer().apply { start() }
        try {
            server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"invalid_grant","error_description":"Bad code"}"""))
            val flow = GoogleDesktopSignIn(GoogleOAuthConfig("c", "s", tokenEndpoint = server.url("/token").toString()), OkHttpClient())
            val failure = assertFailsWith<CloudException> { flow.complete(flow.begin("http://127.0.0.1:1"), "bad") }
            assertEquals("invalid_grant", failure.code)
        } finally {
            server.shutdown()
        }
    }

    @Test fun theReceiverCatchesTheBrowsersReturnTripOnce() = runBlocking<Unit> {
        val receiver = LoopbackReceiver.start()
        val http = OkHttpClient()
        // a stray request (a favicon, a scanner) is ignored and does not end the wait
        http.newCall(Request.Builder().url("${receiver.redirectUri}/favicon.ico").build()).execute().use { assertEquals(404, it.code) }
        http.newCall(Request.Builder().url("${receiver.redirectUri}/?code=abc%2F1&state=xyz").build()).execute().use {
            assertEquals(200, it.code)
            assertTrue(it.body!!.string().contains("You can close this tab"))
        }
        assertEquals(mapOf("code" to "abc/1", "state" to "xyz"), receiver.await(5_000))
    }
}
