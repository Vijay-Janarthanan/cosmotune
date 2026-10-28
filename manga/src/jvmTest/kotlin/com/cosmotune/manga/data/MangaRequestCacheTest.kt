package com.cosmotune.manga.data

import kotlinx.coroutines.*
import kotlin.test.*

class MangaRequestCacheTest {
    @Test fun aCancelledWaiterDoesNotCancelTheSharedLoadAndFailuresCanBeRetried() = runBlocking {
        val cache = MangaRequestCache<String, Int>(2, 1_000)
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        var calls = 0
        val owner = async { cache.get("key") { calls++; entered.complete(Unit); finish.await(); 7 } }
        entered.await()
        val waiter = async { cache.get("key") { error("must share") } }
        yield(); waiter.cancelAndJoin(); finish.complete(Unit)
        assertEquals(7, owner.await())
        assertEquals(7, cache.get("key") { error("must be cached") })
        assertEquals(1, calls)
        assertFailsWith<IllegalStateException> { cache.get("failure") { error("offline") } }
        assertEquals(8, cache.get("failure") { 8 })
    }

    @Test fun capacityExpiryAndClearNeverPublishOldPendingResults() = runBlocking {
        var now = 10L
        val cache = MangaRequestCache<String, Int>(2, 100) { now }
        cache.get("a") { 1 }; cache.get("b") { 2 }; cache.get("c") { 3 }
        assertEquals(4, cache.get("a") { 4 }, "old completed result was evicted")
        now += 100
        assertEquals(5, cache.get("a") { 5 }, "expired result reloaded")
        val entered = CompletableDeferred<Unit>(); val finish = CompletableDeferred<Unit>()
        val pending = async { cache.get("pending") { entered.complete(Unit); finish.await(); 6 } }
        entered.await(); cache.clear(); finish.complete(Unit)
        assertEquals(6, pending.await(), "the caller still receives its own request")
        assertEquals(7, cache.get("pending") { 7 }, "clearing does not resurrect a pending result")
    }
}
