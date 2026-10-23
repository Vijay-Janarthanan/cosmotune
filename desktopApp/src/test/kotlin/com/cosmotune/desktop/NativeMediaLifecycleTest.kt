package com.cosmotune.desktop

import kotlin.test.*

class NativeMediaLifecycleTest {
    @Test fun failedStartupReleasesItsNativeThreadBeforeRetry() {
        var stopped = 0
        repeat(3) {
            assertFalse(acquireNativeMediaControls({ false }, { stopped++ }))
            assertEquals(it + 1, stopped)
        }
    }
    @Test fun exceptionalStartupAlsoReleasesPartialResources() {
        var stopped = false
        val failure = IllegalStateException("media controls unavailable")
        assertSame(failure, assertFailsWith<IllegalStateException> {
            acquireNativeMediaControls({ throw failure }, { stopped = true })
        })
        assertTrue(stopped)
    }
    @Test fun successfulStartupKeepsItsResourcesUntilNormalStop() {
        var stopped = false
        assertTrue(acquireNativeMediaControls({ true }, { stopped = true }))
        assertFalse(stopped)
    }
}
