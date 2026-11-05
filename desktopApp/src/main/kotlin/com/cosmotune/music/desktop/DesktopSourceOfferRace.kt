package com.cosmotune.music.desktop

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull

/** Keep completed offers when a sibling stalls, and release a useful upgrade without waiting for it. */
internal suspend fun awaitSourceOffer(
    requests: List<suspend () -> DesktopStream?>,
    timeoutMs: Long,
    better: (DesktopStream?, DesktopStream) -> DesktopStream,
    ready: (DesktopStream) -> Boolean,
): DesktopStream? = coroutineScope {
    val running = requests.map { request -> async { request() } }.toMutableList()
    var best: DesktopStream? = null
    try {
        withTimeoutOrNull(timeoutMs) {
            while (running.isNotEmpty()) {
                val done = select { running.forEach { request -> request.onAwait { request } } }
                running.remove(done)
                done.await()?.let { best = better(best, it) }
                if (best?.let(ready) == true) break
            }
        }
        best
    } finally {
        running.forEach { it.cancel() }
    }
}
