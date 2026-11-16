package com.cosmotune.manga.data

import java.util.ArrayDeque
import kotlin.coroutines.CoroutineContext
import kotlin.test.*
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.koitharu.kotatsu.parsers.ParsersBundle

class ParserBundleObserverTest {
    /** Controls exactly when subscription begins; no sleeps or timing assumptions. */
    private class QueuedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) { pending.addLast(block) }
        fun drain() { while (pending.isNotEmpty()) pending.removeFirst().run() }
    }

    @Test fun startupReplayDoesNotInvalidateRequestsButActualIdentityChangesDo() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val original = bundledParsers()
        val bundles = MutableStateFlow(original)
        var invalidations = 0
        scope.observeParserBundleChanges(bundles) { invalidations++ }
        try {
            dispatcher.drain()
            assertEquals(0, invalidations, "the initial unchanged StateFlow emission must preserve single-flight/cache keys")
            val updated = object : ParsersBundle by original {}
            bundles.value = updated
            dispatcher.drain()
            assertEquals(1, invalidations)
            bundles.value = updated
            dispatcher.drain()
            assertEquals(1, invalidations, "re-observing the same bundle does not reset catalogue generations")
            bundles.value = object : ParsersBundle by original {}
            dispatcher.drain()
            assertEquals(2, invalidations, "a replacement identity is observed even with the same metadata")
        } finally { scope.cancel(); dispatcher.drain() }
    }

    @Test fun replacementBetweenObserverCreationAndCollectorStartIsNotDropped() {
        val dispatcher = QueuedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val original = bundledParsers()
        val bundles = MutableStateFlow(original)
        var invalidations = 0
        scope.observeParserBundleChanges(bundles) { invalidations++ }
        try {
            bundles.value = object : ParsersBundle by original {}
            assertEquals(0, invalidations)
            dispatcher.drain()
            assertEquals(1, invalidations, "drop(1) or capturing inside launch would miss this real replacement")
        } finally { scope.cancel(); dispatcher.drain() }
    }
}
