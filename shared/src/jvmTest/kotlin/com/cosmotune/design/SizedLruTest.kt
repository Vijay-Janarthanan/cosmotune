package com.cosmotune.design

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/** The cache of the outline masks (P4-21): least recently used out first, held to a budget of bytes rather than of entries. */
class SizedLruTest {

    private class Blob(val bytes: Long)

    private fun lru(max: Long) = SizedLru<String, Blob>(max) { it.bytes }

    @Test fun aValueIsMadeOnceAndThenServedFromTheCache() {
        val cache = lru(100)
        var made = 0
        val first = cache.getOrPut("a") { made++; Blob(10) }
        val second = cache.getOrPut("a") { made++; Blob(10) }
        assertSame(first, second)
        assertEquals(1, made)
        assertEquals(10L, cache.size)
        assertEquals(1, cache.count)
    }

    @Test fun theLeastRecentlyUsedGoesFirstWhenTheBudgetIsPassed() {
        val cache = lru(30)
        cache.getOrPut("a") { Blob(10) }
        cache.getOrPut("b") { Blob(10) }
        cache.getOrPut("c") { Blob(10) }
        cache["a"]                                  // a is used again: b is now the oldest
        cache.getOrPut("d") { Blob(10) }
        assertNull(cache["b"], "the least recently used entry is dropped")
        for (key in listOf("a", "c", "d")) assertEquals(10L, cache[key]?.bytes, "$key stays")
        assertEquals(30L, cache.size)
    }

    @Test fun aValueLargerThanTheWholeBudgetIsReturnedButNotKept() {
        val cache = lru(30)
        cache.getOrPut("small") { Blob(10) }
        val big = cache.getOrPut("big") { Blob(31) }
        assertEquals(31L, big.bytes)
        assertNull(cache["big"])
        assertEquals(10L, cache.size, "and it pushes nothing out")
    }

    @Test fun replacingAValueCountsOnlyTheNewOne() {
        val cache = lru(100)
        cache.put("a", Blob(40))
        cache.put("a", Blob(25))
        assertEquals(25L, cache.size)
        assertEquals(1, cache.count)
        cache.clear()
        assertEquals(0L, cache.size)
        assertNull(cache["a"])
    }
}
