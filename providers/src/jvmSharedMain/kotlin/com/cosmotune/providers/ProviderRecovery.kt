package com.cosmotune.providers

/** Process-scoped circuit state. Credential storage and the user's enabled switch are never touched. */
internal class ProviderRecovery(private val now: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private data class Failure(val attempts: Int, val retryAt: Long)
    private val failures = LinkedHashMap<String, Failure>()
    @Synchronized fun failed(name: String) {
        val attempts = ((failures[name]?.attempts ?: 0) + 1).coerceAtMost(8)
        val delay = if (attempts < 2) 0L else (15_000L shl (attempts - 2)).coerceAtMost(120_000)
        failures[name] = Failure(attempts, now() + delay)
    }
    @Synchronized fun remainingMs(name: String): Long = failures[name]?.let { (it.retryAt - now()).coerceAtLeast(0) } ?: 0
    @Synchronized fun due(): List<String> = failures.filterValues { it.retryAt <= now() }.keys.toList()
    @Synchronized fun names(): List<String> = failures.keys.toList()
    @Synchronized fun clear(name: String) { failures.remove(name) }
}

class ProviderBackoffException(val retryAfterMs: Long) : java.io.IOException("Provider is resting after a temporary failure. Try again or wait for automatic reconnect.")
