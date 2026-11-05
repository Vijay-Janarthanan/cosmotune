package com.cosmotune.video.feed.rss

import com.cosmotune.video.feed.FeedFetcher
import com.cosmotune.video.feed.FeedPolicy
import com.cosmotune.video.feed.FeedResponse
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Dispatcher
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Reads a feed address over HTTP (plan task Y-9). The client is built from `OkHttpClient.Builder()` and nothing else: no
 * cookie jar, no interceptor that adds an account, and not the app's shared client (Video never reads the music account).
 * Cancelling the calling coroutine cancels the call. Any HTTP answer is returned with its status; only a request that got no
 * answer at all throws an [IOException].
 */
class OkHttpFeedFetcher(private val client: OkHttpClient = defaultClient()) : FeedFetcher {
    override suspend fun get(url: String): FeedResponse = suspendCancellableCoroutine { continuation ->
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", USER_AGENT)
            .header("Accept", "application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5")
            .get()
            .build()
        val call = client.newCall(request)
        continuation.invokeOnCancellation { call.cancel() }
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (continuation.isActive) continuation.resumeWithException(e)
            }

            override fun onResponse(call: Call, response: Response) {
                try {
                    response.use { answer ->
                        val text = readLimited(answer)
                        if (continuation.isActive) continuation.resume(FeedResponse(answer.code, text))
                    }
                } catch (failure: IOException) {
                    if (continuation.isActive) continuation.resumeWithException(failure)
                }
            }
        })
    }

    private fun readLimited(answer: Response): String {
        val body: ResponseBody? = answer.body
        if (body == null) return ""
        val source = body.source()
        source.request(MAX_BODY_BYTES)
        return source.buffer.readByteArray(minOf(MAX_BODY_BYTES, source.buffer.size)).decodeToString()
    }

    companion object {
        /** A feed is a few tens of kilobytes; whatever is longer than this is cut off and read as far as it goes. */
        const val MAX_BODY_BYTES: Long = 2L * 1024 * 1024

        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; rv:128.0) Gecko/20100101 Firefox/128.0"

        /**
         * A plain client: no cookie jar (OkHttp's default keeps none), timeouts of 10 s to connect, 20 s to read and 30 s for
         * the whole call, and room for the six channels the engine reads at once.
         */
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .dispatcher(Dispatcher().apply { maxRequestsPerHost = FeedPolicy.CONCURRENCY; maxRequests = FeedPolicy.CONCURRENCY * 2 })
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}
