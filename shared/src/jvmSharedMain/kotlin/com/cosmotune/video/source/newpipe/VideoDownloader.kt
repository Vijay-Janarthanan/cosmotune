package com.cosmotune.video.source.newpipe

import com.music.bitchord.data.TrackLog
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import java.util.concurrent.TimeUnit

/**
 * What NewPipe's requests go through when Video asks (plan task Y-4, step 2, decision W1).
 *
 * Its own OkHttp client, built from `OkHttpClient.Builder()` and nothing else: no cookie jar (the default one stores no
 * cookie, so Video never carries a session and never reads the music account's), no interceptor of Music's (the WebDAV one
 * of `Http.client`), connect 10 s, read 30 s, and a call limit of 30 s, because a read limit alone is renewed by every few
 * bytes a throttled response yields and a call could then never end. Request headers are copied as Music's downloader
 * copies them; HTTP 429 becomes NewPipe's `ReCaptchaException` (its own convention); the answer's body is read whole.
 *
 * Every request is logged through [TrackLog] with its method, host, path and time, never its query string (the query holds
 * the visitor and session values of the source).
 */
internal class VideoDownloader(private val client: OkHttpClient = newClient()) : Downloader() {

    override fun execute(request: Request): Response {
        val method = request.httpMethod()
        val body = request.dataToSend()?.toRequestBody()
            ?: if (method == "POST" || method == "PUT" || method == "PATCH") ByteArray(0).toRequestBody() else null
        val builder = okhttp3.Request.Builder()
            .method(method, body)
            .url(request.url())

        var hasUserAgent = false
        request.headers().forEach { (name, values) ->
            if (name.equals("User-Agent", ignoreCase = true) && values.isNotEmpty()) hasUserAgent = true
            when {
                values.size > 1 -> {
                    builder.removeHeader(name)
                    values.forEach { builder.addHeader(name, it) }
                }
                values.size == 1 -> builder.header(name, values[0])
            }
        }
        if (!hasUserAgent) builder.header("User-Agent", DEFAULT_USER_AGENT)

        val label = "${request.httpMethod()} ${hostAndPath(request.url())}"
        val started = System.nanoTime()
        val response = try {
            client.newCall(builder.build()).execute()
        } catch (e: Exception) {
            TrackLog.w(TAG, "video fetch FAILED after ${elapsedMs(started)}ms $label: ${e.javaClass.simpleName}")
            throw e
        }
        response.use { r ->
            TrackLog.d(TAG, "video fetch ${elapsedMs(started)}ms ${r.code} $label")
            if (r.code == 429) throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            return Response(
                r.code,
                r.message,
                r.headers.toMultimap(),
                r.body.string(),
                r.request.url.toString(),
            )
        }
    }

    private fun elapsedMs(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / 1_000_000

    companion object {
        private const val TAG = "CosmoVideo"

        /**
         * The User-Agent of NewPipe's own `DownloaderTestImpl` at v0.26.3 (read from the tag): the latest Firefox ESR of
         * its time. Used when the extractor sends none.
         */
        const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:140.0) Gecko/20100101 Firefox/140.0"

        /** No cookie jar, no interceptors; 10 s to connect, 30 s to read, 30 s for a whole call. */
        fun newClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(30, TimeUnit.SECONDS)
            .build()

        /** `host/path` of [url] without scheme, user info, port, query or fragment ("www.youtube.com/youtubei/v1/next"), or "(unparsed)". */
        internal fun hostAndPath(url: String): String {
            val parsed = url.toHttpUrlOrNull() ?: return "(unparsed)"
            return parsed.host + parsed.encodedPath
        }
    }
}
