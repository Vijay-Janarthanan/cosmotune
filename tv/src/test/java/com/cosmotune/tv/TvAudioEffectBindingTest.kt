package com.cosmotune.tv

import org.junit.Assert.assertEquals
import org.junit.Test

class TvAudioEffectBindingTest {
    @Test fun disabledProcessingNeverAttachesAndPlaybackHandoffsReleaseThePreviousSession() {
        val events = mutableListOf<String>()
        val binding = TvAudioEffectBinding({ events += "attach:$it" }, { events += "release" })
        binding.update(0, true); binding.update(42, false)
        assertEquals(emptyList<String>(), events)
        binding.update(42, true); binding.update(42, true); binding.update(73, true)
        binding.update(73, false); binding.update(0, false)
        assertEquals(listOf("release", "attach:42", "release", "attach:73", "release"), events)
    }
}
