package com.cosmotune.video.player.desktop

import kotlin.test.*

class SessionRangeCacheTest {
    @Test fun completeRangesServeContainedReadsAndKeepImmutableBytes() {
        val cache = SessionRangeCache(32)
        val owner = Any()
        val bytes = ByteArray(16) { it.toByte() }
        cache.put(owner, 100, 115, bytes)
        bytes[4] = 99
        val found = assertNotNull(cache.get(owner, 104, 109))
        assertContentEquals(byteArrayOf(4, 5, 6, 7, 8, 9), found)
        found[0] = 88
        assertEquals(4, cache.get(owner, 104, 109)!![0].toInt())
        assertNull(cache.get(owner, 99, 110))
        assertNull(cache.get(Any(), 100, 115))
    }

    @Test fun aggregateLimitAndLruApplyAcrossAudioAndVideoOwners() {
        val cache = SessionRangeCache(16)
        val video = Any(); val audio = Any(); val later = Any()
        cache.put(video, 0, 7, ByteArray(8) { 1 })
        cache.put(audio, 0, 7, ByteArray(8) { 2 })
        assertNotNull(cache.get(video, 0, 7))
        cache.put(later, 0, 7, ByteArray(8) { 3 })
        assertEquals(16L, cache.bytes)
        assertNull(cache.get(audio, 0, 7))
        assertNotNull(cache.get(video, 0, 7))
        cache.put(video, 0, 15, ByteArray(16) { 4 })
        assertEquals(16L, cache.bytes)
        assertNull(cache.get(later, 0, 7))
        assertContentEquals(ByteArray(16) { 4 }, cache.get(video, 0, 15))
    }

    @Test fun partialOversizedAndInvalidatedRangesCannotEnterTheCache() {
        val cache = SessionRangeCache(16)
        val owner = Any()
        cache.put(owner, 0, 15, ByteArray(8))
        cache.put(owner, 0, 31, ByteArray(32))
        assertEquals(0L, cache.bytes)
        cache.put(owner, 0, 7, ByteArray(8))
        cache.invalidate()
        cache.put(owner, 0, 7, ByteArray(8))
        assertEquals(0L, cache.bytes)
        assertNull(cache.get(owner, 0, 7))
    }
}
