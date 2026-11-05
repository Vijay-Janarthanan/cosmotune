package com.cosmotune.music.data.sources

import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/** Provider-local capacity and start spacing. Waiting on one provider never locks another. */
class ProviderTransferGate(private val parallel: Int = 2, private val startGapMs: Long = 500,
    private val clockMs: () -> Long = { System.nanoTime() / 1_000_000 }) {
    init { require(parallel in 1..16 && startGapMs in 0..60_000) }
    private class Lane(parallel: Int) {
        val slots = Semaphore(parallel)
        val starts = Mutex()
        var lastStart: Long? = null
        var users = 0
    }
    private val lock = Any()
    private val lanes = HashMap<String, Lane>()

    suspend fun <T> transfer(provider: String, operation: suspend () -> T): T {
        require(provider.isNotBlank())
        val lane = synchronized(lock) { lanes.getOrPut(provider) { Lane(parallel) }.also { it.users++ } }
        try {
            return lane.slots.withPermit {
                lane.starts.withLock {
                    val last = lane.lastStart
                    if (last != null) {
                        val remaining = startGapMs - (clockMs() - last).coerceAtLeast(0)
                        if (remaining > 0) delay(remaining)
                    }
                    lane.lastStart = clockMs()
                }
                operation()
            }
        } finally {
            synchronized(lock) {
                lane.users--
                // Keep the last start while its cooldown matters, but bound idle provider storage.
                if (lanes.size > 256) lanes.entries.removeAll { (_, value) ->
                    value.users == 0 && value.lastStart?.let { clockMs() - it >= startGapMs } != false
                }
            }
        }
    }
}

object ProviderDownloadTransfers {
    private val gate = ProviderTransferGate()
    suspend fun <T> transfer(provider: String, operation: suspend () -> T): T = gate.transfer(provider, operation)
}
