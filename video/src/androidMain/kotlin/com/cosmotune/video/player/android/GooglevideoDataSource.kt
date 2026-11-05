package com.cosmotune.video.player.android

import android.net.Uri
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.cosmotune.video.player.net.GooglevideoRequests
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * The HTTP data source of Video's player (spec 6.2, "Requests"), written after NewPipe's `YoutubeHttpDataSource` and
 * applying the rules of [GooglevideoRequests] (Y-2):
 *
 * - every request carries the headers of [VideoRequestHeaders.current] (the User-Agent the stream set was minted for);
 * - a `videoplayback` request of a set with the range rules gets `&rn=<n>` (a counter of this instance), its byte range as
 *   `&range=<start>-<end>` instead of a `Range` header (the response is then read from its position 0), and goes out as a
 *   POST with the body [GooglevideoRequests.POST_BODY];
 * - if the first response of a stream set is a 400 or a 405, the set switches to a GET with a `Range` header (Music's way), the
 *   request is sent again that way at once, and the choice is remembered for the set ([VideoRequestHeaders.StreamSetRequests]);
 * - a 403 is not handled here: it surfaces as [HttpDataSource.InvalidResponseCodeException] with the code 403, which the engine
 *   reports as `SourceExpired` (spec 6.1).
 *
 * The [upstream] is an [OkHttpDataSource] on Video's own [client]: built from `OkHttpClient.Builder()`, so it has no cookie jar,
 * and no interceptor adds anything of an account. The factory sets no User-Agent of its own: OkHttpDataSource would append it
 * after the request's, and the request would carry two.
 */
@OptIn(UnstableApi::class)
class GooglevideoDataSource(private val upstream: HttpDataSource) : DataSource {

    /** Makes one [GooglevideoDataSource] per loader, each over its own [OkHttpDataSource] on [client]. */
    class Factory(private val upstream: HttpDataSource.Factory = OkHttpDataSource.Factory(client)) : DataSource.Factory {
        override fun createDataSource(): DataSource = GooglevideoDataSource(upstream.createDataSource())
    }

    /** The `rn` of the next `videoplayback` request of this instance (NewPipe starts at 0 and counts every request). */
    private var requestNumber = 0

    /** What is left of the bytes the caller asked for, when the request was turned into `&range=`; [C.LENGTH_UNSET] otherwise. */
    private var bytesRemaining = C.LENGTH_UNSET.toLong()

    override fun addTransferListener(transferListener: TransferListener) {
        upstream.addTransferListener(transferListener)
    }

    override fun open(dataSpec: DataSpec): Long {
        bytesRemaining = C.LENGTH_UNSET.toLong()
        val set = VideoRequestHeaders.current
        val headers = if (set.values.isEmpty()) dataSpec.httpRequestHeaders else set.values + dataSpec.httpRequestHeaders
        val url = dataSpec.uri.toString()
        if (!set.rangeRules || !GooglevideoRequests.isVideoplayback(url)) {
            return upstream.open(dataSpec.buildUpon().setHttpRequestHeaders(headers).build())
        }
        val numbered = GooglevideoRequests.withRequestNumber(url, requestNumber++)
        val endInclusive = endOf(dataSpec)
        // A set that refused the POST, and a read whose end is not known (no length, no clen), go the GET way with a Range header.
        if (set.useGet || endInclusive == null) return openGet(dataSpec, numbered, headers)

        val post = dataSpec.buildUpon()
            .setUri(Uri.parse(GooglevideoRequests.withRange(numbered, dataSpec.position, endInclusive)))
            // the range is in the address now: the response starts at the first byte asked for, and no Range header goes out
            .setPosition(0)
            .setLength(C.LENGTH_UNSET.toLong())
            .setHttpMethod(DataSpec.HTTP_METHOD_POST)
            .setHttpBody(GooglevideoRequests.POST_BODY)
            .setHttpRequestHeaders(headers)
            .build()
        val opened = try {
            upstream.open(post)
        } catch (e: HttpDataSource.InvalidResponseCodeException) {
            if ((e.responseCode == 400 || e.responseCode == 405) && !set.postConfirmed) {
                upstream.close()
                set.useGet = true
                return openGet(dataSpec, GooglevideoRequests.withRequestNumber(url, requestNumber++), headers)
            }
            throw e
        }
        set.postConfirmed = true
        val asked = endInclusive - dataSpec.position + 1
        bytesRemaining = if (opened == C.LENGTH_UNSET.toLong()) asked else minOf(opened, asked)
        return bytesRemaining
    }

    /** Music's way: a GET of the numbered address, the byte range as a `Range` header (OkHttpDataSource writes it from the spec). */
    private fun openGet(dataSpec: DataSpec, numberedUrl: String, headers: Map<String, String>): Long {
        bytesRemaining = C.LENGTH_UNSET.toLong()
        return upstream.open(
            dataSpec.buildUpon()
                .setUri(Uri.parse(numberedUrl))
                .setHttpMethod(DataSpec.HTTP_METHOD_GET)
                .setHttpBody(null)
                .setHttpRequestHeaders(headers)
                .build(),
        )
    }

    /** The last byte [dataSpec] asks for: from its length, else from the stream's `clen`; null when neither is known. */
    private fun endOf(dataSpec: DataSpec): Long? {
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec.position + dataSpec.length - 1
        val total = dataSpec.uri.getQueryParameter("clen")?.toLongOrNull() ?: return null
        return if (total > dataSpec.position) total - 1 else null
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (bytesRemaining == C.LENGTH_UNSET.toLong()) return upstream.read(buffer, offset, length)
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val read = upstream.read(buffer, offset, minOf(length.toLong(), bytesRemaining).toInt())
        if (read == C.RESULT_END_OF_INPUT) {
            bytesRemaining = 0L
            return read
        }
        bytesRemaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        bytesRemaining = C.LENGTH_UNSET.toLong()
        upstream.close()
    }

    companion object {
        /**
         * Video's own HTTP client for the stream bytes and the notification's thumbnail: built from `OkHttpClient.Builder()`, so it
         * keeps no cookies (its cookie jar is OkHttp's empty one) and shares nothing with Music's client.
         */
        val client: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(15, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .build()
        }
    }
}
