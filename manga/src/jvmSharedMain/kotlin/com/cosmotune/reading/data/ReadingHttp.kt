package com.cosmotune.reading.data

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Call
import okhttp3.Response
import java.io.ByteArrayOutputStream

/** Cancellation closes a blocked socket and waits until the body reader stops before an owned temporary file is removed. */
internal suspend fun <T> Call.awaitReading(block: suspend (Response) -> T): T {
    val call = this
    try {
        return coroutineScope {
            val answer = async(Dispatchers.IO) { call.execute().use { response ->
                currentCoroutineContext().ensureActive(); block(response)
            } }
            try { answer.await() }
            catch (cancelled: CancellationException) { call.cancel(); throw cancelled }
        }
    } catch (failure: IOException) { currentCoroutineContext().ensureActive(); throw failure }
}

internal suspend fun Response.catalogueText(limit: Int): String {
    check(isSuccessful && !isRedirect) { "Book catalogue returned HTTP $code." }
    require(body.contentLength() <= limit) { "Book catalogue response is too large." }
    val charset = body.contentType()?.charset(Charsets.UTF_8) ?: Charsets.UTF_8
    return body.byteStream().use { input ->
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        while (true) {
            currentCoroutineContext().ensureActive()
            val count = input.read(buffer)
            if (count < 0) break
            require(count <= limit - output.size()) { "Book catalogue response is too large." }
            output.write(buffer, 0, count)
        }
        output.toByteArray().toString(charset)
    }
}
