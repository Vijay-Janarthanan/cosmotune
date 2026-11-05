package com.cosmotune.podio

import kotlin.test.*

class PodioPlaybackPolicyTest {
    @Test fun spokenRatesAreScopedToContentAndTheMusicPreferenceReturns() {
        assertEquals(1f, PodioPlaybackPolicy.speedOnTransition("music", "podio:episode:one", 1.25f))
        assertNull(PodioPlaybackPolicy.speedOnTransition("podio:episode:one", "podio:episode:one", 1.25f))
        assertEquals(1f, PodioPlaybackPolicy.speedOnTransition("podio:episode:one", "podio:episode:two", 1.25f))
        assertEquals(1.25f, PodioPlaybackPolicy.speedOnTransition("podio:episode:one", "music", 1.25f))
        assertEquals(1.25f, PodioPlaybackPolicy.speedOnTransition("podio:radio:one", "music", 1.25f))
        assertNull(PodioPlaybackPolicy.speedOnTransition("music", "other-music", 1.25f))
        assertNull(PodioPlaybackPolicy.speedOnTransition("podio:episode:one", null, 1.25f))
    }
}
