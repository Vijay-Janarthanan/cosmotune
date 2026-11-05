package com.cosmotune.cloud

import com.opencanvas.core.stream.fetch
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/** The OAuth client made for the desktop app in the Google Cloud console. Google issues a secret even to installed apps; it is not confidential. */
data class GoogleOAuthConfig(
    val clientId: String,
    val clientSecret: String,
    val authEndpoint: String = "https://accounts.google.com/o/oauth2/v2/auth",
    val tokenEndpoint: String = "https://oauth2.googleapis.com/token",
) {
    val isConfigured: Boolean get() = clientId.isNotBlank()
}

/** What the browser needs and what the exchange needs afterwards. */
class PendingGoogleSignIn(val authUrl: String, internal val verifier: String, internal val state: String, internal val redirectUri: String)

/**
 * Google sign-in for desktop, the way Google asks native apps to do it: the system browser, a one-time page on this computer to
 * receive the answer, and PKCE so a code seen by another program is useless. The result is a Google ID token, which
 * [FirebaseAuthClient.signInWithGoogle] turns into the account.
 */
class GoogleDesktopSignIn(private val config: GoogleOAuthConfig, private val client: OkHttpClient) {
    private val json = Json { ignoreUnknownKeys = true }
    private val random = SecureRandom()

    fun begin(redirectUri: String): PendingGoogleSignIn {
        val verifier = randomUrlSafe(48)
        val state = randomUrlSafe(24)
        val query = listOf(
            "client_id" to config.clientId,
            "redirect_uri" to redirectUri,
            "response_type" to "code",
            "scope" to "openid email profile",
            "code_challenge" to challenge(verifier),
            "code_challenge_method" to "S256",
            "state" to state,
            "prompt" to "select_account",
        ).joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
        return PendingGoogleSignIn("${config.authEndpoint}?$query", verifier, state, redirectUri)
    }

    /** Trades the code for Google's ID token. */
    suspend fun complete(pending: PendingGoogleSignIn, code: String): String {
        val form = FormBody.Builder()
            .add("code", code).add("client_id", config.clientId).add("client_secret", config.clientSecret)
            .add("code_verifier", pending.verifier).add("redirect_uri", pending.redirectUri).add("grant_type", "authorization_code")
            .build()
        val answer = client.newCall(Request.Builder().url(config.tokenEndpoint).post(form).build()).fetch()
        val body = runCatching { json.parseToJsonElement(answer.text()).jsonObject }.getOrNull()
        if (!answer.isSuccessful) throw CloudException(body?.get("error")?.jsonPrimitive?.contentOrNull ?: "HTTP_${answer.code}", body?.get("error_description")?.jsonPrimitive?.contentOrNull ?: "Google refused the sign-in", answer.code)
        return body?.get("id_token")?.jsonPrimitive?.contentOrNull ?: throw CloudException("NO_ID_TOKEN", "Google sent no ID token", answer.code)
    }

    private fun randomUrlSafe(bytes: Int): String = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(bytes).also(random::nextBytes))

    companion object {
        fun challenge(verifier: String): String =
            Base64.getUrlEncoder().withoutPadding().encodeToString(MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)))
    }
}

/** A one-shot web page on 127.0.0.1 that catches the browser's return trip, shows "you can close this tab", and shuts down. */
class LoopbackReceiver private constructor(private val server: HttpServer, val redirectUri: String, private val result: CompletableDeferred<Map<String, String>>) {
    /** The query of the first request that carries a `code` or an `error`, or a timeout. */
    suspend fun await(timeoutMs: Long): Map<String, String> = try {
        withTimeout(timeoutMs) { result.await() }
    } finally {
        server.stop(0)
    }

    fun close() = server.stop(0)

    companion object {
        const val PAGE = "<!doctype html><meta charset=utf-8><title>Cosmotune</title><body style=\"font-family:sans-serif;text-align:center;margin-top:20vh\"><h2>Signed in</h2><p>You can close this tab and go back to Cosmotune.</p>"

        suspend fun start(): LoopbackReceiver = withContext(Dispatchers.IO) {
            val result = CompletableDeferred<Map<String, String>>()
            val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
            server.createContext("/") { exchange ->
                val query = exchange.requestURI.rawQuery.orEmpty().split('&').filter { it.contains('=') }
                    .associate { it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), "UTF-8") }
                val relevant = "code" in query || "error" in query
                val body = (if (relevant) PAGE else "").toByteArray()
                exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
                exchange.sendResponseHeaders(if (relevant) 200 else 404, if (body.isEmpty()) -1 else body.size.toLong())
                if (body.isNotEmpty()) exchange.responseBody.use { it.write(body) }
                exchange.close()
                if (relevant) result.complete(query)
            }
            server.start()
            LoopbackReceiver(server, "http://127.0.0.1:${server.address.port}", result)
        }
    }
}

/** The whole desktop flow, with the browser opening left to the caller. Returns the Google ID token. */
suspend fun GoogleDesktopSignIn.signInThroughBrowser(openBrowser: (String) -> Unit, timeoutMs: Long = 180_000L): String {
    val receiver = LoopbackReceiver.start()
    try {
        val pending = begin(receiver.redirectUri)
        openBrowser(pending.authUrl)
        val answer = receiver.await(timeoutMs)
        answer["error"]?.let { throw CloudException(it, "Sign-in was cancelled or refused", 400) }
        if (answer["state"] != pending.state) throw CloudException("STATE_MISMATCH", "The sign-in answer did not match the request", 400)
        return complete(pending, answer["code"] ?: throw CloudException("NO_CODE", "No code came back", 400))
    } finally {
        receiver.close()
    }
}
