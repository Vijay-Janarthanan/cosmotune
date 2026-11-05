package com.cosmotune.video.segments

import kotlin.jvm.Synchronized

/**
 * A small in-memory cache for the three clients (spec 7.1 to 7.3: SponsorBlock 1 hour, DeArrow 24 hours, Return YouTube
 * Dislike 3 minutes). An entry is fresh for [ttlMs] after it was stored; at most [maxEntries] are kept, and when there is
 * no room the expired ones go first, then the oldest. Storing a key again replaces it and makes it the newest.
 * [clock] is milliseconds. Safe to call from several threads.
 */
class TtlCache<K, V : Any>(
    private val ttlMs: Long,
    private val maxEntries: Int,
    private val clock: () -> Long,
) {
    init {
        require(ttlMs > 0) { "ttlMs must be positive" }
        require(maxEntries > 0) { "maxEntries must be positive" }
    }

    private class Entry<V>(val value: V, val storedAtMs: Long)

    private val entries = LinkedHashMap<K, Entry<V>>()

    /** The number of entries held, expired ones not yet dropped included. */
    val size: Int
        @Synchronized get() = entries.size

    /** The fresh value stored for [key], or null; an expired entry is dropped. */
    @Synchronized
    fun get(key: K): V? {
        val entry = entries[key] ?: return null
        if (clock() - entry.storedAtMs >= ttlMs) {
            entries.remove(key)
            return null
        }
        return entry.value
    }

    /** Stores [value] for [key] now. */
    @Synchronized
    fun put(key: K, value: V) {
        val now = clock()
        entries.remove(key)
        entries[key] = Entry(value, now)
        if (entries.size > maxEntries) {
            val iterator = entries.iterator()
            while (iterator.hasNext()) {
                if (now - iterator.next().value.storedAtMs >= ttlMs) iterator.remove()
            }
            while (entries.size > maxEntries) {
                val oldest = entries.iterator()
                oldest.next()
                oldest.remove()
            }
        }
    }

    /** Forgets [key]. */
    @Synchronized
    fun remove(key: K) {
        entries.remove(key)
    }

    /** Forgets everything. */
    @Synchronized
    fun clear() {
        entries.clear()
    }
}
