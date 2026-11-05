package com.cosmotune.video.signed

import com.cosmotune.music.data.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * The one kind of request a signed player call makes. An interface so a test can answer it without a network and read what was sent,
 * as [com.cosmotune.youtube.account.SessionTransport] does for the refresh. Never throws an HTTP status: the status is the answer.
 */
internal fun interface SignedHttp {
    suspend fun post(url: String, headers: Map<String, String>, body: String): Answer

    /** One answer: its status, its `Set-Cookie` values and its text. */
    class Answer(val status: Int, val setCookies: List<String>, val body: String)
}

/**
 * [SignedHttp] over the app's shared client, not Video's cookie-less [VideoDownloader][com.cosmotune.video.source.newpipe.VideoDownloader],
 * which stays what anonymous NewPipe goes through. Nothing of a request's headers or body is logged here.
 */
internal object OkHttpSignedHttp : SignedHttp {
    private val JSON = "application/json".toMediaType()

    override suspend fun post(url: String, headers: Map<String, String>, body: String): SignedHttp.Answer = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).post(body.toRequestBody(JSON)).apply { headers.forEach { (name, value) -> header(name, value) } }.build()
        Http.client.newCall(request).execute().use { response ->
            SignedHttp.Answer(response.code, response.headers("Set-Cookie"), response.body.string())
        }
    }
}
