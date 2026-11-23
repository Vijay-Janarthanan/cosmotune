package com.cosmotune.settings

import com.cosmotune.audio.SoundEffect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SongSoundEffectTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
        override fun getString(key: String, default: String) = map[key] as? String ?: default
        override fun putString(key: String, value: String) { map[key] = value }
    }

    @Test fun aSongKeepsItsOwnEffectAndOlderEntriesStillRead() {
        val tuned = mapOf("song:a" to SongPlaybackTuning(1.25f, 2, SoundEffect.HALL.name), "song:b" to SongPlaybackTuning(0.9f, 0))
        val again = SongPlaybackMemory.decode(SongPlaybackMemory.encode(tuned))
        assertEquals(tuned, again)
        // Saved before songs had their own effect: three fields.
        assertEquals(SongPlaybackTuning(1.5f, -1), SongPlaybackMemory.decode("song:c\t1.5\t-1")["song:c"])
        assertNull(SongPlaybackTuning(1f, 0, "SPARKLE").normalized().effect)
    }

    @Test fun thePlayingSongsEffectWinsOverTheDefault() {
        val settings = ExtrasSettings(MapStore())
        settings.setSoundEffect(SoundEffect.ECHO)
        settings.setSongPlayback("song:a", SongPlaybackTuning(1f, 0, SoundEffect.ROOM_3D.name))
        settings.setSongPlayback("song:b", SongPlaybackTuning(1.1f, 0))
        settings.playingSongId = "song:a"
        assertEquals(SoundEffect.ROOM_3D, settings.effectNow())
        settings.playingSongId = "song:b"
        assertEquals(SoundEffect.ECHO, settings.effectNow())
        settings.setSongPlayback("song:b", SongPlaybackTuning(1.1f, 0, SoundEffect.NONE.name))
        assertEquals(SoundEffect.NONE, settings.effectNow())
        settings.playingSongId = null
        assertEquals(SoundEffect.ECHO, settings.effectNow())
    }
}
