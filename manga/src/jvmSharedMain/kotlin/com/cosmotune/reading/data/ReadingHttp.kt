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
