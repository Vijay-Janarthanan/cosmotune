package com.cosmotune.video.shorts

import com.cosmotune.video.model.VideoItem
import kotlin.jvm.Synchronized

/**
 * Hands the Shorts of the list a card was opened from to the pager (spec 5.15: "that Short, then the other Shorts of the
 * list it was opened from"): a route carries only a key (`VideoRoute.Shorts.seedKey`), the list waits here. Keeps the
 * [CAPACITY] most recent keys; [take] removes what it returns.
 */
object ShortsSeedCache {
    /** How many seeds are kept: 8. */
    const val CAPACITY = 8

    private val seeds = LinkedHashMap<String, List<VideoItem>>()
    private var counter = 0L

    /** Keeps [items] under a fresh key and returns the key. */
    @Synchronized
    fun put(items: List<VideoItem>): String {
        counter += 1
        val key = "seed-$counter"
        store(key, items)
        return key
    }

    /** Keeps [items] under [key], replacing what was there. */
    @Synchronized
    fun put(key: String, items: List<VideoItem>) = store(key, items)

    /** The items kept under [key], removed from the cache, or null when the key is unknown or was dropped. */
    @Synchronized
    fun take(key: String): List<VideoItem>? = seeds.remove(key)

    /** Forgets every seed (tests). */
    @Synchronized
    fun clear() = seeds.clear()

    private fun store(key: String, items: List<VideoItem>) {
        seeds.remove(key)
        seeds[key] = items.toList()
        while (seeds.size > CAPACITY) seeds.remove(seeds.keys.first())
    }
}
