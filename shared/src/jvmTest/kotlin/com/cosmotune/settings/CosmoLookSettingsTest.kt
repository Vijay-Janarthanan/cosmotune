package com.cosmotune.settings

import com.cosmotune.CosmoConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CosmoLookSettingsTest {
    private class MapStore : KeyValueStore {
        val map = mutableMapOf<String, Any>()
        override fun getBoolean(key: String, default: Boolean) = map[key] as? Boolean ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
        override fun getInt(key: String, default: Int) = map[key] as? Int ?: default
        override fun putInt(key: String, value: Int) { map[key] = value }
    }

    /** A store from before the intensity setting: it keeps the two default bodies of [KeyValueStore.getInt] and [KeyValueStore.putInt]. */
    private class BooleansOnlyStore : KeyValueStore {
        val map = mutableMapOf<String, Boolean>()
        override fun getBoolean(key: String, default: Boolean) = map[key] ?: default
        override fun putBoolean(key: String, value: Boolean) { map[key] = value }
    }

    @Test fun anEmptyStoreStartsWithTheNewLookOn() {
        assertTrue(CosmoConfig.NEW_LOOK_DEFAULT, "the new look is the default since part two of it was finished")
        assertEquals(true, CosmoLookSettings(MapStore()).newLook.value, "a fresh install shows the new look")
    }

    @Test fun startsFromTheDefaultsAndRemembersChanges() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(CosmoConfig.NEW_LOOK_DEFAULT, settings.newLook.value)
        assertTrue(settings.coverColours.value, "colours follow the cover unless switched off")
        // an explicit choice is kept, also the one that goes against the default
        settings.setNewLook(false)
        settings.setCoverColours(false)
        val again = CosmoLookSettings(store)
        assertEquals(false, again.newLook.value)
        assertEquals(false, again.coverColours.value)
    }

    @Test fun intensityStartsBalancedAndRemembersTheChoice() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        assertEquals(1, settings.intensity.value, "Balanced is the default")
        settings.setIntensity(2)
        assertEquals(2, settings.intensity.value)
        assertEquals(2, store.map["intensity"], "kept under the key `intensity`")
        assertEquals(2, CosmoLookSettings(store).intensity.value)
        settings.setIntensity(0)
        assertEquals(0, CosmoLookSettings(store).intensity.value)
    }

    @Test fun anIntensityOutsideTheThreeIsHeldInsideThem() {
        val store = MapStore()
        val settings = CosmoLookSettings(store)
        settings.setIntensity(7)
        assertEquals(2, settings.intensity.value)
        settings.setIntensity(-3)
        assertEquals(0, settings.intensity.value)
        store.map["intensity"] = 99
        assertEquals(2, CosmoLookSettings(store).intensity.value, "a stored value out of range is clamped when read")
    }

    @Test fun aStoreThatKnowsNothingOfIntegersStaysBalanced() {
        val store = BooleansOnlyStore()
        val settings = CosmoLookSettings(store)
        assertEquals(1, settings.intensity.value)
        settings.setIntensity(2)
        assertEquals(2, settings.intensity.value, "the flow still follows, even where nothing is kept")
        settings.setNewLook(true)
        assertEquals(true, CosmoLookSettings(store).newLook.value)
    }
}
