package com.cosmotune.providers

import kotlinx.coroutines.*

/** Release blocking host IO on cancellation, then join the VM worker before its state can be reused. */
internal suspend fun <T> providerRuntimeWork(cancel: () -> Unit, block: suspend () -> T): T = supervisorScope {
    val worker = async(Dispatchers.IO) { block() }
    try { worker.await() }
    finally {
        if (!currentCoroutineContext().isActive) withContext(NonCancellable + Dispatchers.IO) {
            cancel()
            worker.cancelAndJoin()
        }
    }
}
