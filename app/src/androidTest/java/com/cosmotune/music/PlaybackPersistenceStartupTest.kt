package com.cosmotune.music

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cosmotune.music.playback.BoundedContextPersistence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class PlaybackPersistenceStartupTest {
    @Test
    fun applicationOpensContextPersistenceBeforeAnyPlayback() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.applicationContext is CosmotuneApplication)
        val before = BoundedContextPersistence.load()
        // An obsolete callback must read the initialized store and leave any
        // existing queue intact. No test init call: production startup owns it.
        BoundedContextPersistence.savePlaybackState("startup-probe:${UUID.randomUUID()}", 0)
        assertEquals(before, BoundedContextPersistence.load())
    }
}
