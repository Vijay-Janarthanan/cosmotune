package com.opencanvas.core.stream

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.io.IOException

/**
 * Runs [block] once per entry of [timeoutsMs], each time with that entry as its time limit, and
 * returns the first result; an attempt that hangs or fails with an I/O error is followed by the next.
 *
 * A connection that stops answering is the usual cause of a slow start, and a fresh connection
 * almost always answers at once, so a short first limit and a longer last one cost far less than
 * waiting out the client's read timeout. The limit is handed to [block] so it can also set it on the
 * HTTP call itself (`call.timeout()`): a coroutine timeout cannot interrupt a blocked body read, the
 * call's own timeout can.
 */
internal suspend fun <T> withRetry(timeoutsMs: List<Long> = listOf(2_500L, 8_000L), block: suspend (timeoutMs: Long) -> T): T {
    var last: Exception? = null
    for (timeoutMs in timeoutsMs) {
        try {
            return withTimeout(timeoutMs + 1_000L) { block(timeoutMs) }
        } catch (hung: TimeoutCancellationException) {
            last = hung
        } catch (failure: IOException) {
            last = failure
        }
    }
    // a timeout is reported as an I/O failure: as a cancellation it would look like the caller was cancelled
    throw (last as? IOException) ?: IOException("no answer within ${timeoutsMs.sum()} ms", last)
}

/** The call with [timeoutMs] as its limit for everything, response body included. */
internal fun okhttp3.Call.within(timeoutMs: Long): okhttp3.Call = also { timeout().timeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS) }

/**
 * The result of [block], or null when it fails. Unlike `runCatching` it lets a cancellation through,
 * so a cancelled coroutine stops instead of carrying on with a null.
 */
internal inline fun <T> tryOrNull(block: () -> T): T? =
    try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        null
    }
