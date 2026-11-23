package com.cosmotune.podio

import kotlin.test.*

class PodioDownloadEtaTest {
    @Test fun theTimeLeftComesFromTheSpeedSoFar() {
        var now = 0L
        val eta = PodioDownloadEta { now }
        assertNull(eta.left("a", 0.10f))
        now = 2_000
        assertNull(eta.left("a", 0.20f), "too early to say")
        now = 30_000
        assertEquals("about 2 min left", eta.left("a", 0.30f))
        now = 290_000
        assertEquals("under a minute left", eta.left("a", 0.95f))
        eta.keepOnly(emptySet())
        assertNull(eta.left("a", 0.96f), "a new start")
    }
}
