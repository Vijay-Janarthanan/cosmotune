package com.cosmotune.manga.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Bounded, in-memory single-flight cache. Failed/cancelled loads never become cached answers. */
internal class MangaRequestCache<K, V>(private val capacity: Int, private val ttlMs: Long,
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 }) {
    private class Entry<V>(val answer: CompletableDeferred<V> = CompletableDeferred(), var writtenAt: Long? = null)
    private val lock = Mutex()
    private val entries = LinkedHashMap<K, Entry<V>>()

    init { require(capacity > 0 && ttlMs > 0) }

    suspend fun get(key: K, refresh: Boolean = false, load: suspend () -> V): V {
        var owner = false
        val entry = lock.withLock {
            val now = clock()
            entries.entries.removeAll { (_, value) -> value.writtenAt?.let { now - it >= ttlMs || now < it } == true }
            val existing = entries[key]
            if (existing != null && (!refresh || !existing.answer.isCompleted)) existing
            else Entry<V>().also {
                owner = true
                entries.remove(key)
                entries[key] = it
                // Pending entries belong to their callers. Drop only finished entries to bound
                // retained results without cancelling an unrelated catalogue request.
                while (entries.size > capacity) {
                    val oldest = entries.entries.firstOrNull { item -> item.value.answer.isCompleted } ?: break
                    entries.remove(oldest.key)
                }
            }
        }
        if (!owner) return entry.answer.await()
        try {
            val value = load()
            lock.withLock {
                entry.writtenAt = clock()
                entry.answer.complete(value)
                while (entries.size > capacity) {
                    val oldest = entries.entries.firstOrNull { it.value.answer.isCompleted } ?: break
                    entries.remove(oldest.key)
                }
            }
            return value
        } catch (failure: Throwable) {
            withContext(NonCancellable) { lock.withLock {
                if (entries[key] === entry) entries.remove(key)
                entry.answer.completeExceptionally(failure)
            } }
            throw failure
        }
    }

    suspend fun clear() = lock.withLock { entries.clear() }
}
