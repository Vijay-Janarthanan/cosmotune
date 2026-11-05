package com.cosmotune.youtube.account

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType

/**
 * The two kinds of request [YouTubeSession] makes on its own to keep the sign-in alive: a cookie rotation and a look at a signed-in
 * page. An interface so a test answers them without a network.
 */
interface SessionTransport {
    /** POSTs Google's cookie-rotation call to [url] with [cookie]; the answer's status and `Set-Cookie` values. */
    suspend fun rotate(url: String, cookie: String, origin: String): Answer

    /** GETs [url] with [cookie] and [authorization]; the answer's status, body and `Set-Cookie` values. */
    suspend fun page(url: String, cookie: String, authorization: String?): Answer

    /** One answer. [body] is empty for a rotation. */
    data class Answer(val status: Int, val setCookies: List<String>, val body: String = "")
}

/**
 * [SessionTransport] over the app's shared OkHttp client (the one ExoPlayer and Innertube use). Never throws an HTTP status: the
 * status is the answer. Cookie values are never logged here; a failure is reported by the caller by its message alone, which names
 * no header.
 */
class KtorSessionTransport : SessionTransport {
    private val client = HttpClient(OkHttp) {
        engine { preconfigured = com.cosmotune.music.data.Http.client }
        install(HttpTimeout) {
            requestTimeoutMillis = 15_000
            connectTimeoutMillis = 10_000
            socketTimeoutMillis = 15_000
        }
        expectSuccess = false
    }

    override suspend fun rotate(url: String, cookie: String, origin: String): SessionTransport.Answer {
        val response = client.post(url) {
            header("User-Agent", USER_AGENT)
            header("Cookie", cookie)
            header("Origin", origin)
            header("Referer", "$origin/")
            contentType(ContentType.Application.Json)
            setBody(ROTATE_BODY)
        }
        return SessionTransport.Answer(response.status.value, response.headers.getAll(HttpHeaders.SetCookie).orEmpty())
    }

    override suspend fun page(url: String, cookie: String, authorization: String?): SessionTransport.Answer {
        val response = client.get(url) {
            header("User-Agent", USER_AGENT)
            header("Accept-Language", "en-US,en;q=0.9")
            header("Cookie", cookie)
            authorization?.let { header("Authorization", it) }
        }
        return SessionTransport.Answer(response.status.value, response.headers.getAll(HttpHeaders.SetCookie).orEmpty(), response.bodyAsText())
    }

    companion object {
        /** The body the site's own page sends with the rotation call. */
        const val ROTATE_BODY = """[000,"-0000000000000000000"]"""

        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36"
    }
}

/**
 * A platform's last way to bring a refused session back before it is declared expired: a fresher copy of the same login from
 * wherever the platform keeps one (the phone's WebView cookie store, the desktop browser the login was imported from).
 */
fun interface SessionRecovery {
    /**
     * A fresher `Cookie` header for the login of [current], or null when there is none. A jar of another login is refused by
     * [YouTubeSession] whatever this returns.
     */
    suspend fun freshCookie(current: String): Recovered?

    /** The YouTube jar, and the accounts.google.com one when the platform has it. */
    data class Recovered(val youtube: String, val google: String? = null)
}
