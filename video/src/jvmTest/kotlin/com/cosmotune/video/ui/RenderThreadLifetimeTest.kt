package com.cosmotune.video.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch

class RenderThreadLifetimeTest {
    @Test fun sceneCancellationRunsItsFinallyBeforeTheNextSceneCanBegin() {
        var disposed = false
        val context = RenderThread.begin()
        val job = CoroutineScope(context).launch {
            try { awaitCancellation() } finally { disposed = true }
        }
        try { RenderThread.drain() } finally { RenderThread.end() }
        assertTrue(disposed)
        assertTrue(job.isCompleted)
        RenderThread.begin()
        try { assertTrue(disposed) } finally { RenderThread.end() }
    }
    @Test fun aClosedScenesLateContinuationCannotRunInsideTheNextScenesComposition() {
        var oldWrites = 0
        var currentWrites = 0
        val old = RenderThread.begin()
        try { RenderThread.dispatch(old, Runnable { oldWrites++ }) }
        finally { RenderThread.end() }
        assertEquals(1, oldWrites, "queued cleanup finishes within the retired scene")
        val current = RenderThread.begin()
        try {
            assertTrue(RenderThread.isDispatchNeeded(old), "a stale immediate continuation must be dispatched and rejected")
            RenderThread.dispatch(old, Runnable { oldWrites++ })
            RenderThread.dispatch(current, Runnable { currentWrites++ })
            RenderThread.drain()
            assertEquals(1, oldWrites, "the late continuation must not write in the new scene")
            assertEquals(1, currentWrites)
        } finally { RenderThread.end() }
    }
}
