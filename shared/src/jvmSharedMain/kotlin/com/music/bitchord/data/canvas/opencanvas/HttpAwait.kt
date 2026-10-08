package com.opencanvas.core.stream

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Headers
import okhttp3.Response
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A complete HTTP answer: status, headers and the body read in full (at most the requested limit). */
internal class Fetched(val code: Int, val headers: Headers, val body: ByteArray, val contentLength: Long) {
    val isSuccessful: Boolean get() = code in 200..299

    fun text(): String = body.decodeToString()
}

/**
 * Runs this call without blocking a caller's thread and reads the whole answer, body included, on
 * the HTTP client's own thread. Cancelling the calling coroutine cancels the call itself, which
 * aborts even a body read that is stuck mid-stream - so a skipped song or a losing candidate stops
 * at once instead of waiting for a socket timeout.
 *
 * @param limit Read at most this many body bytes and drop the rest of the answer.
 */
internal suspend fun Call.fetch(limit: Long = Long.MAX_VALUE): Fetched = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            try {
                response.use { answer ->
                    val body = answer.body
                    val bytes = if (body == null) {
                        ByteArray(0)
                    } else {
                        val source = body.source()
                        if (limit == Long.MAX_VALUE) source.readByteArray() else source.run { request(limit); buffer.readByteArray(minOf(limit, buffer.size)) }
                    }
                    if (continuation.isActive) continuation.resume(Fetched(answer.code, answer.headers, bytes, body?.contentLength() ?: -1L))
                }
            } catch (failure: IOException) {
                if (continuation.isActive) continuation.resumeWithException(failure)
            }
        }

        override fun onFailure(call: Call, e: IOException) {
            if (continuation.isActive) continuation.resumeWithException(e)
        }
    })
}
