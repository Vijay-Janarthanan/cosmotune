package com.cosmotune.video.segments

import com.cosmotune.CosmoConfig
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.Call
import okhttp3.Callback
import okhttp3.CookieJar
import okhttp3.HttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.roundToLong

/**
 * What the three web-service clients (SponsorBlock, DeArrow, Return YouTube Dislike) share: one OkHttp client with 10 s
 * timeouts and no cookie jar, built on its own (Video never reads the music account's session), the `User-Agent`, and a
 * suspending GET that cancels the call when the coroutine is cancelled.
 */
object WebServicesHttp {
    /** The connect, read, write and whole-call timeout of every request (plan task Y-6: 10 s). */
    const val TIMEOUT_SECONDS = 10L

    /** An answer larger than this is treated as a failure (the biggest real ones are some tens of kilobytes). */
    const val MAX_BODY_BYTES = 4L * 1024 * 1024

    private const val DEFAULT_VERSION = "1.0.0"

    /** The client the clients use unless given another: a fresh `OkHttpClient.Builder()`, no cookie jar, 10 s timeouts. */
    val client: OkHttpClient by lazy { tuned(OkHttpClient.Builder().build()) }

    /** [http] with the timeouts and the empty cookie jar of this task, sharing its connection pool and dispatcher. */
    fun tuned(http: OkHttpClient): OkHttpClient = http.newBuilder()
        .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .callTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .cookieJar(CookieJar.NO_COOKIES)
        .build()

    /**
     * `Cosmotune/<version>`. [version] is the app's; the desktop application sets the `cosmotune.version` property, and
     * without either the build's `1.0.0` stands (CosmoConfig holds the name, not a version).
     */
    fun userAgent(version: String? = null): String =
        "${CosmoConfig.APP_NAME}/${version ?: System.getProperty("cosmotune.version") ?: DEFAULT_VERSION}"

    /** Whether [id] looks like a video id: 1 to 64 characters of letters, digits, `-` and `_`. Anything else is never sent. */
    internal fun isVideoId(id: String): Boolean =
        id.length in 1..64 && id.all { (it in 'a'..'z') || (it in 'A'..'Z') || (it in '0'..'9') || it == '-' || it == '_' }

    /**
     * GETs [url] with the agent and returns the status and, for a 2xx answer, the body as text. Throws [IOException] when
     * the connection fails or the body is larger than [MAX_BODY_BYTES]; cancelling the coroutine cancels the call.
     */
    internal suspend fun get(http: OkHttpClient, url: HttpUrl, userAgent: String): WebAnswer = suspendCancellableCoroutine { continuation ->
        val call = http.newCall(
            Request.Builder().url(url).header("User-Agent", userAgent).header("Accept", "application/json").get().build(),
        )
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { answer ->
                        val body: ResponseBody? = answer.body
                        val text = if (answer.isSuccessful && body != null) readLimited(body) else ""
                        if (continuation.isActive) continuation.resume(WebAnswer(answer.code, text))
                    }
                } catch (e: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(e)
                }
            }
        })
    }

    private fun readLimited(body: ResponseBody): String {
        val source = body.source()
        source.request(MAX_BODY_BYTES + 1)
        if (source.buffer.size > MAX_BODY_BYTES) throw IOException("answer larger than $MAX_BODY_BYTES bytes")
        return source.readString(Charsets.UTF_8)
    }
}

/** A web service's answer: the HTTP status and, when it was 2xx, the body. */
internal class WebAnswer(val code: Int, val body: String) {
    val isSuccessful: Boolean get() = code in 200..299
}

// ---- tolerant readers for the services' JSON ----

internal fun JsonElement?.asString(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

/** A JSON number as a double; a quoted number or any other value is null. */
internal fun JsonElement?.asDouble(): Double? = (this as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it.isFinite() }

internal fun JsonElement?.asLong(): Long? = asDouble()?.roundToLong()

/** A JSON boolean, or a number where 0 is false (SponsorBlock writes `locked` as 0 or 1). */
internal fun JsonElement?.asBoolean(): Boolean? {
    val primitive = (this as? JsonPrimitive)?.takeIf { !it.isString } ?: return null
    return primitive.booleanOrNull ?: primitive.doubleOrNull?.let { it != 0.0 }
}

/** Seconds, as the services give them, in milliseconds. */
internal fun JsonElement?.asMillis(): Long? = asDouble()?.let { (it * 1000).roundToLong() }
