package com.cosmotune.providers

import kotlinx.coroutines.*
import okhttp3.Call
import okhttp3.Response

/** Cancel the socket and finish the IO worker before a caller can reuse or remove its destination. */
internal suspend fun <T> Call.withReply(read: suspend (Response) -> T): T = supervisorScope {
    val request = this@withReply
    val worker = async(Dispatchers.IO) {
        request.execute().use { reply -> currentCoroutineContext().ensureActive(); read(reply) }
    }
    try { worker.await() }
    finally {
        if (!currentCoroutineContext().isActive) {
            request.cancel()
            withContext(NonCancellable) { worker.cancelAndJoin() }
        }
    }
}
