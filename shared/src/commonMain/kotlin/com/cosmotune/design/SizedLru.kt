package com.cosmotune.design

/**
 * A least-recently-used cache held to a budget of [maxSize] units (bytes, for the outline masks of the new look) rather than to a
 * number of entries: [sizeOf] says what a value costs. Adding past the budget drops the entries used longest ago; a value that is
 * larger than the whole budget is handed back by [getOrPut] but not kept, so it cannot push everything else out. Not thread-safe:
 * the masks are made and read on the UI thread only.
 */
class SizedLru<K : Any, V : Any>(val maxSize: Long, private val sizeOf: (V) -> Long) {
    // Insertion order is recency order: a read takes the entry out and puts it back at the end.
    private val map = LinkedHashMap<K, V>()

    /** What the kept values cost together. */
    var size: Long = 0L
        private set

    /** How many values are kept. */
    val count: Int get() = map.size

    /** The value kept for [key], which becomes the most recently used, or null. */
    operator fun get(key: K): V? {
        val value = map.remove(key) ?: return null
        map[key] = value
        return value
    }

    /** Keeps [value] for [key] (replacing what was there) unless it is larger than the whole budget, then trims to the budget. */
    fun put(key: K, value: V) {
        map.remove(key)?.let { size -= sizeOf(it) }
        val cost = sizeOf(value)
        if (cost > maxSize) return
        map[key] = value
        size += cost
        trim()
    }

    /** The value kept for [key], or the one [make] returns, kept for next time. */
    fun getOrPut(key: K, make: () -> V): V = get(key) ?: make().also { put(key, it) }

    fun clear() {
        map.clear()
        size = 0L
    }

    private fun trim() {
        val keys = map.keys.iterator()
        while (size > maxSize && keys.hasNext()) {
            val oldest = keys.next()
            size -= sizeOf(map.getValue(oldest))
            keys.remove()
        }
    }
}
